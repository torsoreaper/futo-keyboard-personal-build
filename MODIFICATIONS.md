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

4. **Transformer corrections only commit when they are believable.** When the
   dictionary has no real opinion (its best candidate scores below zero) the
   language model's suggestion used to be given the highest score and committed.
   It now only commits when the model's word is a real word the keyboard knows
   (main, contacts or personal dictionary) and is close to what was typed, so
   `deepseel` no longer becomes `deepsells` and `Mouthguar` no longer becomes
   `Northern`. Otherwise the model's words are still suggested, but you have to
   tap them.

5. **Typos correct without full confidence**, gated by a configurable length
   tolerance so a word is not replaced by one of a very different length.

6. **Per-token normalised probabilities** in the native language model, a wider
   correction beam, a typo prior, and a two-word context.

7. **Allowlist corrections no longer override the language model.** The built-in
   allowlist makes corrections like `tine` → `time`, `hoe` → `how` and `ill` → `I'll`.
   They used to be sent with the highest possible score and a flag that skipped the
   normal confidence check, so they always won. They are still offered first, but
   now only replace what you typed when the language model agrees, so `tine` is not
   changed to `time` unless the sentence is really about time. Allowlist entries for
   things that are not words at all (`alot` → `a lot`, `i` → `I`) still correct.

## Changes to personalisation

8. **Built-in word list.** Common profanity and over-the-counter medicine names
   are seeded into the user dictionary once so they are recognised as words.

9. **Custom (personal-dictionary) words are treated like normal dictionary words.**
   The personal dictionary used to be pasted into every request sent to the language
   model as a `(Glossary: ...)` list, which pushed those words forward and let them
   overrule the sentence. It is now just another dictionary.

10. **Your own words win when what you typed is not a word.** A word learned from
    typing history used to be pushed above the language model whenever the model had
    no real word of its own. That is gone. Instead, when what you typed is not itself
    a word, the closest word from the personal dictionary or your typing history is
    used if it is a closer match than the model's guess, so `deepseel` becomes the
    `deepseek` you have typed before instead of an invented word. It cannot hijack a
    real word, because it only applies when what you typed is not one.

## Added tooling

11. **Rotating on-device debug log.** A 250 MB log (5 MB chunks, oldest
    overwritten) in the app's private storage, which survives the keyboard being
    killed. It records what was typed, the top suggestion and its source, and
    whether it will auto-correct. Controlled from Settings → Miscellaneous.

## Reporting problems

Please do not report issues with this build to FUTO. They are not responsible
for it.
