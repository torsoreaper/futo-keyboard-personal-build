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
   These seeded words are recognised and suggested like ordinary dictionary
   words, but they are deliberately kept out of the "prefer a word the user has
   used before" path, so typing the start of a normal word (`immig` for
   immigrants) is not force-corrected to a seeded word (`dammit`).

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

## Further auto-correction changes

12. **Numbers and times keep their digits.** A token that is only digits plus a
    unit (`7am`, `30k`, `5x`) or a multi-digit ordinal (`19th`) is no longer put
    through the digit-to-letter fix, so it cannot be turned into an unrelated
    word (`yam`, `wok`, `with`). Genuine top-row mistaps still correct
    (`hell9` -> `hello`, `2nd` -> `end`, `col3man` -> `Coleman`).

13. **The sentence model sees more context.** The text handed to the language
    model before it is trimmed was raised from about five words / 70 characters
    to about eight words / 110 characters (and the hard cutoff from 144 to 240),
    so a word typed with several wrong keys is judged against more of the
    sentence. This can make the keyboard slightly slower, by design.

14. **A close typo is only committed when the keyboard is sure.** When the typed
    word is not a known word and the best suggestion is a close match, it used
    to be committed without the normal confidence check. It now additionally
    requires the suggestion to be marked appropriate for auto-correction, so a
    guess the sentence model offered but that the merge deliberately demoted (an
    invented word) cannot be committed by pressing space.

15. **A suggestion the sentence model only echoed back no longer blocks the
    dictionary's own correction.** The model returns the word being typed as one
    of its candidates; because the suggestion list is ordered by score, that echo
    could sit above the dictionary's correction, and the keyboard would then
    decide "the typed word is already the best suggestion" and commit the typo
    when space was pressed (`habe`, `parb`, `tha`, `ans`). The dictionary's
    correction now takes the auto-correction slot instead. The gates are narrow,
    because over-correcting a word you meant is as bad as missing one: the
    dictionary's word must be the **same length as what you typed with exactly one
    character replaced by a key that is next to the one you pressed**, must
    **begin with the same letter**, and must be positively scored and marked
    appropriate by the dictionary. That is the shape of a single fat-fingered
    key, so `habe` -> `have` (b next to v), `ans` -> `and` (s next to d),
    `licky` -> `lucky` (i next to u) and `boble` -> `Bible` (o next to i) are
    fixed, while `tazo` -> `task` and `parb` -> `path` (two wrong keys),
    `bluesky` -> `bluesy`, `twlo` -> `two` and `pre` -> `press` (a dropped or
    added key), `tha` -> `the` and `revanant` -> `revenant` (a is not next to e),
    `danaher` -> `manager`, `auth` -> `with`, `descale` -> `rescale` and
    `7am` -> `jam` all stay exactly as typed. Sentence-initial typos work too, so
    the model returning your word capitalised (`habe` as `Habe`) no longer
    disables the fix. A deliberate elongation such as `Ohhhh` is never shortened.

    The "next to the one you pressed" test means the two keys must **touch** on the
    keyboard, measured in key widths: same row (1.0), or the two diagonal rings
    (1.118 and 1.414). The next key out is `a`/`e` at 1.803, which is *one key over
    as well as one row down* — a spelling slip, not a finger slip — and `d`/`m` is
    at 5.1. Both are deliberately left alone, so the rule can never claim that "any
    key could have been any nearby key". On the on-device logs this covers 92.7% of
    the single-substitution typos; the remaining ones are mostly vowel confusions
    such as `revanant` -> `revenant`, which better typing (not a wider radius) is
    the answer to.

    That radius is a setting: **Settings -> Typing -> Typo correction range**. The
    slider snaps to the five meaningful distances (1.000 adjacent keys only, 1.118
    + closest diagonal, 1.414 + the rest of the touching ring *[the default]*,
    1.803 adds `a`/`e`, 2.000 adds `e`/`t`), and tapping the readout lets you type
    an exact value. It is capped at 2.000, so `d`/`m` at 5.099 is unreachable by
    construction. Widening it trades a little more coverage (95.0% at 1.803) for
    rewriting things that were not finger slips.

16. **Tokens with digits and words the dictionary has no opinion about are left
    alone.** A token containing a digit is never replaced, so `7am`, `30k`, `40s`
    and `19th` keep their digits, and a candidate the dictionary scores below zero
    is ignored, so it cannot become the top suggestion.

17. **One wrong key is corrected even when the sentence model was the only one to
    spot the word.** If what you typed is not a word and the model proposes a word
    that differs by exactly one neighbouring key — same length, same first letter —
    that word is committed on space. This fixes `noe` -> `now` and `voicemaols` ->
    `voicemails` (the model had the right word, but `voicemails` is not in the
    keyboard's dictionary, and a model guess without the dictionary's blessing was
    being downgraded to a tappable suggestion only). The model must be as confident
    as any other transformer correction before this applies, so an unsure guess
    still just sits in the strip.

## Reporting problems

Please do not report issues with this build to FUTO. They are not responsible
for it.
