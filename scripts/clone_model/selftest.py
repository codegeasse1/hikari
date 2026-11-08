#!/usr/bin/env python3
"""
Listens to the converted model with the real sherpa-onnx engine (the same one the app uses)
and refuses to let a broken model be published.

  selftest.py --dir OUT --ref reference.wav --wavs DIR

Passes when two Hindi lines are spoken, are not silent, and stop at a sensible length (the known
failure of a wrong conversion is 40-80 seconds of noise). Tries the encoder with the BOS frame
first and the spare one without it second; the one that passes becomes encoder.onnx.
"""
import argparse
import shutil
import sys
from pathlib import Path

import numpy as np
import soundfile as sf

TESTS = [
    ("short", "आज मौसम बहुत अच्छा है।", 0.7, 10.0),
    ("long", "ज़िंदगी हमेशा हमारी सोच के हिसाब से नहीं चलती, लेकिन कभी-कभी अचानक मिले पल ही सबसे खूबसूरत यादें बन जाते हैं।", 3.5, 30.0),
]


def make_tts(d: Path, enc: Path):
    import sherpa_onnx as so
    m = so.OfflineTtsModelConfig(num_threads=2)
    m.pocket = so.OfflineTtsPocketModelConfig(
        lm_flow=str(d / "lm_flow.int8.onnx"), lm_main=str(d / "lm_main.int8.onnx"),
        encoder=str(enc), decoder=str(d / "decoder.int8.onnx"),
        text_conditioner=str(d / "text_conditioner.onnx"),
        vocab_json=str(d / "vocab.json"), token_scores_json=str(d / "token_scores.json"),
    )
    return so, so.OfflineTts(so.OfflineTtsConfig(model=m))


def build_cfg(so, ref, ref_sr):
    forms = []
    for r in (ref, ref.tolist()):
        forms.append(dict(reference_audio=r, reference_sample_rate=ref_sr, num_steps=2, speed=1.0))
        forms.append(dict(reference_audio=r, reference_sample_rate=ref_sr, num_steps=2))
    for kw in forms:
        try:
            return so.GenerationConfig(**kw)
        except Exception as e:  # noqa
            print("GenerationConfig(%s, %s) failed: %s" % (sorted(kw), type(kw["reference_audio"]).__name__, e), flush=True)
    # last try: empty config, then set the fields one by one
    try:
        cfg = so.GenerationConfig()
        cfg.reference_audio = ref.tolist()
        cfg.reference_sample_rate = ref_sr
        cfg.num_steps = 2
        return cfg
    except Exception as e:  # noqa
        print("GenerationConfig() + attributes failed: %s" % e, flush=True)
    raise RuntimeError("cannot build GenerationConfig; sherpa_onnx has: " + str([n for n in dir(so) if "Gen" in n]))


def speak(so, tts, text, ref, ref_sr):
    cfg = build_cfg(so, ref, ref_sr)
    audio = tts.generate(text, cfg)
    return np.asarray(audio.samples, dtype=np.float32), int(audio.sample_rate)


def check(d: Path, enc: Path, ref, ref_sr, wavs: Path, tag: str):
    so, tts = make_tts(d, enc)
    ok = True
    for name, text, lo, hi in TESTS:
        x, sr = speak(so, tts, text, ref, ref_sr)
        secs = len(x) / float(sr) if sr else 0.0
        rms = float(np.sqrt(np.mean(x ** 2))) if len(x) else 0.0
        good = lo <= secs <= hi and rms > 0.01
        print("[%s] %-5s %6.2fs rms=%.4f (want %.1f-%.1fs) %s" % (tag, name, secs, rms, lo, hi, "OK" if good else "BAD"), flush=True)
        if len(x):
            sf.write(str(wavs / ("%s_%s.wav" % (tag, name))), x, sr)
        ok = ok and good
    return ok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", required=True)
    ap.add_argument("--ref", required=True)
    ap.add_argument("--wavs", required=True)
    a = ap.parse_args()
    d, wavs = Path(a.dir), Path(a.wavs)
    wavs.mkdir(parents=True, exist_ok=True)
    ref, ref_sr = sf.read(a.ref, dtype="float32")
    if ref.ndim > 1:
        ref = ref.mean(axis=1)
    ref = np.ascontiguousarray(ref, dtype=np.float32)
    candidates = [("with_bos", d / "encoder.onnx")]
    spare = d / "encoder.nobos.onnx"
    if spare.exists():
        candidates.append(("no_bos", spare))
    for tag, enc in candidates:
        try:
            if check(d, enc, ref, int(ref_sr), wavs, tag):
                if enc != d / "encoder.onnx":
                    shutil.copy2(enc, d / "encoder.onnx")
                spare.unlink(missing_ok=True)
                print("SELF TEST PASSED with encoder variant:", tag)
                return 0
        except Exception as e:  # noqa
            import traceback
            traceback.print_exc()
            print("[%s] crashed: %r" % (tag, e), flush=True)
            print("::error title=Self test crashed::[%s] %r" % (tag, e), flush=True)
    print("::error title=Self test::the model did not speak a normal Hindi line (see the numbers above) - nothing is published")
    print("SELF TEST FAILED - nothing is published.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
