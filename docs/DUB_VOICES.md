# Dub voices: how the Hindi voices work and how to add more

## Where it is in the app (main build)

Two separate dub systems live in the player. They never speak at the same time: turning one on turns the other off.

| Player > Audio | What it is |
|---|---|
| **AI Dub** | The light one: System TTS or Piper (natural, offline, one voice pack per language). Speaks the subtitle live. Unchanged. |
| **Dub audio** | The full one (this document): Automatic / Google / Phone / **Neural** voices, a different voice per character, "listen to the original voices" scan (man / woman), voice files downloaded once. Also reachable from AI Dub > *More voices & engines*. |

**Make subtitle from voice** (Whisper speech-to-text) is in Player > Subtitles, and as a button inside the Dub audio panel when the video has no subtitle.

The neural voices speak **Hindi and English**. Choosing *Neural voice* while another language is selected switches the language to Hindi for you.

The `Gender` enum is shared (`SpeakerVoices.kt`); AI Dub's own Piper pack helper is `AiDubPiperPack` (`PiperVoice.kt`) so it does not clash with the Dub engine's `PiperVoice` data class.

## What is there now (Hindi, neural / offline)

| Actor | Gender | Engine | Source |
|---|---|---|---|
| Myira | woman | Pocket TTS (voice cloning) | `app/src/main/assets/dub/voices/myira.wav` |
| Kyra | woman | Pocket TTS (voice cloning) | `app/src/main/assets/dub/voices/kyra.wav` |
| Rohan | man | Piper | downloaded once |
| Pratham | man | Piper | downloaded once |
| Priyamvada | woman | Piper (`vits-piper-hi_IN-priyamvada-medium`) | downloaded once |

Priyamvada is a real Piper voice, so she is as fast as the men. She is listed first among the women
(`SherpaModels.VOICES`), so she is the main woman's voice; Myira and Kyra are the next women. If the cloned
women turn out too slow on a phone, the dub puts them aside for that run and every woman's line uses Priyamvada.

English still uses the two Kokoro voices, nothing changed there.

## How the cloning works

1. Subtitle line -> translated to Hindi (as before).
2. `SherpaTtsVoice` picks the actor of that character. A woman gets Priyamvada / Myira / Kyra, a man gets Rohan / Pratham.
3. For Myira / Kyra the helper process (`NeuralTtsService`) runs sherpa-onnx **Pocket TTS** with the
   actor's recording as the voice prompt (`GenerationConfig.referenceAudio`). The model speaks the
   translated line in the voice of that recording.
4. `DubFx.process(..., faithful = true)`: the clip is NOT pitch-shifted, NOT re-timed and gets no
   "acting" effects, so the voice stays the voice of the sample. It only trims silence, evens the loudness
   and, when a line is longer than its slot, speeds it up WITHOUT changing the pitch.

The cloning model (about 170 MB, int8) is downloaded on the first neural Hindi dub into
`files/dub_models/pocket-multi/`. The files and download addresses are in `SherpaModels`
(`CLONE_FILES`, `CLONE_BASES`). If that download fails, Hindi neural dubbing still works with the men's voices
and the app says so.

## If the voices do not download

The dub panel shows the real error in a box (host, file, reason) and has a **Copy** button. Typical reasons:

| Message | Meaning |
|---|---|
| `UnknownHostException: huggingface.co` / `github.com` | the phone's DNS or the network blocks that host: try another Wi-Fi / mobile data, or pick a DNS in Settings -> Network and Internet -> DNS mode |
| `answered 404` | the file is not in the clone-model release yet: run the **Build clone model** workflow (and check the repository is public) |
| `sent a web page instead of the file` | a captive portal / block page answered |
| `the file was too small` | the same, or a cut file: it is deleted and the next address is tried |
| `Network problem ... after 4 tries` | the connection keeps dropping: tap Try again, it continues from what is saved |

Everything downloaded so far is kept, so Try again never starts from zero.

## Add another cloned voice

1. Record / cut 6 to 12 seconds of ONE person talking, clean, no music. Convert to WAV, 24 kHz, mono, 16-bit:

   ```
   ffmpeg -i sample.mp3 -ac 1 -ar 24000 -c:a pcm_s16le name.wav
   ```
2. Put it in `app/src/main/assets/dub/voices/name.wav`.
3. Add one line to `SherpaModels.CLONE_VOICES`:

   ```kotlin
   PiperVoice("clone-name", Gender.FEMALE, "Name", clone = true, ref = "dub/voices/name.wav"),
   ```

## Add another language

The same Pocket TTS multilingual model also speaks Marathi (`mr`), Tamil (`ta`), Telugu (`te`),
Kannada (`kn`) and Malayalam (`ml`). To switch one on, add its code to `SherpaModels.CLONE_LANGS`
(for example `setOf("hi", "mr")`). Men and women both come from the cloned voices in a language that has no
Piper voices (a man's voice is the cloned voice moved lower). Listen to a few lines before shipping a new language.

## Where the cloning model comes from (and what to do when the voices do not download)

The cloning model is the open model **Thanushttk/pocket-tts-multilingual** (CC-BY-4.0). The app does NOT
download it from Hugging Face any more (the old converted copy, `dhruvs99/...`, was taken down and answers
401). Instead the repository converts it itself and keeps the 7 files in its own release:

1. GitHub -> **Actions** -> **Build clone model** -> **Run workflow** (about 15-30 minutes, run it once).
2. The workflow downloads the open model, converts it, then LISTENS to it with the real sherpa-onnx engine
   (two Hindi lines: must be spoken, not silent, not 40 seconds of noise). Only if that passes, it uploads
   `lm_main.int8.onnx lm_flow.int8.onnx encoder.onnx decoder.int8.onnx text_conditioner.onnx vocab.json
   token_scores.json` to the pre-release **clone-model** of the same repository, and attaches the test
   voices (`clone-model-test-voices`) so you can hear them.
3. The app downloads from `https://github.com/<repository>/releases/download/clone-model/` (the repository the
   APK was built in, then `Updater.REPO`). **The repository must be public**, otherwise a phone cannot download
   from it (the workflow prints a warning if so).

If the workflow fails, nothing is published and the app keeps working with the Piper men's voices. Open the
failed run, copy the last error lines from the step that is red, and fix / report that step.

## Build

`.github/workflows/build.yml` fetches sherpa-onnx **v1.13.8** (native libraries + `Tts.kt`). Pocket TTS needs
1.12.24 or newer. A guard step fails the build early with a clear message if the fetched `Tts.kt` lacks a name
the dub code uses.
