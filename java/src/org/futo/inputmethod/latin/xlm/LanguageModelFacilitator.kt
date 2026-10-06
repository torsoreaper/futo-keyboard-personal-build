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
        if (dictionaryFacilitator.isValidSpellingWord(typed)) return null
        val lowerTyped = typed.lowercase()
        val modelDistance = modelTop?.let { levenshteinDistance(lowerTyped, it.mWord.lowercase()) }
        return dictionaryCandidates
            .asSequence()
            .filter {
                val type = it.mSourceDict?.mDictType
                type == Dictionary.TYPE_USER || type == Dictionary.TYPE_USER_HISTORY
            }
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

        // A negative dictionary score means the dictionary had no real opinion: it is only
        // suggesting that word because it ran out of plausible candidates. That is not the same
        // as disagreeing with the language model, so when the transformer is confident about a
        // word we should keep it rather than discarding it for lack of a second opinion. Without
        // this, typos the dictionary cannot help with (e.g. "gjthun" -> "GitHub") get suggested
        // but never commit on space.
        val dictionaryHasNoOpinion = maxWordDict == null || maxWordDict.mScore < 0
        val transformerIsConfident = maxWord != null && maxWord.mScore >= MIN_TRANSFORMER_AUTOCORRECT_SCORE

        var autocorrectWord: SuggestedWordInfo? = null
        val filtered = mutableListOf<SuggestedWordInfo>()
        if (userWordCandidate != null && maxWord != null) {
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