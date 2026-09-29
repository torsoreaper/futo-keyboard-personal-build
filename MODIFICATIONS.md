# Modifications

**This is an unofficial, modified build of the FUTO Keyboard. It is not produced,
endorsed, or supported by FUTO Holdings, Inc.**

This repository is based on [futo-org/android-keyboard](https://github.com/futo-org/android-keyboard)
at commit `a0b84ef`. The software is licensed under the
[FUTO Source First License 1.1-kb](LICENSE.md), which requires that anyone who
receives a copy also receives those terms, and that modified copies carry a
prominent notice that they have been modified. The distributed APK ships this
file and `LICENSE.md` alongside it.

Everything below is a change made on top of the upstream code. Nothing here is
part of the official FUTO Keyboard.

## Changes to auto-correction

1. **Auto-correction in "no suggestions" fields.** Fields that set a bare
   `TYPE_TEXT_FLAG_NO_SUGGESTIONS` (for example the Google search box) no longer
   disable auto-correction. Password, email, URI, filter and code fields are
   still excluded.

2. **Run-together words are split into two words.** A typed token that is not a
   word and contains a bottom-row key where the space bar may have been meant is
   offered as two words (`laxybdogz` → `lazy dogs`). Only `c v b n` (directly
   above the space bar) and `z x m` (outer keys, which additionally require the
   first half to already be a word) are treated this way.

3. **Number-row mistaps are corrected to letters.** A token containing digits
   where the digit is a mistap of the letter below it (`1`→`q`, `2`→`w`, …
   `0`→`p`) is corrected when the result is a real word (`hell9` → `hello`,
   `c0de` → `code`). Pure numbers are never touched.

4. **Confident transformer corrections commit.** When the dictionary has no
   real opinion (its best candidate scores below zero) the language model's
   suggestion is used instead of being discarded, so typos the dictionary
   cannot help with (`gjthun` → `GitHub`) commit on space.

5. **Typos correct without full confidence**, gated by a configurable length
   tolerance so a word is not replaced by one of a very different length.

6. **Per-token normalised probabilities** in the native language model, a wider
   correction beam, a typo prior, and a two-word context.

## Changes to personalisation

7. **Built-in word list.** Common profanity and over-the-counter medicine names
   are seeded into the user dictionary once so they are recognised as words.
   They are deliberately excluded from the glossary sent to the language model,
   which otherwise swamps the sentence context.

8. **User-history suggestions can no longer hijack the language model.** A word
   learned from history may only outrank the model when the model has not found
   a real word of its own.

## Added tooling

9. **Rotating on-device debug log.** A 250 MB log (5 MB chunks, oldest
   overwritten) in the app's private storage, which survives the keyboard being
   killed. It records what was typed, the top suggestion and its source, and
   whether it will auto-correct. Controlled from Settings → Miscellaneous.

## Reporting problems

Please do not report issues with this build to FUTO. They are not responsible
for it.
