/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.futo.inputmethod.latin;

import android.text.TextUtils;
import android.util.Log;

import static org.futo.inputmethod.latin.define.DecoderSpecificConstants.SHOULD_AUTO_CORRECT_USING_NON_WHITE_LISTED_SUGGESTION;
import static org.futo.inputmethod.latin.define.DecoderSpecificConstants.SHOULD_REMOVE_PREVIOUSLY_REJECTED_SUGGESTION;

import org.futo.inputmethod.keyboard.Keyboard;
import org.futo.inputmethod.latin.SuggestedWords.SuggestedWordInfo;
import org.futo.inputmethod.latin.common.Constants;
import org.futo.inputmethod.latin.common.StringUtils;
import org.futo.inputmethod.latin.define.DebugFlags;
import org.futo.inputmethod.latin.settings.SettingsValuesForSuggestion;
import org.futo.inputmethod.latin.utils.AutoCorrectionUtils;
import org.futo.inputmethod.latin.utils.BinaryDictionaryUtils;
import org.futo.inputmethod.latin.utils.DebugFileLogger;
import org.futo.inputmethod.latin.utils.SuggestionResults;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nonnull;

/**
 * This class loads a dictionary and provides a list of suggestions for a given sequence of
 * characters. This includes corrections and completions.
 */
public final class Suggest {
    public static final String TAG = Suggest.class.getSimpleName();

    // Session id for
    // {@link #getSuggestedWords(WordComposer,String,ProximityInfo,boolean,int)}.
    // We are sharing the same ID between typing and gesture to save RAM footprint.
    public static final int SESSION_ID_TYPING = 0;
    public static final int SESSION_ID_GESTURE = 0;

    // Close to -2**31
    public static final int SUPPRESS_SUGGEST_THRESHOLD = -2000000000;

    // Default maximum difference in length between the typed word and a suggestion for it to be
    // eligible for auto-correction. Used by code paths that don't have access to the setting.
    public static final int DEFAULT_MAX_AUTO_CORRECTION_LENGTH_DELTA = 2;

    // Maximum edit distance for the typo bypass below. A larger distance means the top candidate
    // is probably a different word, not a typo of what was typed.
    private static final int MAX_TYPO_EDIT_DISTANCE = 2;

    private static final boolean DBG = DebugFlags.DEBUG_ENABLED;
    private final DictionaryFacilitator mDictionaryFacilitator;

    private static final int MAXIMUM_AUTO_CORRECT_LENGTH_FOR_GERMAN = 12;
    private static final HashMap<String, Integer> sLanguageToMaximumAutoCorrectionWithSpaceLength =
            new HashMap<>();
    static {
        // TODO: should we add Finnish here?
        // TODO: This should not be hardcoded here but be written in the dictionary header
        sLanguageToMaximumAutoCorrectionWithSpaceLength.put(Locale.GERMAN.getLanguage(),
                MAXIMUM_AUTO_CORRECT_LENGTH_FOR_GERMAN);
    }

    private float mAutoCorrectionThreshold;
    private float mPlausibilityThreshold;

    public Suggest(final DictionaryFacilitator dictionaryFacilitator) {
        mDictionaryFacilitator = dictionaryFacilitator;
    }

    /**
     * Set the normalized-score threshold for a suggestion to be considered strong enough that we
     * will auto-correct to this.
     * @param threshold the threshold
     */
    public void setAutoCorrectionThreshold(final float threshold) {
        mAutoCorrectionThreshold = threshold;
    }

    /**
     * Set the normalized-score threshold for what we consider a "plausible" suggestion, in
     * the same dimension as the auto-correction threshold.
     * @param threshold the threshold
     */
    public void setPlausibilityThreshold(final float threshold) {
        mPlausibilityThreshold = threshold;
    }

    public interface OnGetSuggestedWordsCallback {
        public void onGetSuggestedWords(final SuggestedWords suggestedWords);
    }

    public void getSuggestedWords(final WordComposer wordComposer,
            final NgramContext ngramContext, final Keyboard keyboard,
            final SettingsValuesForSuggestion settingsValuesForSuggestion,
            final boolean isCorrectionEnabled, final int inputStyle, final int sequenceNumber,
            final OnGetSuggestedWordsCallback callback) {
        if (wordComposer.isBatchMode()) {
            getSuggestedWordsForBatchInput(wordComposer, ngramContext, keyboard,
                    settingsValuesForSuggestion, inputStyle, sequenceNumber, callback);
        } else {
            getSuggestedWordsForNonBatchInput(wordComposer, ngramContext, keyboard,
                    settingsValuesForSuggestion, inputStyle, isCorrectionEnabled,
                    sequenceNumber, callback);
        }
    }

    private static ArrayList<SuggestedWordInfo> getTransformedSuggestedWordInfoList(
            final WordComposer wordComposer, final SuggestionResults results,
            final int trailingSingleQuotesCount, final Locale defaultLocale) {
        final boolean shouldMakeSuggestionsAllUpperCase = wordComposer.isAllUpperCase()
                && !wordComposer.isResumed();
        final boolean isOnlyFirstCharCapitalized =
                wordComposer.isOrWillBeOnlyFirstCharCapitalized();

        final ArrayList<SuggestedWordInfo> suggestionsContainer = new ArrayList<>(results);
        final int suggestionsCount = suggestionsContainer.size();
        if (isOnlyFirstCharCapitalized || shouldMakeSuggestionsAllUpperCase
                || 0 != trailingSingleQuotesCount) {
            for (int i = 0; i < suggestionsCount; ++i) {
                final SuggestedWordInfo wordInfo = suggestionsContainer.get(i);
                final Locale wordLocale = (wordInfo.mSourceDict != null) ? wordInfo.mSourceDict.mLocale : null;
                final SuggestedWordInfo transformedWordInfo = getTransformedSuggestedWordInfo(
                        wordInfo, null == wordLocale ? defaultLocale : wordLocale,
                        shouldMakeSuggestionsAllUpperCase, isOnlyFirstCharCapitalized,
                        trailingSingleQuotesCount);
                suggestionsContainer.set(i, transformedWordInfo);
            }
        }
        return suggestionsContainer;
    }

    private static SuggestedWordInfo getWhitelistedWordInfoOrNull(
            @Nonnull final ArrayList<SuggestedWordInfo> suggestions) {
        if (suggestions.isEmpty()) {
            return null;
        }
        final SuggestedWordInfo firstSuggestedWordInfo = suggestions.get(0);
        if (!firstSuggestedWordInfo.isKindOf(SuggestedWordInfo.KIND_WHITELIST)) {
            return null;
        }
        return firstSuggestedWordInfo;
    }

    public Set<Integer> getValidNextCodePoints(final WordComposer wordComposer) {
        final ArrayList<Integer> nextCodePoints = mDictionaryFacilitator.getValidNextCodePoints(
                wordComposer.getComposedDataSnapshot()
        );

        return new HashSet<>(nextCodePoints);
    }

    public static SuggestedWords obtainNonBatchedInputSuggestedWords(
            final WordComposer wordComposer, final int inputStyleIfNotPrediction,
            final boolean isCorrectionEnabled, final int sequenceNumber,
            final Locale locale, final SuggestionResults suggestionResults,
            final float autoCorrectionThreshold, final boolean numberRowActive,
            final int maxAutoCorrectionLengthDelta, final boolean isTypedWordKnown
    ) {
        final String typedWordString = wordComposer.getTypedWord();
        final int trailingSingleQuotesCount =
                StringUtils.getTrailingSingleQuotesCount(typedWordString);
        final String consideredWord = trailingSingleQuotesCount > 0
                ? typedWordString.substring(0, typedWordString.length() - trailingSingleQuotesCount)
                : typedWordString;

        final ArrayList<SuggestedWordInfo> suggestionsContainer =
                getTransformedSuggestedWordInfoList(wordComposer, suggestionResults,
                        trailingSingleQuotesCount, locale);

        boolean foundInDictionary = false;
        Dictionary sourceDictionaryOfRemovedWord = null;
        for (final SuggestedWordInfo info : suggestionsContainer) {
            // Search for the best dictionary, defined as the first one with the highest match
            // quality we can find.
            if (!foundInDictionary && typedWordString.equals(info.mWord)) {
                // Use this source if the old match had lower quality than this match
                sourceDictionaryOfRemovedWord = info.mSourceDict;
                foundInDictionary = true;
                break;
            }
        }

        final int firstOcurrenceOfTypedWordInSuggestions =
                SuggestedWordInfo.removeDups(typedWordString, suggestionsContainer);

        final SuggestedWordInfo whitelistedWordInfo =
                getWhitelistedWordInfoOrNull(suggestionsContainer);
        final String whitelistedWord = whitelistedWordInfo == null
                ? null : whitelistedWordInfo.mWord;
        final boolean resultsArePredictions = !wordComposer.isComposingWord();

        // We allow auto-correction if whitelisting is not required or the word is whitelisted,
        // or if the word had more than one char and was not suggested.
        final boolean allowsToBeAutoCorrected =
                (SHOULD_AUTO_CORRECT_USING_NON_WHITE_LISTED_SUGGESTION || whitelistedWord != null)
                || (consideredWord.length() > 1 && (sourceDictionaryOfRemovedWord == null));

        // A token with digits can still be auto-corrected when the digits were a mistap of the
        // top-row letters (e.g. "hell9" -> "hello"); the caller promotes such a suggestion and
        // this recognises it so the digit guards below don't reject it.
        final SuggestedWordInfo firstSuggestionForGuards =
                suggestionResults.isEmpty() ? null : suggestionResults.first();
        final boolean digitToLetterCorrection = firstSuggestionForGuards != null
                && isDigitToLetterCorrection(consideredWord, firstSuggestionForGuards.mWord);

        final boolean hasAutoCorrection;
        // If correction is not enabled, we never auto-correct. This is for example for when
        // the setting "Auto-correction" is "off": we still suggest, but we don't auto-correct.
        if (!isCorrectionEnabled
                // If the word does not allow to be auto-corrected, then we don't auto-correct.
                || !allowsToBeAutoCorrected
                // If we are doing prediction, then we never auto-correct of course
                || resultsArePredictions
                // If we don't have suggestion results, we can't evaluate the first suggestion
                // for auto-correction
                || suggestionResults.isEmpty()
                // If the word has dashes, we never auto-correct because it had to have been
                // typed with a lot of care
                || wordComposer.hasDashes()
                // If the word has digits, we never auto-correct because it's likely the word
                // was type with a lot of care, unless number row is active or the digits are a
                // mistap of the top-row letters
                || (wordComposer.hasDigits() && !numberRowActive && !digitToLetterCorrection)
                // If the word is entirely digits, never autocorrect regardless of number row
                // being active or not
                || wordComposer.isEntirelyDigits()
                // If it's an ordinal (1st, 2nd, 3rd), do not autocorrect
                //|| wordComposer.isOrdinal(locale)
                // If it starts with a digit, do not autocorrect, unless it's a digit->letter fix
                || (wordComposer.startsWithDigit() && !digitToLetterCorrection)
                // If it's attached to a non word, do not autocorrect
                || wordComposer.isAttachedToNonWord()
                // If the word is mostly caps, we never auto-correct because this is almost
                // certainly intentional (and careful input)
                || wordComposer.isMostlyCaps()
                // We never auto-correct when suggestions are resumed because it would be unexpected
                || wordComposer.isResumed()
                // If the user just rejected a swipe input, it's likely they're typing a word that
                // isn't in the dictionary because swiping isn't working, so skip autocorrect for
                // this case.
                || wordComposer.getRejectedBatchModeSuggestion() != null
                // If we don't have a main dictionary, we never want to auto-correct. The reason
                // for this is, the user may have a contact whose name happens to match a valid
                // word in their language, and it will unexpectedly auto-correct. For example, if
                // the user types in English with no dictionary and has a "Will" in their contact
                // list, "will" would always auto-correct to "Will" which is unwanted. Hence, no
                // main dict => no auto-correct. Also, it would probably get obnoxious quickly.
                // TODO: now that we have personalization, we may want to re-evaluate this decision
                //|| !mDictionaryFacilitator.hasAtLeastOneInitializedMainDictionary()
                // If the first suggestion is a shortcut we never auto-correct to it, regardless
                // of how strong it is (allowlist entries are not KIND_SHORTCUT but KIND_WHITELIST).
                // TODO: we may want to have shortcut-only entries auto-correct in the future.
                || suggestionResults.first().isKindOf(SuggestedWordInfo.KIND_SHORTCUT)
                // Don't do it if it looks like a URL (or email address)
                || StringUtils.lastPartLooksLikeURL(typedWordString)) {
            hasAutoCorrection = false;
        } else {
            final SuggestedWordInfo firstSuggestion = suggestionResults.first();
            // Only consider a correction if the top candidate is a plausible typo of what was
            // typed, i.e. within the configured length tolerance.
            final boolean lengthInTolerance = Math.abs(
                    firstSuggestion.mWord.length() - consideredWord.length())
                    <= maxAutoCorrectionLengthDelta;
            if (!lengthInTolerance) {
                hasAutoCorrection = false;
            } else if (firstOcurrenceOfTypedWordInSuggestions == 0) {
                // The typed word itself was the top suggestion. It was removed from the
                // candidate list above (it is re-added as KIND_TYPED), so the runner-up now
                // sits in the auto-correction slot. Do not commit the runner-up; keep the
                // typed word.
                hasAutoCorrection = false;
            } else if (!isTypedWordKnown
                    && !firstSuggestion.mWord.equalsIgnoreCase(consideredWord)
                    && levenshteinDistance(firstSuggestion.mWord, consideredWord)
                            <= MAX_TYPO_EDIT_DISTANCE) {
                // The typed word isn't a known word and the top candidate is a close match,
                // so this is a typo: correct it without requiring full confidence.
                hasAutoCorrection = isAllowedByAutoCorrectionWithSpaceFilter(
                        consideredWord, firstSuggestion);
            } else if (!AutoCorrectionUtils.suggestionExceedsThreshold(
                    firstSuggestion, consideredWord, autoCorrectionThreshold)) {
                // Score is too low for autocorrect
                hasAutoCorrection = false;
            } else {
                // We have a high score, so we need to check if this suggestion is in the correct
                // form to allow auto-correcting to it in this language. For details of how this
                // is determined, see #isAllowedByAutoCorrectionWithSpaceFilter.
                // TODO: this should not have its own logic here but be handled by the dictionary.
                hasAutoCorrection = isAllowedByAutoCorrectionWithSpaceFilter(
                        consideredWord, firstSuggestion);
            }
        }

        if (DBG && !suggestionResults.isEmpty()) {
            final SuggestedWordInfo decisionTop = suggestionResults.first();
            Log.d(TAG, "autocorrect decision: hasAutoCorrection=" + hasAutoCorrection
                    + " typed=" + typedWordString
                    + " considered=" + consideredWord
                    + " top=" + decisionTop.mWord
                    + " score=" + decisionTop.mScore
                    + " kind=" + decisionTop.mKindAndFlags
                    + " threshold=" + autoCorrectionThreshold
                    + " maxDelta=" + maxAutoCorrectionLengthDelta
                    + " typedKnown=" + isTypedWordKnown
                    + " typedIndex=" + firstOcurrenceOfTypedWordInSuggestions
                    + " allows=" + allowsToBeAutoCorrected
                    + " whitelist=" + whitelistedWord
                    + " srcDictRemoved=" + (sourceDictionaryOfRemovedWord != null)
                    + " [guard: corrEnabled=" + isCorrectionEnabled
                    + " isPred=" + resultsArePredictions
                    + " dashes=" + wordComposer.hasDashes()
                    + " digits=" + (wordComposer.hasDigits() && !numberRowActive)
                    + " allDigits=" + wordComposer.isEntirelyDigits()
                    + " startsDigit=" + wordComposer.startsWithDigit()
                    + " attachedNonWord=" + wordComposer.isAttachedToNonWord()
                    + " mostlyCaps=" + wordComposer.isMostlyCaps()
                    + " resumed=" + wordComposer.isResumed()
                    + " rejectedSwipe=" + (wordComposer.getRejectedBatchModeSuggestion() != null)
                    + " shortcut=" + suggestionResults.first().isKindOf(SuggestedWordInfo.KIND_SHORTCUT)
                    + " url=" + StringUtils.lastPartLooksLikeURL(typedWordString) + "]");
        } else if (DebugFileLogger.INSTANCE.isEnabled() && !suggestionResults.isEmpty()) {
            // Compact per-keystroke record for the rotating file log: what was typed, what the
            // keyboard decided to suggest, and whether it will be auto-corrected on space.
            final SuggestedWordInfo decisionTop = suggestionResults.first();
            DebugFileLogger.INSTANCE.log(TAG
                    + " typed=" + typedWordString
                    + " top=" + decisionTop.mWord
                    + " score=" + decisionTop.mScore
                    + " autoCorrect=" + hasAutoCorrection);
        }

        final SuggestedWordInfo typedWordInfo = new SuggestedWordInfo(typedWordString,
                "" /* prevWordsContext */, SuggestedWordInfo.MAX_SCORE,
                SuggestedWordInfo.KIND_TYPED,
                null == sourceDictionaryOfRemovedWord ? Dictionary.DICTIONARY_USER_TYPED
                        : sourceDictionaryOfRemovedWord,
                SuggestedWordInfo.NOT_AN_INDEX /* indexOfTouchPointOfSecondWord */,
                SuggestedWordInfo.NOT_A_CONFIDENCE /* autoCommitFirstWordConfidence */);
        if (!TextUtils.isEmpty(typedWordString)) {
            suggestionsContainer.add(0, typedWordInfo);
        }

        final ArrayList<SuggestedWordInfo> suggestionsList;
        if (DBG && !suggestionsContainer.isEmpty()) {
            suggestionsList = getSuggestionsInfoListWithDebugInfo(typedWordString,
                    suggestionsContainer);
        } else {
            suggestionsList = suggestionsContainer;
        }

        final int inputStyle;
        if (resultsArePredictions) {
            inputStyle = suggestionResults.mIsBeginningOfSentence
                    ? SuggestedWords.INPUT_STYLE_BEGINNING_OF_SENTENCE_PREDICTION
                    : SuggestedWords.INPUT_STYLE_PREDICTION;
        } else {
            inputStyle = inputStyleIfNotPrediction;
        }

        final boolean isTypedWordValid = firstOcurrenceOfTypedWordInSuggestions > -1
                || (!resultsArePredictions && !allowsToBeAutoCorrected);
        return new SuggestedWords(suggestionsList,
                suggestionResults.mRawSuggestions, typedWordInfo,
                isTypedWordValid,
                hasAutoCorrection /* willAutoCorrect */,
                false /* isObsoleteSuggestions */, inputStyle, sequenceNumber);
    }

    // Returns whether the provided codepoint should trigger an autocorrection
    public static boolean shouldCodePointAutocorrect(final int codePoint) {
        // Dash will never autocorrect because the autocorrect currently doesn't take the dash
        // suffix into account, this leads to it miscorrecting prefixes like "un-" to "in-"
        // because in most cases, "un" is a typo of "in"
        return codePoint != '-' &&
                // Email addresses should not be corrected
                codePoint != '@' &&
                // Code, etc
                codePoint != '`' && codePoint != '_';
    }


    // Retrieves suggestions for non-batch input (typing, recorrection, predictions...)
    // and calls the callback function with the suggestions.
    private void getSuggestedWordsForNonBatchInput(final WordComposer wordComposer,
            final NgramContext ngramContext, final Keyboard keyboard,
            final SettingsValuesForSuggestion settingsValuesForSuggestion,
            final int inputStyleIfNotPrediction, final boolean isCorrectionEnabled,
            final int sequenceNumber, final OnGetSuggestedWordsCallback callback) {
        final SuggestionResults suggestionResults = mDictionaryFacilitator.getSuggestionResults(
                wordComposer.getComposedDataSnapshot(), ngramContext, keyboard,
                settingsValuesForSuggestion, SESSION_ID_TYPING, inputStyleIfNotPrediction);
        final Locale locale = mDictionaryFacilitator.getPrimaryLocale();

        // If the typed token contains a digit that is really a top-row letter mistap ("hell9"),
        // promote the corrected word so it can be committed on space.
        final String digitToLetterFix =
                findDigitLetterCorrection(mDictionaryFacilitator, wordComposer.getTypedWord());
        if (digitToLetterFix != null) {
            suggestionResults.add(new SuggestedWordInfo(digitToLetterFix, "",
                    SuggestedWordInfo.MAX_SCORE,
                    SuggestedWordInfo.KIND_FLAG_APPROPRIATE_FOR_AUTO_CORRECTION
                            | SuggestedWordInfo.KIND_CORRECTION,
                    null, SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE));
        }

        callback.onGetSuggestedWords(
            obtainNonBatchedInputSuggestedWords(wordComposer, inputStyleIfNotPrediction,
                isCorrectionEnabled, sequenceNumber, locale, suggestionResults, mAutoCorrectionThreshold,
                    keyboard.mId.mNumberRow, DEFAULT_MAX_AUTO_CORRECTION_LENGTH_DELTA,
                    mDictionaryFacilitator.isValidSpellingWord(wordComposer.getTypedWord()))
        );
    }

    // Retrieves suggestions for the batch input
    // and calls the callback function with the suggestions.
    private void getSuggestedWordsForBatchInput(final WordComposer wordComposer,
            final NgramContext ngramContext, final Keyboard keyboard,
            final SettingsValuesForSuggestion settingsValuesForSuggestion,
            final int inputStyle, final int sequenceNumber,
            final OnGetSuggestedWordsCallback callback) {
        final SuggestionResults suggestionResults = mDictionaryFacilitator.getSuggestionResults(
                wordComposer.getComposedDataSnapshot(), ngramContext, keyboard,
                settingsValuesForSuggestion, SESSION_ID_GESTURE, inputStyle);
        // For transforming words that don't come from a dictionary, because it's our best bet
        final Locale locale = mDictionaryFacilitator.getPrimaryLocale();
        final ArrayList<SuggestedWordInfo> suggestionsContainer =
                new ArrayList<>(suggestionResults);
        final int suggestionsCount = suggestionsContainer.size();
        final boolean isFirstCharCapitalized = wordComposer.wasShiftedNoLock();
        final boolean isAllUpperCase = wordComposer.isAllUpperCase();
        if (isFirstCharCapitalized || isAllUpperCase) {
            for (int i = 0; i < suggestionsCount; ++i) {
                final SuggestedWordInfo wordInfo = suggestionsContainer.get(i);
                final Locale wordlocale = (wordInfo.mSourceDict != null) ? wordInfo.mSourceDict.mLocale : null;
                final SuggestedWordInfo transformedWordInfo = getTransformedSuggestedWordInfo(
                        wordInfo, null == wordlocale ? locale : wordlocale, isAllUpperCase,
                        isFirstCharCapitalized, 0 /* trailingSingleQuotesCount */);
                suggestionsContainer.set(i, transformedWordInfo);
            }
        }

        if (SHOULD_REMOVE_PREVIOUSLY_REJECTED_SUGGESTION
                && suggestionsContainer.size() > 1
                && TextUtils.equals(suggestionsContainer.get(0).mWord,
                   wordComposer.getRejectedBatchModeSuggestion())) {
            final SuggestedWordInfo rejected = suggestionsContainer.remove(0);
            Log.i(TAG, "Swipe: Swapping top candidate [ " + rejected.mWord + " ] as it was previously rejected by user");
            suggestionsContainer.add(1, rejected);
        }
        SuggestedWordInfo.removeDups(null /* typedWord */, suggestionsContainer);

        // For some reason some suggestions with MIN_VALUE are making their way here.
        // TODO: Find a more robust way to detect distracters.
        for (int i = suggestionsContainer.size() - 1; i >= 0; --i) {
            if (suggestionsContainer.get(i).mScore < SUPPRESS_SUGGEST_THRESHOLD) {
                suggestionsContainer.remove(i);
            }
        }

        // In the batch input mode, the most relevant suggested word should act as a "typed word"
        // (typedWordValid=true), not as an "auto correct word" (willAutoCorrect=false).
        // Note that because this method is never used to get predictions, there is no need to
        // modify inputType such in getSuggestedWordsForNonBatchInput.
        final SuggestedWordInfo pseudoTypedWordInfo = suggestionsContainer.isEmpty() ? null
                : suggestionsContainer.get(0);

        callback.onGetSuggestedWords(new SuggestedWords(suggestionsContainer,
                suggestionResults.mRawSuggestions,
                pseudoTypedWordInfo,
                true /* typedWordValid */,
                false /* willAutoCorrect */,
                false /* isObsoleteSuggestions */,
                inputStyle, sequenceNumber));
    }

    private static ArrayList<SuggestedWordInfo> getSuggestionsInfoListWithDebugInfo(
            final String typedWord, final ArrayList<SuggestedWordInfo> suggestions) {
        final SuggestedWordInfo typedWordInfo = suggestions.get(0);
        typedWordInfo.setDebugString("+");
        final int suggestionsSize = suggestions.size();
        final ArrayList<SuggestedWordInfo> suggestionsList = new ArrayList<>(suggestionsSize);
        suggestionsList.add(typedWordInfo);
        // Note: i here is the index in mScores[], but the index in mSuggestions is one more
        // than i because we added the typed word to mSuggestions without touching mScores.
        for (int i = 0; i < suggestionsSize - 1; ++i) {
            final SuggestedWordInfo cur = suggestions.get(i + 1);
            final float normalizedScore = BinaryDictionaryUtils.calcNormalizedScore(
                    typedWord, cur.toString(), cur.mScore);
            final String scoreInfoString;
            if (normalizedScore > 0) {
                scoreInfoString = String.format(
                        Locale.ROOT, "%d (%4.2f), %s", cur.mScore, normalizedScore,
                         cur.mSourceDict == null ? "null" : cur.mSourceDict.mDictType);
            } else {
                scoreInfoString = Integer.toString(cur.mScore);
            }
            cur.setDebugString(scoreInfoString);
            suggestionsList.add(cur);
        }
        return suggestionsList;
    }

    /**
     * Computes whether this suggestion should be blocked or not in this language
     *
     * This function implements a filter that avoids auto-correcting to suggestions that contain
     * spaces that are above a certain language-dependent character limit. In languages like German
     * where it's possible to concatenate many words, it often happens our dictionary does not
     * have the longer words. In this case, we offer a lot of unhelpful suggestions that contain
     * one or several spaces. Ideally we should understand what the user wants and display useful
     * suggestions by improving the dictionary and possibly having some specific logic. Until
     * that's possible we should avoid displaying unhelpful suggestions. But it's hard to tell
     * whether a suggestion is useful or not. So at least for the time being we block
     * auto-correction when the suggestion is long and contains a space, which should avoid the
     * worst damage.
     * This function is implementing that filter. If the language enforces no such limit, then it
     * always returns true. If the suggestion contains no space, it also returns true. Otherwise,
     * it checks the length against the language-specific limit.
     *
     * @param info the suggestion info
     * @return whether it's fine to auto-correct to this.
     */
    private static int levenshteinDistance(final String s1, final String s2) {
        final int len1 = s1.length();
        final int len2 = s2.length();
        final int[] prev = new int[len2 + 1];
        final int[] curr = new int[len2 + 1];
        for (int j = 0; j <= len2; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= len1; i++) {
            curr[0] = i;
            for (int j = 1; j <= len2; j++) {
                final int cost = (s1.charAt(i - 1) == s2.charAt(j - 1)) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            System.arraycopy(curr, 0, prev, 0, len2 + 1);
        }
        return prev[len2];
    }

    // Bottom-row letters that can stand in for the space bar when a run-together token is split
    // into two words. c/v/b/n are directly above the space bar (strong); z/x/m are the outer keys.
    private static final String SPACE_SPLIT_STRONG_KEYS = "cvbn";
    private static final String SPACE_SPLIT_WEAK_KEYS = "zxm";

    // The number row sits directly above the top letter row, so a digit can be a mistap of the
    // letter below it: 1->q, 2->w, ... 9->o, 0->p.
    private static final String NUMBER_ROW_DIGITS = "1234567890";
    private static final String NUMBER_ROW_LETTERS = "qwertyuiop";

    private static char[] digitLetterCandidates(final char digit) {
        final int index = NUMBER_ROW_DIGITS.indexOf(digit);
        if (index < 0) {
            return null;
        }
        // The letter directly below the digit, plus its horizontal neighbours on the top row,
        // because a thumb can land slightly to either side.
        final StringBuilder candidates = new StringBuilder(3);
        candidates.append(NUMBER_ROW_LETTERS.charAt(index));
        if (index > 0) {
            candidates.append(NUMBER_ROW_LETTERS.charAt(index - 1));
        }
        if (index < NUMBER_ROW_LETTERS.length() - 1) {
            candidates.append(NUMBER_ROW_LETTERS.charAt(index + 1));
        }
        return candidates.toString().toCharArray();
    }

    /**
     * Whether {@code suggestion} is the typed word with each digit replaced by a plausible
     * top-row letter (used to let the digit guards know a digit->letter fix is being offered).
     */
    private static boolean isDigitToLetterCorrection(final String typed,
            final String suggestion) {
        if (typed == null || suggestion == null || typed.length() != suggestion.length()) {
            return false;
        }
        boolean hasDigit = false;
        for (int i = 0; i < typed.length(); i++) {
            final char typedChar = typed.charAt(i);
            final char suggestionChar = suggestion.charAt(i);
            if (Character.isDigit(typedChar)) {
                hasDigit = true;
                final char[] candidates = digitLetterCandidates(typedChar);
                if (candidates == null) {
                    return false;
                }
                boolean matches = false;
                for (final char candidate : candidates) {
                    if (Character.toLowerCase(suggestionChar) == candidate) {
                        matches = true;
                        break;
                    }
                }
                if (!matches) {
                    return false;
                }
            } else if (Character.toLowerCase(typedChar) != Character.toLowerCase(suggestionChar)) {
                return false;
            }
        }
        return hasDigit;
    }

    /**
     * Finds a real word made by replacing the digits in a token with the top-row letters below
     * them (e.g. "hell9" -> "hello"), or null when there is no such word. Only positions that
     * hold a digit are changed; the token must also contain at least one letter, so pure numbers
     * are never touched.
     */
    public static String findDigitLetterCorrection(final DictionaryFacilitator dictionaryFacilitator,
            final String rawTypedWord) {
        if (dictionaryFacilitator == null || rawTypedWord == null) {
            return null;
        }
        final int length = rawTypedWord.length();
        if (length < 2 || length > 24) {
            return null;
        }
        boolean hasDigit = false;
        boolean hasLetter = false;
        for (int i = 0; i < length; i++) {
            if (Character.isDigit(rawTypedWord.charAt(i))) {
                hasDigit = true;
            } else if (Character.isLetter(rawTypedWord.charAt(i))) {
                hasLetter = true;
            }
        }
        if (!hasDigit || !hasLetter) {
            return null;
        }
        final boolean capitalize = Character.isUpperCase(rawTypedWord.charAt(0));
        final String typed = rawTypedWord.toLowerCase(Locale.ROOT);
        final String found = searchDigitLetterCorrection(dictionaryFacilitator, typed.toCharArray(), 0);
        if (found == null) {
            return null;
        }
        return capitalize ? Character.toUpperCase(found.charAt(0)) + found.substring(1) : found;
    }

    private static String searchDigitLetterCorrection(final DictionaryFacilitator dictionaryFacilitator,
            final char[] buffer, final int position) {
        if (position >= buffer.length) {
            final String candidate = new String(buffer);
            return dictionaryFacilitator.isValidSpellingWord(candidate) ? candidate : null;
        }
        final char current = buffer[position];
        if (Character.isDigit(current)) {
            final char[] candidates = digitLetterCandidates(current);
            for (final char letter : candidates) {
                buffer[position] = letter;
                final String result = searchDigitLetterCorrection(dictionaryFacilitator, buffer,
                        position + 1);
                if (result != null) {
                    buffer[position] = current;
                    return result;
                }
            }
            buffer[position] = current;
            return null;
        }
        return searchDigitLetterCorrection(dictionaryFacilitator, buffer, position + 1);
    }

    private static boolean isAllowedByAutoCorrectionWithSpaceFilter(
            final String typedWord, final SuggestedWordInfo info) {
        // If the suggestion contains a space, only allow it when that space replaced one of the
        // bottom-row keys (e.g. "laxybdogz" -> "lazy dogs", with the space standing in for 'b').
        // This prevents committing odd splits at unrelated keys (e.g. "helloyhere" -> "helot here").
        if (info.mWord.indexOf(Constants.CODE_SPACE) >= 0) {
            final int secondWordInputIndex = info.mIndexOfTouchPointOfSecondWord;
            if (secondWordInputIndex <= 0 || secondWordInputIndex > typedWord.length()) {
                return false;
            }
            final char splitKey = Character.toLowerCase(typedWord.charAt(secondWordInputIndex - 1));
            if (SPACE_SPLIT_STRONG_KEYS.indexOf(splitKey) < 0
                    && SPACE_SPLIT_WEAK_KEYS.indexOf(splitKey) < 0) {
                return false;
            }
        }
        final Locale locale = (info.mSourceDict != null) ? info.mSourceDict.mLocale : null;
        if (null == locale) {
            return true;
        }
        final Integer maximumLengthForThisLanguage =
                sLanguageToMaximumAutoCorrectionWithSpaceLength.get(locale.getLanguage());
        if (null == maximumLengthForThisLanguage) {
            // This language does not enforce a maximum length to auto-correction
            return true;
        }
        return info.mWord.length() <= maximumLengthForThisLanguage
                || -1 == info.mWord.indexOf(Constants.CODE_SPACE);
    }

    /* package for test */ static SuggestedWordInfo getTransformedSuggestedWordInfo(
            final SuggestedWordInfo wordInfo, final Locale locale, final boolean isAllUpperCase,
            final boolean isOnlyFirstCharCapitalized, final int trailingSingleQuotesCount) {
        final StringBuilder sb = new StringBuilder(wordInfo.mWord.length());
        if (isAllUpperCase) {
            sb.append(wordInfo.mWord.toUpperCase(locale));
        } else if (isOnlyFirstCharCapitalized) {
            sb.append(StringUtils.capitalizeFirstCodePoint(wordInfo.mWord, locale));
        } else {
            sb.append(wordInfo.mWord);
        }
        // Appending quotes is here to help people quote words. However, it's not helpful
        // when they type words with quotes toward the end like "it's" or "didn't", where
        // it's more likely the user missed the last character (or didn't type it yet).
        final int quotesToAppend = trailingSingleQuotesCount
                - (-1 == wordInfo.mWord.indexOf(Constants.CODE_SINGLE_QUOTE) ? 0 : 1);
        for (int i = quotesToAppend - 1; i >= 0; --i) {
            sb.appendCodePoint(Constants.CODE_SINGLE_QUOTE);
        }
        SuggestedWordInfo result = new SuggestedWordInfo(sb.toString(), wordInfo.mPrevWordsContext,
                wordInfo.mScore, wordInfo.mKindAndFlags,
                wordInfo.mSourceDict, wordInfo.mIndexOfTouchPointOfSecondWord,
                wordInfo.mAutoCommitFirstWordConfidence);

        result.mOriginatesFromTransformerLM = wordInfo.mOriginatesFromTransformerLM;
        result.mOriginatesFromSwipeModel    = wordInfo.mOriginatesFromSwipeModel;

        return result;
    }
}
