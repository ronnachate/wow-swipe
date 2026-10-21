# WSwipe

A native Android swipe keyboard for **Thai + English**.

- **Layout**: Thai Kedmanee in 11 columns (`th_kedmanee_11.xml`) and English QWERTY.
- **Long-press = second layer**: hold any key to type its second character (ก → ฏ, ้ → ็,
  q → 1 …). Keep holding and slide along the popup for more choices.
- **Shift (cyan key, bottom-right in Thai)**: tap to show the second layer as the main
  characters for one key; double-tap to lock.
- **Swipe typing** in both languages (see below). Thai words are joined without spaces; English
  adds a space automatically.
- **Suggestion bar** always shows something useful: common words when idle, completions while
  typing, alternatives after a swipe (tap to replace). Long-press a suggestion to forget it.
- Backspace right after a swipe deletes the whole word. Drag on the space bar to move the cursor.
- **Learns your words**: words you use rank higher; new words (names, slang) are added after you
  type them twice and can then be swiped too.
- **E-mail suggestions**: type `name@` → `@gmail.com`, `@hotmail.com`, … (your own domains
  first); addresses you've typed before are suggested as you start them. E-mail fields open in
  English with an `@` key in place of the comma.
- **Privacy**: nothing is learned in password fields or when an app requests incognito
  (`IME_FLAG_NO_PERSONALIZED_LEARNING`); password fields get no suggestions or swipe. All data
  stays on the phone (`learned_th.tsv`, `learned_en.tsv`, `emails.tsv` in app storage).
- No internet permission, no analytics, no extra libraries.

## Build & install

Open the folder in **Android Studio** (Koala or newer) → let Gradle sync → **Run**. Or from a
terminal:

```sh
./gradlew assembleRelease          # → app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest        # swipe-accuracy tests
```

Then open the **WSwipe** app → *Open keyboard settings* (turn WSwipe on) → *Choose WSwipe*.

The release build is signed with the debug key so it installs directly. Use your own
signing key before sharing it. To let GitHub build the APK on every push, move `ci/github-build.yml` to
`.github/workflows/build.yml` (download the APK from the run's *Artifacts*).

Requirements: JDK 17, Android SDK 34, Gradle 8.9 (the wrapper downloads it).

## Project layout

```
app/src/main/
  assets/layouts/   th_kedmanee_11.xml, en_qwerty.xml, numeric.xml, symbol.xml
  assets/dict/      th.tsv (76k words), en.tsv (47k words) — "word<TAB>frequency"
  java/com/wow/wswipe/
    layout/KeyboardLayout.kt   layout model + XML parser + geometry
    engine/Dictionary.kt       word list, frequencies, completions, learned user words
    engine/EmailSuggester.kt   e-mail address + domain suggestions
    engine/SwipeDecoder.kt     key-sequence trie + swipe decoder
    ime/KeyboardView.kt        drawing + touch (tap / long-press popup / swipe / space drag)
    ime/WSwipeService.kt       InputMethodService: text editing logic
    SettingsActivity.kt        setup + settings screen
app/src/test/   SwipeDecoderTest.kt (layout + swipe accuracy), PersonalWordsTest.kt (learning, e-mail)
```

### Layout file format

```xml
<key c="ก" hold="ฏ" more="k,t"/>   <!-- tap, long-press, extra popup choices -->
<key fn="backspace" shade="medium" w="15"/>
<gap w="5"/>
```
`w` is the width in % of the keyboard; `swap="true"` on `<layout>` makes Shift swap tap/hold
characters (Thai) instead of upper-casing. Add a file and register its id in `WSwipeService`
to add a layout.

## How the swipe engine works

1. The finger path is **resampled every 0.2 key-widths**, so fast swipes don't skip keys.
2. Each sample "touches" its key **plus the horizontal neighbour on the side the finger is on**,
   and the key above/below when the finger is in the **top or bottom 30 %** of a key.
3. Words are stored in a **trie of key sequences**. Letters that share a key (base + long-press,
   e.g. ส/ศ, ต/ฺ) share a path, and repeated keys collapse, so "hello" and "helo" need the same
   motion.
4. A word is a candidate when its key sequence appears, in order, along the touched keys,
   **starting on the first key and ending on the last**.
5. Candidates are ranked by: closeness of the path to each letter, **sharp corners (> 50°) the
   word doesn't explain**, path length vs. the word's ideal length, and word frequency.

Tuning weights live at the top of `SwipeDecoder`. On simulated swipes the top suggestion is
right ~95 % of the time for common words, and the right word is in the top 3 ~98–100 %.

## Credits & licences

- Thai word frequencies: Thai National Corpus, via [PyThaiNLP](https://github.com/PyThaiNLP/pythainlp) (Apache-2.0).
- English word frequencies: [hermitdave/FrequencyWords](https://github.com/hermitdave/FrequencyWords) (MIT; OpenSubtitles 2018).
