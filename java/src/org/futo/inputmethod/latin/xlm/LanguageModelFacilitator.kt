package org.futo.inputmethod.latin.xlm;

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.futo.inputmethod.engine.general.OnGetSuggestedWordsCallbackWithInputStyle
import org.futo.inputmethod.keyboard.Keyboard
import org.futo.inputmethod.keyboard.KeyboardSwitcher
import org.futo.inputmethod.latin.BinaryDictionary
import org.futo.inputmethod.latin.BuildConfig
import org.futo.inputmethod.latin.Dictionary
import org.futo.inputmethod.latin.DictionaryFacilitator
import org.futo.inputmethod.latin.NgramContext
import org.futo.inputmethod.latin.Suggest
import org.futo.inputmethod.latin.SuggestedWords
import org.futo.inputmethod.latin.SuggestedWords.SuggestedWordInfo
import org.futo.inputmethod.latin.SuggestionBlacklist
import org.futo.inputmethod.latin.common.ComposedData
import org.futo.inputmethod.latin.inputlogic.InputLogic
import org.futo.inputmethod.latin.settings.Settings
import org.futo.inputmethod.latin.uix.BuiltinWordList
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.USE_TRANSFORMER_FINETUNING
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.utils.DebugFileLogger
import org.futo.inputmethod.latin.utils.SuggestionResults
import kotlin.math.ceil

// Bottom-row letters that can be a mistyped space bar when splitting a run-together token.
// c/v/b/n sit directly above the space bar; z/x/m are the outer keys of the same row.
private const val SPACE_SPLIT_STRONG_KEYS = "cvbn"
private const val SPACE_SPLIT_WEAK_KEYS = "zxm"

// When the dictionary has no useful opinion (its best candidate scores below zero) we fall back to
// the transformer, but only when the transformer is reasonably sure. Observed confident
// corrections such as "gjthun" -> "GitHub" score around 1.4M.
private const val MIN_TRANSFORMER_AUTOCORRECT_SCORE = 500_000

// When the dictionary has no positive opinion we fall back to the transformer, but it must not be
// allowed to commit an invention. The model's word has to be a real word and close to what was
// typed; this blocks "deepseel" -> "deepsells", "glasswit" -> "glass-wire", "Mouthguar" ->
// "Northern" while still allowing "someok" -> "someone", "didnyn" -> "didn't", "andr9uj" ->
// "Android". Words the user added to the personal dictionary count as real words.
private const val MAX_TRANSFORMER_CORRECTION_EDIT_DISTANCE = 3

// How close a word from the user's own dictionaries (personal dictionary or typing history) must
// be to what was typed before it is preferred over the model's guess. Only used when what was
// typed is not itself a word, so it cannot hijack a real word like it used to.
private const val MAX_USER_WORD_TYPO_DISTANCE = 3

val AutocorrectThresholdSetting = SettingsKey(
    floatPreferencesKey("lm_autocorrect_threshold"),
    4.0f
)

val AutocorrectMaxLengthDeltaSetting = SettingsKey(
    intPreferencesKey("lm_autocorrect_max_length_delta"),
    Suggest.DEFAULT_MAX_AUTO_CORRECTION_LENGTH_DELTA
)

val BinaryDictTransformerWeightSetting = SettingsKey(
    floatPreferencesKey("binary_dict_result_weight"),
    3.4f
)

val AllowTransformerOnNonQWERTYLayouts = SettingsKey(
    booleanPreferencesKey("allow_transformer_lm_on_non_qwerty"),
    false
)

internal fun SuggestedWordInfo.add(other: SuggestedWordInfo): SuggestedWordInfo {
    assert(mWord == other.mWord)

    val result = SuggestedWordInfo(
        mWord,
        mPrevWordsContext,
        (mScore.coerceAtLeast(0).toLong() + other.mScore.coerceAtLeast(0).toLong())
            .coerceAtMost(
                Int.MAX_VALUE.toLong()
            ).toInt(),
        SuggestedWordInfo.KIND_WHITELIST or SuggestedWordInfo.KIND_FLAG_APPROPRIATE_FOR_AUTO_CORRECTION,
        mSourceDict ?: other.mSourceDict,
        0,
        0
    )

    result.mOriginatesFromTransformerLM = mOriginatesFromTransformerLM || other.mOriginatesFromTransformerLM

    return result
}


internal fun SuggestedWordInfo.scoreAtLeast(other: SuggestedWordInfo): SuggestedWordInfo {
    val result = SuggestedWordInfo(
        mWord,
        mPrevWordsContext,
        mScore.coerceAtLeast(other.mScore + 1),
        SuggestedWordInfo.KIND_WHITELIST or SuggestedWordInfo.KIND_FLAG_APPROPRIATE_FOR_AUTO_CORRECTION,
        mSourceDict,
        0,
        0
    )

    return result
}

/** Same word, re-scored, and marked as appropriate for auto-correction. */
internal fun SuggestedWordInfo.copyAs(newScore: Int): SuggestedWordInfo {
    val result = SuggestedWordInfo(
        mWord,
        mPrevWordsContext,
        newScore,
        SuggestedWordInfo.KIND_WHITELIST or SuggestedWordInfo.KIND_FLAG_APPROPRIATE_FOR_AUTO_CORRECTION,
        mSourceDict,
        0,
        0
    )
    result.mOriginatesFromTransformerLM = mOriginatesFromTransformerLM
    return result
}

internal fun SuggestedWordInfo.scoreAtMost(other: SuggestedWordInfo): SuggestedWordInfo {
    val result = SuggestedWordInfo(
        mWord,
        mPrevWordsContext,
        mScore.coerceAtMost(other.mScore - 1),
        mKindAndFlags,
        mSourceDict,
        0,
        0
    )

    return result
}

/**
 * The same word and score, but no longer marked as appropriate for auto-correction and no longer
 * carrying the whitelist kind. Such a suggestion is still shown and can still be tapped, but it
 * cannot bypass the auto-correction threshold, so it will not be committed by pressing space.
 */
internal fun SuggestedWordInfo.asPlainSuggestion(): SuggestedWordInfo {
    val withoutAppropriateFlag = mKindAndFlags and
        SuggestedWordInfo.KIND_FLAG_APPROPRIATE_FOR_AUTO_CORRECTION.inv()
    // Clear the kind byte (KIND_MASK_KIND, 0xFF) and set an ordinary correction kind.
    val kind = (withoutAppropriateFlag and 0xFFFFFF00.toInt()) or SuggestedWordInfo.KIND_CORRECTION
    val result = SuggestedWordInfo(
        mWord,
        mPrevWordsContext,
        mScore,
        kind,
        mSourceDict,
        mIndexOfTouchPointOfSecondWord,
        mAutoCommitFirstWordConfidence
    )
    result.mOriginatesFromTransformerLM = mOriginatesFromTransformerLM
    return result
}

/**
 * Whether every space in [candidate] sits on a bottom-row key in [typedWord], so that the split is
 * plausibly a mistyped space bar rather than an unrelated two-word suggestion. [typedWord] and
 * [candidate] must be the same length, i.e. the candidate is the typed word with keys replaced by
 * spaces. Outer keys (z/x/m) are weaker evidence and additionally require the segment before them
 * to already be a real word.
 */
private fun spaceSplitKeysAreAllowed(
    typedWord: String,
    candidate: String,
    isValidWord: (String) -> Boolean
): Boolean {
    if (candidate.length != typedWord.length) return false
    var segmentStart = 0
    for (i in candidate.indices) {
        if (candidate[i] != ' ') continue
        val key = typedWord[i]
        val strong = SPACE_SPLIT_STRONG_KEYS.indexOf(key) >= 0
        val weak = SPACE_SPLIT_WEAK_KEYS.indexOf(key) >= 0
        if (!strong && !weak) return false
        if (weak && !isValidWord(typedWord.substring(segmentStart, i))) return false
        segmentStart = i + 1
    }
    return true
}

private fun levenshteinDistance(s1: String, s2: String): Int {
    val len1 = s1.length
    val len2 = s2.length

    val dist = Array(len1 + 1) { IntArray(len2 + 1) }

    for (i in 0..len1) {
        dist[i][0] = i
    }
    for (j in 0..len2) {
        dist[0][j] = j
    }

    for (j in 1..len2) {
        for (i in 1..len1) {
            val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
            dist[i][j] = minOf(
                dist[i - 1][j] + 1,
                dist[i][j - 1] + 1,
                dist[i - 1][j - 1] + cost
            )
        }
    }

    return dist[len1][len2]
}

/**
 * [typed] is [candidate] with the candidate's last letter repeated ("ohh" vs "oh", "gooo" vs "go",
 * "yesss" vs "yes"). That is a deliberate stylised elongation, not a typo, so it must not be
 * rewritten to the shorter word.
 */
private fun isDeliberateElongationOf(typed: String, candidate: String): Boolean {
    if (typed.length <= candidate.length) return false
    if (!typed.startsWith(candidate, ignoreCase = true)) return false
    val last = candidate.last().lowercaseChar()
    return typed.substring(candidate.length).all { it.lowercaseChar() == last }
}

// Letter positions on the qwerty keyboard, in key widths. The row offsets match the real layout, so
// a diagonal neighbour (b/v, s/d, z/s) counts as adjacent while a vowel confusion such as a/e (1.8
// key widths apart) does not. The letter rows are the standard qwerty layout, which is also what
// Suggest.java's number-row reading assumes and what makePredictionInputValues verifies.
private val KEYBOARD_ROWS = arrayOf(
    "qwertyuiop", // offset 0.0
    "asdfghjkl",  // offset 0.5
    "zxcvbnm"     // offset 1.5
)
private val KEYBOARD_ROW_OFFSETS = floatArrayOf(0.0f, 0.5f, 1.5f)
private const val MAX_NEIGHBOUR_KEY_DISTANCE = 1.6f

private fun keyPosition(ch: Char): Pair<Float, Float>? {
    val lower = ch.lowercaseChar()
    for (row in KEYBOARD_ROWS.indices) {
        val index = KEYBOARD_ROWS[row].indexOf(lower)
        if (index >= 0) return (index + KEYBOARD_ROW_OFFSETS[row]) to row.toFloat()
    }
    return null
}

/** Whether two keys are close enough that one could be a mistap of the other. */
private fun areNeighbouringKeys(a: Char, b: Char): Boolean {
    if (a.equals(b, ignoreCase = true)) return false
    val pa = keyPosition(a) ?: return false
    val pb = keyPosition(b) ?: return false
    val dx = pa.first - pb.first
    val dy = pa.second - pb.second
    return dx * dx + dy * dy <= MAX_NEIGHBOUR_KEY_DISTANCE * MAX_NEIGHBOUR_KEY_DISTANCE
}

/**
 * Whether [candidate] is what was typed with exactly one character replaced by a NEIGHBOURING key.
 * That is the shape of a single fat-fingered key: "habe"/"have" (b next to v), "ans"/"and" (s next
 * to d). A substitution between keys that are not neighbours is not a finger slip, so "tha"/"the"
 * and "revanant"/"revenant" (a and e are 1.8 key widths apart) are not treated as typos, and a
 * dropped or added key is excluded too because it is the shape of "bluesky"/"bluesy".
 */
private fun isSingleNeighbouringKeySlip(typed: String, candidate: String): Boolean {
    if (typed.length != candidate.length) return false
    var differing = -1
    for (i in typed.indices) {
        if (typed[i].equals(candidate[i], ignoreCase = true)) continue
        if (differing >= 0) return false
        differing = i
    }
    return differing >= 0 && areNeighbouringKeys(typed[differing], candidate[differing])
}

public class LanguageModelFacilitator(
    val context: Context,
    val inputLogic: InputLogic,
    val dictionaryFacilitator: DictionaryFacilitator,
    val settings: Settings,
    val keyboardSwitcher: KeyboardSwitcher,
    val lifecycleScope: LifecycleCoroutineScope,
    val suggestionBlacklist: SuggestionBlacklist,
    val suggestedWordsCallback: OnGetSuggestedWordsCallbackWithInputStyle
) {
    private val TAG = "LanguageModelFacilitator"
    private val userDictionary = UserDictionaryObserver(context)

    private var languageModel: LanguageModel? = null
    data class PredictionInputValues(
        val composedData: ComposedData,
        val ngramContext: NgramContext,
        val inputStyle: Int,
        val sequenceId: Int
    )
    private val sharedFlow = MutableSharedFlow<PredictionInputValues>(replay = 0, extraBufferCapacity = 1)

    private var currentSequenceId = 0
    private val sequenceIdFinishedFlow = MutableSharedFlow<Pair<PredictionInputValues, SuggestedWords?>>(replay = 1, extraBufferCapacity = 1)

    @OptIn(ExperimentalCoroutinesApi::class)
    val languageModelScope = LanguageModelScope

    private var numConsecutiveTimeouts = 0
    private var transformerDisabled = false

    fun reportTimeout() {
        if(shouldPassThroughToLegacy()) return

        if(BuildConfig.DEBUG) Log.d(TAG, "Failed to complete prediction within the time!")
        numConsecutiveTimeouts += 1
        if(numConsecutiveTimeouts > 5) {
            transformerDisabled = true
            if(BuildConfig.DEBUG) Log.w(TAG, "Temporarily disabling transformer due to continuous timeouts")
        }
    }

    private var skipLanguage: String? = null
    private suspend fun runLanguageModel(values: PredictionInputValues): ArrayList<SuggestedWordInfo>? {
        if(transformerDisabled) return null

        val locale = dictionaryFacilitator.primaryLocale ?: return null
        if ((languageModel == null && locale.language != skipLanguage) || (languageModel != null && languageModel?.locale?.language != locale.language)) {
            skipLanguage = null
            if(BuildConfig.DEBUG) Log.d(TAG, "Calling closeInternalLocked on model due to seeming locale change")
            languageModel?.closeInternalLocked()
            languageModel = null

            // TODO: Cache value so we're not hitting this repeatedly
            val options = ModelPaths.getModelOptions(context)
            val model = options[locale.language]
            if (model != null) {
                languageModel = LanguageModel(context, lifecycleScope, model, locale)
            } else {
                if(BuildConfig.DEBUG) Log.d(TAG, "no model for ${locale.language}")
                skipLanguage = locale.language
                return null
            }
        }

        if(dictionaryFacilitator.mostConfidentLocale != languageModel?.locale) return null

        val keyboard = keyboardSwitcher.keyboard ?: return null

        val proximityInfoHandle = keyboard.proximityInfo.nativeProximityInfo

        val autocorrectThreshold = context.getSetting(AutocorrectThresholdSetting)

        try {
            // The personal dictionary is a dictionary source, exactly like the built-in
            // dictionaries; it is deliberately not injected into the model's context any more.
            return languageModel?.getSuggestions(
                values.composedData,
                values.ngramContext,
                proximityInfoHandle,
                autocorrectThreshold,
                arrayOf()
            )
        }catch (e: ModelLoadingException) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    "Unable to load Transformer model for ${locale.getDisplayLanguage(locale)}, it may be corrupted or unsupported.",
                    Toast.LENGTH_LONG
                ).show()
                transformerDisabled = true
                e.printStackTrace()
            }
            return null
        }
    }

    suspend fun getLanguageModelSuggestions(values: PredictionInputValues): ArrayList<SuggestedWordInfo>? {
        if(values.composedData.mTypedWord.length > BinaryDictionary.DICTIONARY_MAX_WORD_LENGTH-1)
            return null

        val lmSuggestions = runLanguageModel(values)
        return lmSuggestions
    }

    /**
     * Whether the transformer's word is close enough to what was typed to be committed when the
     * dictionary has no positive opinion. It must be an actual word the keyboard knows (main,
     * contacts or personal dictionary - history does not count) and within a small edit distance
     * of the typed string, so the model cannot force an invented word into the text.
     */
    private fun transformerCorrectionIsPlausible(typed: String, candidate: String): Boolean {
        if (typed.isEmpty() || candidate.isEmpty()) return false
        if (!dictionaryFacilitator.isValidSpellingWord(candidate)) return false
        val distance = levenshteinDistance(typed.lowercase(), candidate.lowercase())
        return distance <= MAX_TRANSFORMER_CORRECTION_EDIT_DISTANCE
    }

    /**
     * The closest word from the user's own dictionaries (personal dictionary or typing history)
     * to [typed], when what was typed is not itself a word and the user's word is a closer match
     * than the model's top guess. This is what turns "deepseel" into the "deepseek" the user has
     * typed before, rather than the model's invention. Returns null when there is no such word.
     */
    private fun findCloseUserWord(
        typed: String,
        dictionaryCandidates: List<SuggestedWordInfo>,
        modelTop: SuggestedWordInfo?
    ): SuggestedWordInfo? {
        if (typed.length < 3) return null
        // A token containing a digit is intentional: "7am", "30k", "40s", "19th" must keep their
        // digits rather than being read as "yam", "wok", "red", "with". The digit->letter mistap
        // logic handles genuine cases such as "hell9" -> "hello" separately.
        if (typed.any { it.isDigit() }) return null
        if (dictionaryFacilitator.isValidSpellingWord(typed)) return null
        val lowerTyped = typed.lowercase()
        val modelDistance = modelTop?.let { levenshteinDistance(lowerTyped, it.mWord.lowercase()) }
        return dictionaryCandidates
            .asSequence()
            .filter {
                val type = it.mSourceDict?.mDictType
                // Only the user's own dictionaries. Widening this to the built-in main dictionary
                // was tried and reverted: when the model's guess is farther away than an ordinary
                // dictionary word, promote-the-closest turned "remt" into "Remy" and "jiu" into
                // "joy" (both verified on device). A built-in word is a credible answer only
                // through the merge's own agreement paths, not through this preference.
                type == Dictionary.TYPE_USER || type == Dictionary.TYPE_USER_HISTORY
            }
            // Machine-seeded built-in words (profanity / OTC medicine names) must not use the
            // user-word preference: they are recognised and suggested like any other dictionary
            // word, but a near-miss of what was typed must not be force-promoted to them. That
            // produced "immig" -> "dammit", "evap" -> "crap", "Wti" -> "Wtf" and similar.
            .filter { it.mWord.lowercase() !in BuiltinWordList.ALL_LOWER }
            // A negative score means the dictionary has no real opinion about this word, so it is
            // not a credible replacement (it is how "holosun" would otherwise become "Holbein").
            .filter { it.mScore > 0 }
            .filter { it.isAprapreateForAutoCorrection() }
            // A user word is preferred as a single token. Multi-word history phrases
            // ("have i", "do i") are not a replacement for what was typed, and preferring one
            // produces the same context-blind split the space-split logic is careful about.
            .filter { !it.mWord.contains(' ') }
            .map { it to levenshteinDistance(lowerTyped, it.mWord.lowercase()) }
            .filter { (_, distance) ->
                distance in 1..MAX_USER_WORD_TYPO_DISTANCE &&
                        (modelDistance == null || distance < modelDistance)
            }
            .minWithOrNull(
                compareBy<Pair<SuggestedWordInfo, Int>> { it.second }
                    .thenByDescending { it.first.mScore }
            )
            ?.first
    }

    fun processAndMergeSuggestions(
        values: PredictionInputValues,
        suggestedWordsDict: SuggestedWords,
        lmSuggestions: ArrayList<SuggestedWordInfo>
    ): SuggestedWords? {
        var transformerWeight = context.getSetting(BinaryDictTransformerWeightSetting)
        if(dictionaryFacilitator.locales.size > 1) transformerWeight = 1.0f

        val suggestionResults = SuggestionResults(
            14, values.ngramContext.isBeginningOfSentenceContext)


        val typedWordForTypoPrior = values.composedData.mTypedWord
        var reweightedSuggestions = lmSuggestions.mapIndexedNotNull { i, it ->
            if(transformerWeight == Float.NEGATIVE_INFINITY) { null } else {
                // Phase 2 typo prior: boost candidates close in edit distance to what was typed,
                // so a near-miss typo outranks a distant high-probability word. Conservative:
                // +24%/+16%/+8% for distance 0/1/2, nothing beyond.
                val typoBoost = if (typedWordForTypoPrior.isNotEmpty()) {
                    1.0f + maxOf(0, 3 - levenshteinDistance(it.mWord, typedWordForTypoPrior)) * 0.08f
                } else {
                    1.0f
                }
                SuggestedWordInfo(
                    it.mWord,
                    it.mPrevWordsContext,
                    (it.mScore.toFloat() * transformerWeight * typoBoost).toLong().coerceAtMost(Int.MAX_VALUE.toLong() - lmSuggestions.size)
                        .toInt() - i + (lmSuggestions.size - 1),
                    it.mKindAndFlags,
                    it.mSourceDict,
                    it.mIndexOfTouchPointOfSecondWord,
                    it.mAutoCommitFirstWordConfidence
                ).apply {
                    this.mOriginatesFromTransformerLM = true
                }
            }
        }

        val maxWord = reweightedSuggestions.maxByOrNull { it.mScore }

        val suggestedWordsDictList = suggestedWordsDict.mSuggestedWordInfoList.filter {
            suggestionBlacklist.isSuggestedWordOk(it)
        }.toMutableList()

        // The dictionary-only path (Suggest.getSuggestedWordsForNonBatchInput) injects the
        // digit->letter reading at MAX_SCORE before the merge, with no knowledge of the sentence.
        // The transformer does read the sentence, so when it is confident about a plausible
        // different word, drop the injected reading here and let the model's context decide. This
        // is what lets context choose "2nd" -> "end" in "the end of the book" or "3rd" -> "red" in
        // "that shirt is red", instead of the digits always winning.
        val typedWordForDigitFix = values.composedData.mTypedWord
        val digitToLetterCandidate = Suggest.findDigitLetterCorrection(
                dictionaryFacilitator, typedWordForDigitFix)
        val transformerHasBetterReading = digitToLetterCandidate != null
                && maxWord != null
                && maxWord.mScore >= MIN_TRANSFORMER_AUTOCORRECT_SCORE
                && !maxWord.mWord.equals(digitToLetterCandidate, ignoreCase = true)
                && transformerCorrectionIsPlausible(typedWordForDigitFix, maxWord.mWord)
        if (transformerHasBetterReading) {
            suggestedWordsDictList.removeAll {
                it.mWord.equals(digitToLetterCandidate, ignoreCase = true)
            }
        }

        // The native dictionary can already split a run-together token at a key that was probably
        // meant to be the space bar (e.g. "laxybdogz" -> "lazy dogs"). Its score is usually low, so
        // the LM merge below would otherwise discard it. When the typed word is not itself a word
        // and the dictionary offers a two-word split of the same length, prefer that split. Only
        // splits where the space replaced one of the bottom-row keys are considered: c/v/b/n sit
        // right above the space bar (strong), while z/x/m are the row's outer keys (weaker).
        val typedWordForSplit = values.composedData.mTypedWord
        val lowerTypedWordForSplit = typedWordForSplit.lowercase()
        val spaceSplitCandidate: SuggestedWordInfo? = if (typedWordForSplit.length > 3
                && !dictionaryFacilitator.isValidSpellingWord(typedWordForSplit)) {
            suggestedWordsDictList.filter {
                val word = it.word
                if (!word.contains(' ')) return@filter false
                // The candidate must be the typed word with some keys replaced by spaces, so the
                // two line up position by position. This allows more than one space
                // ("justnfornthe" -> "just for the"), which a single-index check could not.
                if (word.length != typedWordForSplit.length) return@filter false
                // Don't reduce a word to a fragment on either side.
                if (word.substringBefore(' ').length < 2) return@filter false
                if (word.substringAfterLast(' ').length < 2) return@filter false
                spaceSplitKeysAreAllowed(lowerTypedWordForSplit, word.lowercase()) { candidate ->
                    dictionaryFacilitator.isValidSpellingWord(candidate)
                }
            }.maxByOrNull { it.mScore }
        } else {
            null
        }

        var maxWordDict = suggestedWordsDictList.maxByOrNull {
            if(it == suggestedWordsDict.typedWordInfo
                || it.isKindOf(SuggestedWordInfo.KIND_EMOJI_SUGGESTION)) Int.MIN_VALUE else it.mScore
        }

        val maxNonWhitelistWordDict = suggestedWordsDictList.maxByOrNull {
            if(it == suggestedWordsDict.typedWordInfo
                || it.isKindOf(SuggestedWordInfo.KIND_EMOJI_SUGGESTION)
                || (it.isKindOf(SuggestedWordInfo.KIND_WHITELIST) && it.mSourceDict?.mDictType == "main")) Int.MIN_VALUE else it.mScore
        }

        // English language has some shortcuts, e.g. "bot" -> "not",    "hid" -> "his"
        // These are common misspellings and usually useful corrections, but if the language model
        // believes the original word fits better in the context we should skip the shortcut,
        // else it's impossible to type these words without manually skipping correction in
        // suggestion bar
        if(maxWordDict != null && maxNonWhitelistWordDict != null && maxNonWhitelistWordDict != maxWordDict && maxNonWhitelistWordDict.mWord == maxWord?.mWord) {
            val idx = suggestedWordsDictList.indexOf(maxWordDict)
            suggestedWordsDictList.remove(maxWordDict)
            suggestedWordsDictList.add(idx, maxWordDict.scoreAtMost(maxNonWhitelistWordDict))
            maxWordDict = maxNonWhitelistWordDict
        }

        val bothAlgorithmsCameToSameConclusion = maxWordDict?.mWord == maxWord?.mWord

        // Prefer a word the user has used before over the model's guess when what was typed is not
        // itself a word. This is what turns "deepseel" into "deepseek" and "mouthguar" into
        // "mouthguard" instead of the model's inventions ("deepsells", "Northern").
        val userWordCandidate = findCloseUserWord(typedWordForSplit, suggestedWordsDictList, maxWord)

        // A real-word allowlist correction ("id" -> "I'd", "tine" -> "time") is pinned at MAX_SCORE
        // by the native side so it can be the dictionary's top candidate and be committed when the
        // model agrees with it. But it must not count as a strong dictionary opinion when the model
        // confidently wants a different word. The native side emits the real-word allowlist case as
        // KIND_CORRECTION at MAX_SCORE (outputShortcuts in suggestions_output_utils.cpp) and only
        // the non-word typed case ("alot" -> "a lot") as KIND_WHITELIST, which must stay
        // unconditional because no legitimate word competes with it. So a pinned top that is NOT
        // KIND_WHITELIST is the real-word allowlist correction; the digit->letter injection (also
        // MAX_SCORE, KIND_CORRECTION, but mSourceDict == null) is handled separately by
        // transformerHasBetterReading above.
        val dictTopIsPinnedAllowlistCorrection = maxWordDict != null
                && maxWordDict.mScore == SuggestedWordInfo.MAX_SCORE
                && !maxWordDict.isKindOf(SuggestedWordInfo.KIND_WHITELIST)
        val dictionaryHasNoOpinion = maxWordDict == null || maxWordDict.mScore < 0
                || (dictTopIsPinnedAllowlistCorrection
                        && maxWord != null && maxWordDict.mWord != maxWord.mWord)
        val transformerIsConfident = maxWord != null && maxWord.mScore >= MIN_TRANSFORMER_AUTOCORRECT_SCORE

        // The transformer echoes the typed word straight back as one of its candidates (its kind
        // carries KIND_FLAG_EXACT_MATCH and its source dictionary is null), and because the
        // suggestion strip is ordered by score that echo can outrank the dictionary's own
        // correction. Suggest.obtainNonBatchedInputSuggestedWords then sees "the typed word is the
        // top suggestion" and refuses to auto-correct at all, so pressing space commits the typo
        // ("habe", "parb", "tha", "ans"). When the dictionary does offer a close, appropriate
        // correction for a token that is not itself a word, let that correction take the
        // auto-correction slot instead.
        //
        // The gates are deliberately narrow, because over-correcting a real word is as bad as
        // missing one. The echo is compared ignoring case, because the model returns the typed
        // word capitalised at the start of a sentence ("habe" comes back as "Habe", "parb" as
        // "Parb"), which used to defeat this branch completely. The dictionary's word must be the
        // SAME LENGTH as what was typed with exactly ONE character replaced by a NEIGHBOURING key,
        // and must start with the same letter. That is the shape of a single fat-fingered key, and
        // it is what keeps everything else as typed: "tazo" -> "task" and "parb" -> "path" (two
        // substitutions), "bluesky" -> "bluesy", "twlo" -> "two" and "pre" -> "press" (a dropped
        // or added key), "tha" -> "the" and "revanant" -> "revenant" (a and e are not neighbouring
        // keys), "7am" -> "jam", "danaher" -> "manager", "auth" -> "with" and
        // "descale" -> "rescale".
        val typedWordForEcho = values.composedData.mTypedWord
        val dictionaryCorrectionBeatsEcho: SuggestedWordInfo? = if (
                maxWord != null && maxWordDict != null
                && typedWordForEcho.isNotEmpty()
                && maxWord.mWord.equals(typedWordForEcho, ignoreCase = true)
                && !maxWordDict.mWord.equals(typedWordForEcho, ignoreCase = true)
                && maxWordDict.mScore > 0
                && maxWordDict.isAprapreateForAutoCorrection()
                && !dictionaryFacilitator.isValidSpellingWord(typedWordForEcho)
                && maxWordDict.mWord.first().equals(typedWordForEcho.first(), ignoreCase = true)
                && !isDeliberateElongationOf(typedWordForEcho, maxWordDict.mWord)
                && isSingleNeighbouringKeySlip(typedWordForEcho, maxWordDict.mWord)
        ) maxWordDict else null

        // A single wrong key is the shape of a fast typist's mistake, so when the model proposes a
        // word that differs from what was typed by exactly one NEIGHBOURING key, treat it as a typo
        // and let it commit even though the model did not mark it appropriate-for-auto-correction.
        // This is what makes "noe" -> now and "voicemaols" -> voicemails commit on space. The
        // second case matters because the merge's other transformer paths require the model's word
        // to be a word the dictionary knows, and "voicemails" is not. The same-length,
        // same-first-letter, not-an-echo and neighbouring-key tests keep a word the user meant as
        // typed: the model must be actively proposing a change, only one key may differ, the first
        // letter must be unchanged, and the model must be as sure of it as any other transformer
        // commit is required to be (MIN_TRANSFORMER_AUTOCORRECT_SCORE).
        val singleKeySlipTop: SuggestedWordInfo? = if (
                maxWord != null && typedWordForEcho.isNotEmpty()
                && isSingleNeighbouringKeySlip(typedWordForEcho, maxWord.mWord)
                && !dictionaryFacilitator.isValidSpellingWord(typedWordForEcho)
                && !maxWord.mWord.equals(typedWordForEcho, ignoreCase = true)
                && maxWord.mWord.first().equals(typedWordForEcho.first(), ignoreCase = true)
                && maxWord.mScore >= MIN_TRANSFORMER_AUTOCORRECT_SCORE
        ) maxWord else null

        var autocorrectWord: SuggestedWordInfo? = null
        val filtered = mutableListOf<SuggestedWordInfo>()
        if (singleKeySlipTop != null) {
            if(BuildConfig.DEBUG) Log.d(TAG, "single neighbouring-key slip '${singleKeySlipTop.mWord}' replaces '$typedWordForEcho'")
            val clone = singleKeySlipTop.scoreAtLeast(singleKeySlipTop)
            autocorrectWord = clone
            suggestionResults.add(clone)
            filtered.add(singleKeySlipTop)
        } else if (dictionaryCorrectionBeatsEcho != null && maxWord != null) {
            if(BuildConfig.DEBUG) Log.d(TAG, "dictionary correction '${dictionaryCorrectionBeatsEcho.mWord}' beats transformer echo of '$typedWordForEcho'")
            val clone = dictionaryCorrectionBeatsEcho.scoreAtLeast(maxWord)
            autocorrectWord = clone
            suggestionResults.add(clone)
            filtered.add(dictionaryCorrectionBeatsEcho)
        } else if (userWordCandidate != null && maxWord != null) {
            if(BuildConfig.DEBUG) Log.d(TAG, "preferring user word '${userWordCandidate.mWord}' over model '${maxWord.mWord}'")
            val clone = userWordCandidate.scoreAtLeast(maxWord)
            autocorrectWord = clone
            suggestionResults.add(clone)
            filtered.add(userWordCandidate)
        } else if(dictionaryHasNoOpinion && transformerIsConfident) {
            val candidate = maxWord!!
            if (transformerCorrectionIsPlausible(typedWordForTypoPrior, candidate.mWord)) {
                if(BuildConfig.DEBUG) Log.d(TAG, "dictionary has no opinion, trusting transformer: autocorrect to ${candidate.mWord}")
                // Score the transformer's word above everything so it sits in the auto-correction slot.
                val clone = candidate.copyAs(SuggestedWordInfo.MAX_SCORE)
                autocorrectWord = clone
                suggestionResults.add(clone)
            } else {
                // The dictionary has nothing useful to say and the model's guess is not a believable
                // typo of what was typed (e.g. "deepseel" -> "deepsells", "Mouthguar" ->
                // "Northern"). Keep the model's words in the strip, but strip the flag that lets
                // them bypass the auto-correction threshold, so they can be tapped rather than
                // committed by pressing space.
                if(BuildConfig.DEBUG) Log.d(TAG, "dictionary has no opinion and transformer guess " +
                        "'${candidate.mWord}' is not a plausible correction of " +
                        "'$typedWordForTypoPrior': suggesting only")
                reweightedSuggestions = reweightedSuggestions.map { it.asPlainSuggestion() }
            }
        } else if(bothAlgorithmsCameToSameConclusion && maxWord != null && maxWordDict != null){
            if(BuildConfig.DEBUG) Log.d(TAG, "both algorithms came to same conclusion, autocorrect to ${maxWord.mWord}")
            // We can be pretty confident about autocorrecting this
            val clone = maxWord.add(maxWordDict)
            autocorrectWord = clone
            suggestionResults.add(clone)
            filtered.add(maxWordDict)
            filtered.add(maxWord)
        }
        // In some cases the LM will predict an uppercased word but dictionary predicts lowercased,
        // we should prefer the lowercase version to reduce automatically capitalizing which can be
        // annoying
        val bothAlgorithmsCameToSameConclusionButLowerCased = maxWordDict?.mWord == maxWord?.mWord?.lowercase()
        if(bothAlgorithmsCameToSameConclusionButLowerCased && maxWord != null && maxWordDict != null) {
            if(BuildConfig.DEBUG) Log.d(TAG, "both algorithms came to same conclusion but lowercased, autocorrect to ${maxWord.mWord}")
            val clone = maxWordDict.scoreAtLeast(maxWord)
            autocorrectWord = clone
            suggestionResults.add(clone)
            filtered.add(maxWordDict)
        }

        if(transformerWeight <= 0.0f) {
            if(suggestedWordsDictList.isEmpty()) {
                transformerWeight = 1.0f
            }
        }

        // Only promote the dictionary's two-word split when it is genuinely the better reading.
        // The split is context-blind otherwise: for "checkinf" the dictionary and the model both
        // want "checking" (lmTop=checking, dictTop=checking), but a same-length split whose space
        // happened to land on a strong key ("the king") still won, overriding both. Require that
        // the algorithms have not already agreed on a single word, and that the dictionary did not
        // rank some other single-word candidate above this split.
        val spaceSplit = spaceSplitCandidate
        if (spaceSplit != null
                && !bothAlgorithmsCameToSameConclusion
                && (maxWordDict == null || spaceSplit.mScore >= maxWordDict.mScore)) {
            // Promote the dictionary's two-word split so it becomes the top suggestion and can be
            // committed by pressing space. MAX_SCORE keeps it ahead of the LM single words, and
            // the auto-correction flag lets it pass the threshold check.
            if(BuildConfig.DEBUG) Log.d(TAG, "promoting space-split suggestion: ${spaceSplit.word}")
            suggestionResults.add(SuggestedWordInfo(
                spaceSplit.word,
                spaceSplit.mPrevWordsContext,
                SuggestedWordInfo.MAX_SCORE,
                SuggestedWordInfo.KIND_FLAG_APPROPRIATE_FOR_AUTO_CORRECTION or SuggestedWordInfo.KIND_CORRECTION,
                spaceSplit.mSourceDict,
                spaceSplit.mIndexOfTouchPointOfSecondWord,
                spaceSplit.mAutoCommitFirstWordConfidence
            ))
            if(!filtered.contains(spaceSplit)) filtered.add(spaceSplit)
        }

        // A token that contains a digit may be a mistap of the number row directly above the top
        // letter row (e.g. "hell9" -> "hello"). Promote the digit->letter reading unless the
        // transformer's sentence-context reading took precedence above.
        if(digitToLetterCandidate != null && !transformerHasBetterReading) {
            if(BuildConfig.DEBUG) Log.d(TAG, "promoting digit->letter correction: $digitToLetterCandidate")
            suggestionResults.add(SuggestedWordInfo(
                digitToLetterCandidate,
                "",
                SuggestedWordInfo.MAX_SCORE,
                SuggestedWordInfo.KIND_FLAG_APPROPRIATE_FOR_AUTO_CORRECTION or SuggestedWordInfo.KIND_CORRECTION,
                null,
                SuggestedWordInfo.NOT_AN_INDEX,
                SuggestedWordInfo.NOT_A_CONFIDENCE
            ))
        }

        // Add reweightedSuggestions, with space replacement logic. It can replace one of the LM
        // suggestions if the top dictionary result has a space, based on heuristics about the
        // relative quality of the LM suggestion
        val spaceReplacementPossible = spaceSplitCandidate == null
                && maxWordDict != null && maxWordDict.word.count { it == ' ' } == 1
        var spaceReplacementPerformed = false
        for(i in 0 until reweightedSuggestions.size) {
            val word = reweightedSuggestions[i]
            if(filtered.contains(word)) continue

            if(!spaceReplacementPerformed && spaceReplacementPossible && (
                        // If the dict score is high enough, allow the space suggestion
                        ((maxWordDict.mScore) > (word.mScore / 3))
                                // Most LM-generated dashed suggestions are distractions, so accept the space suggestion
                                || (word.word.contains('-'))
                                // If the typed word is much longer than the transformer word, just accept the space suggestion
                                || (values.composedData.mTypedWord.length > ceil(word.word.length * 3.0 / 2.0))
                        )
            ) {
                val clone = maxWordDict.scoreAtLeast(word)
                suggestionResults.add(clone)
                spaceReplacementPerformed = true
                continue
            }

            suggestionResults.add(word)
        }

        // Learned user words are ordinary dictionary candidates: they are already suggested by
        // the history dictionary with their own score, so they are not promoted above the
        // language model here. (Before, a relevant history word was cloned above the model
        // whenever the model had no real word of its own.)

        suggestionResults.mRawSuggestions?.addAll(reweightedSuggestions.filter { !filtered.contains(it) })
        if(transformerWeight != Float.POSITIVE_INFINITY) {
            suggestedWordsDictList?.let { words ->
                suggestionResults.addAll(words.filter {
                    it != suggestedWordsDict.typedWordInfo && !filtered.contains(
                        it
                    )
                })
            }
        }

        val settingsValues = settings.current ?: return null
        val locale = dictionaryFacilitator.primaryLocale ?: return null
        val wordComposer = inputLogic.mWordComposer ?: return null

        val suggestedWords = Suggest.obtainNonBatchedInputSuggestedWords(
            wordComposer,
            values.inputStyle,
            settingsValues.mAutoCorrectionEnabledPerUserSettings,
            -1,
            locale,
            suggestionResults,
            settingsValues.mAutoCorrectionThreshold,
            settingsValues.mIsNumberRowEnabled,
            context.getSetting(AutocorrectMaxLengthDeltaSetting),
            dictionaryFacilitator.isValidSpellingWord(values.composedData.mTypedWord)
        )


        // The full candidate dump is expensive to build (it stringifies every LM and dictionary
        // candidate on every keystroke), so it is only produced for debug builds. The file logger
        // records the compact summary below instead.
        if(BuildConfig.DEBUG) {
            val dmpw: (SuggestedWordInfo?) -> String = { v -> v?.let { "${it.mWord}:${it.mScore}:${it.mKindAndFlags}:${it.mSourceDict?.mDictType}" } ?: "[null]" }
            val dmp: (List<SuggestedWordInfo>?) -> String = { v -> v?.joinToString { dmpw(it) } ?: "[null]" }
            Log.d(TAG, "process update suggestion strip:\n" +
                    "raw lm results: ${dmp(lmSuggestions)}\n" +
                    "reweighted lm results: ${dmp(reweightedSuggestions)}\n" +
                    "raw dict results: ${dmp(suggestedWordsDict?.mSuggestedWordInfoList)}}\n" +
                    "filtered dict results: ${dmp(suggestedWordsDictList)}}\n" +
                    "------------\n" +
                    "max word lm: ${dmpw(maxWord)}}\n" +
                    "max word dict: ${dmpw(maxWordDict)}}\n"
            )
        }

        // A cheap per-keystroke summary for the rotating file log: just what the keyboard decided
        // to suggest and why, without the full candidate list.
        if(DebugFileLogger.isEnabled()) {
            val dmpw: (SuggestedWordInfo?) -> String = { v -> v?.let { "${it.mWord}(${it.mScore})" } ?: "[null]" }
            DebugFileLogger.log(TAG +
                    " merge: typed=${values.composedData.mTypedWord}" +
                    " lmTop=${dmpw(maxWord)}" +
                    " dictTop=${dmpw(maxWordDict)}" +
                    " lmSuggestions=${lmSuggestions.size}" +
                    " dictCandidates=${suggestedWordsDictList.size}" +
                    " finalSuggestions=${suggestedWords.mSuggestedWordInfoList.size}")
        }


        return suggestedWords
    }

    public suspend fun destroyModel() {
        if(BuildConfig.DEBUG) Log.d(TAG, "destroyModel called")
        languageModel?.closeInternalLocked()
        languageModel = null
    }

    public fun close() {
        userDictionary.unregister()
    }

    private var trainingEnabled = false

    public fun launchProcessor() = lifecycleScope.launch {
        if(BuildConfig.DEBUG) Log.d(TAG, "Starting processor")
        launch {
            withContext(Dispatchers.Default) {
                TrainingWorkerStatus.lmRequest.collect {
                    if (it == LanguageModelFacilitatorRequest.ResetModel) {
                        if(BuildConfig.DEBUG) Log.d(TAG, "ResetModel event received, destroying model")
                        destroyModel()
                    }else if(it == LanguageModelFacilitatorRequest.ClearTrainingLog) {
                        historyLog.clear()
                        saveHistoryLog()
                    }
                }
            }
        }

        launch {
            withContext(Dispatchers.Default) {
                ModelPaths.modelOptionsUpdated.collect {
                    if(BuildConfig.DEBUG) Log.d(TAG, "ModelPaths options updated, destroying model")
                    skipLanguage = null
                    destroyModel()
                }
            }
        }
    }

    public fun shouldPassThroughToLegacy(): Boolean = when {
        (!settings.current.mTransformerPredictionEnabled) -> true
        (dictionaryFacilitator.primaryLocale.language == skipLanguage) -> true
        (transformerDisabled) -> true
        else -> false
    }

    // This method should return null if transformer is disabled by settings or locale
    var prevAcceptedLayout: Keyboard? = null
    var prevAcceptedLayoutResult = false
    fun makePredictionInputValues(inputStyle: Int): PredictionInputValues? {
        if(shouldPassThroughToLegacy()) return null

        if(inputStyle == SuggestedWords.INPUT_STYLE_TAIL_BATCH
            || inputStyle == SuggestedWords.INPUT_STYLE_UPDATE_BATCH) return null

        val settingsValues = settings.current
        if (!settingsValues.needsToLookupSuggestions()) {
            return null
        }

        if(settingsValues.mInputAttributes.mIsEmailField) return null

        if(!inputLogic.mConnection.isConnected) return null

        try {
            val wordComposer = inputLogic.mWordComposer
            val ngramContext = inputLogic.getNgramContextFromNthPreviousWordForSuggestion(
                settingsValues.mSpacingAndPunctuations,
                2
            )

            if(wordComposer.isComposingWord
                && !context.getSetting(AllowTransformerOnNonQWERTYLayouts)
            ) {
                // Check we are on a supported layout
                if(prevAcceptedLayout != keyboardSwitcher.keyboard) {
                    if(keyboardSwitcher.keyboard?.mId?.mKeyboardLayoutSetName == "qwerty") {
                        prevAcceptedLayoutResult = true
                    } else {
                        val letters = keyboardSwitcher.keyboard?.sortedKeys?.filter {
                            settingsValues.isWordCodePoint(it.code) && it.code != '\''.code
                        }?.sortedBy {
                            it.x + 10000 * it.y
                        }?.joinToString(separator = "") { it.label.lowercase() } ?: ""

                        prevAcceptedLayoutResult = letters == "qwertyuiopasdfghjklzxcvbnm"
                    }
                }

                if(prevAcceptedLayoutResult == false)
                    return null
            }

            val values = PredictionInputValues(
                wordComposer.composedDataSnapshot,
                ngramContext,
                inputStyle,
                ++currentSequenceId
            )

            return values
        } catch(e: Exception) {
            if(e is CancellationException) throw e

            if(BuildConfig.DEBUG) Log.d(TAG, "Failed to get context, composed data snapshot, etc: $e")
            e.printStackTrace()
        }

        return null
    }

    private val historyLog: MutableList<HistoryLogForTraining> = mutableListOf()

    public fun addToHistory(
        word: String,
        wasAutoCapitalized: Boolean,
        ngramContext: NgramContext,
        timeStampInSeconds: Long,
        blockPotentiallyOffensive: Boolean,
        importance: Int
    ) {
        if(shouldPassThroughToLegacy()) return
        if(!trainingEnabled) return
        if(settings.current?.mInputAttributes?.mNoLearning != false) return

        if(dictionaryFacilitator.mostConfidentLocale != languageModel?.locale) return

        val wordCtx = ngramContext.fullContext.trim().lines().last()
        var committedNgramCtx = ngramContext.extractPrevWordsContext().replace(NgramContext.BEGINNING_OF_SENTENCE_TAG, " ").trim();
        if(committedNgramCtx.isEmpty()) {
            committedNgramCtx = " "
        }
        
        val lastIdx = wordCtx.lastIndexOf(committedNgramCtx)
        if(lastIdx == -1) {
            //println("addToHistory: extraction failed, couldn't find ngram ctx in full ctx")
            return
        }

        val misspelledWord = wordCtx.substring(
            lastIdx + committedNgramCtx.length
        )
        if(misspelledWord.isNotBlank() && (!(misspelledWord.startsWith(" ") || committedNgramCtx == " ") || misspelledWord.endsWith(" ") || misspelledWord.trim().contains(" "))) {
            //println("addToHistory: extraction failed bad context. wordCtx=[$wordCtx]  --   committedNgramCtx=[$committedNgramCtx]  --  word=[$word]  --  fullNgram=[$ngramContext]")
            return
        }

        val ctxBeforeMisspelledWord = wordCtx.dropLast(misspelledWord.length)

        val key = committedNgramCtx.trim() + " " + word.trim()
        val logToAdd = if(misspelledWord.isNotBlank()) {
            // Correcting (ctx) misspelled -> word
            HistoryLogForTraining(
                key,
                ctxBeforeMisspelledWord,
                committedNgramCtx,
                misspelledWord.trim(),
                word,
                importance,
                dictionaryFacilitator.primaryLocale.language,
                timeStampInSeconds
            )
        } else {
            // Predicted (ctx) -> word
            HistoryLogForTraining(
                key,
                ctxBeforeMisspelledWord,
                committedNgramCtx,
                null,
                word,
                importance,
                dictionaryFacilitator.primaryLocale.language,
                timeStampInSeconds
            )
        }

        historyLog.add(logToAdd)
        //println("addToHistory: Adding $logToAdd")
    }

    public fun unlearnFromHistory(
        word: String,
        ngramContext: NgramContext,
        timeStampInSeconds: Long,
        eventType: Int
    ) {
        if(shouldPassThroughToLegacy()) return
        if(!trainingEnabled) return

        val wordCtx = ngramContext.fullContext.trim().lines().last()
        var committedNgramCtx = ngramContext.extractPrevWordsContext().replace(NgramContext.BEGINNING_OF_SENTENCE_TAG, " ").trim();
        if(committedNgramCtx.isEmpty()) {
            committedNgramCtx = " "
        }
        
        val keyToSearch = committedNgramCtx.trim() + " " + word.trim()

        val logToRemove = historyLog.indexOfLast {
            it.key.startsWith(keyToSearch) || it.key == keyToSearch
        }

        if(logToRemove == -1) {
            //println("addToHistory: UNLEARN Couldn't find key $keyToSearch")
        } else {
            //println("addToHistory: Unlearning ${historyLog[logToRemove]}")
            historyLog.removeAt(logToRemove)
        }
    }

    public fun saveHistoryLog() {
        if(!context.getSetting(USE_TRANSFORMER_FINETUNING)) historyLog.clear()
        saveHistoryLogBackup(context, historyLog)
    }

    public fun loadHistoryLog() {
        assert(historyLog.isEmpty())
        loadHistoryLogBackup(context, historyLog)
    }

    public fun onStartInput() {
        transformerDisabled = false
        numConsecutiveTimeouts = 0
    }

    public fun isTransformerDisabled(): Boolean = transformerDisabled
}