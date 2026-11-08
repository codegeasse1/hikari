#!/usr/bin/env python3
"""
Turns the open Hindi/Indic Pocket TTS model (Thanushttk/pocket-tts-multilingual, CC-BY-4.0)
into the 7 files the Hikari app downloads for its cloned voices:

    lm_main.int8.onnx  lm_flow.int8.onnx  encoder.onnx  decoder.int8.onnx
    text_conditioner.onnx  vocab.json  token_scores.json

It is run by .github/workflows/clone-model.yml (never on the phone).

  convert.py all --src DIR --sherpa DIR --out DIR
      tries the exporters one after the other (each one in a clean folder), fixes the usual
      small problems by itself (a missing python package, a config key the exporter does not
      know) and retries, then collects + renames the files, bakes the BOS frame into the
      encoder and converts the tokenizer.

If everything fails, the REAL error of every attempt is printed as a red annotation on top of
the run page (open the run, look at "Annotations") and the whole log of every attempt is in the
artifact "clone-model-logs".
"""
import argparse
import collections
import json
import os
import re
import shutil
import subprocess
import sys
import traceback
from pathlib import Path

LOGDIR = Path("/tmp/clone-model-logs")

# The exporter. lomotron/pocket-tts-onnx-export is the live copy of KevinAHM's exporter (the
# KevinAHM repository itself is gone - GitHub answers "could not read Username" for it).
LOMOTRON = "https://github.com/lomotron/pocket-tts-onnx-export.git"
LOMOTRON_PINNED = "8f52199533098963fe1cbd90bed35bb20d871b5e"   # the commit others exported with

ATTEMPTS = [
    dict(name="lomotron main, --config", url=LOMOTRON, ref="main", mode="config"),
    dict(name="lomotron pinned commit, --language", url=LOMOTRON, ref=LOMOTRON_PINNED, mode="language"),
    dict(name="lomotron main, --language", url=LOMOTRON, ref="main", mode="language"),
]

ROLES = {
    # sherpa name            : names the exporters use (first one found wins)
    "lm_main.int8.onnx": ["lm_main.int8.onnx", "flow_lm_main_int8.onnx"],
    "lm_flow.int8.onnx": ["lm_flow.int8.onnx", "flow_lm_flow_int8.onnx"],
    "decoder.int8.onnx": ["decoder.int8.onnx", "mimi_decoder_int8.onnx"],
    "encoder.onnx": ["encoder.onnx", "mimi_encoder.onnx"],
    "text_conditioner.onnx": ["text_conditioner.onnx"],
}

MIN_BYTES = {
    "lm_main.int8.onnx": 40_000_000,
    "lm_flow.int8.onnx": 5_000_000,
    "encoder.onnx": 15_000_000,
    "decoder.int8.onnx": 10_000_000,
    "text_conditioner.onnx": 8_000_000,
    "vocab.json": 1_000,
    "token_scores.json": 1_000,
}

PIP_NAMES = {"yaml": "pyyaml", "sklearn": "scikit-learn", "PIL": "pillow", "cv2": "opencv-python-headless"}


def log(*a):
    print(*a, flush=True)


def gh_error(title, text):
    text = (text or "")[-1800:]
    text = text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    print("::error title=%s::%s" % (title, text), flush=True)


def gh_warning(text):
    print("::warning::%s" % text.replace("\n", " "), flush=True)


def sh(cmd, cwd=None, env=None, logfile=None):
    """Runs a command, shows its output live, keeps the last 400 lines. Returns (code, tail)."""
    log("+", " ".join(str(c) for c in cmd), "   (in %s)" % (cwd or "."))
    tail = collections.deque(maxlen=400)
    try:
        p = subprocess.Popen([str(c) for c in cmd], cwd=cwd, env=env, stdout=subprocess.PIPE,
                             stderr=subprocess.STDOUT, text=True, errors="replace", bufsize=1)
    except OSError as e:
        return 127, "could not start %s: %s" % (cmd[0], e)
    for line in p.stdout:
        sys.stdout.write(line)
        sys.stdout.flush()
        tail.append(line.rstrip("\n"))
        if logfile:
            logfile.write(line)
    p.wait()
    return p.returncode, "\n".join(tail)


def short_error(tail):
    """The last python traceback of the output (or the last lines when there is none)."""
    lines = tail.splitlines()
    idx = None
    for i, l in enumerate(lines):
        if l.startswith("Traceback"):
            idx = i
    chunk = lines[idx:] if idx is not None else lines[-14:]
    return "\n".join(chunk[-14:])


# ----------------------------------------------------------------------------- config
def load_yaml(p: Path):
    import yaml
    return yaml.safe_load(p.read_text(encoding="utf-8"))


def absolutize(node, src: Path):
    """weights_path / tokenizer_path in the model's config.yaml are relative to the model folder.
    Make every '..._path' that points to a file of the model folder absolute."""
    if isinstance(node, dict):
        for k, v in list(node.items()):
            if isinstance(v, str) and k.endswith("_path") and not re.match(r"^(hf://|https?://|/)", v):
                cand = (src / v)
                if cand.exists():
                    node[k] = str(cand.resolve())
            else:
                absolutize(v, src)
    elif isinstance(node, list):
        for v in node:
            absolutize(v, src)


def drop_key_everywhere(node, key):
    n = 0
    if isinstance(node, dict):
        if key in node:
            del node[key]
            n += 1
        for v in list(node.values()):
            n += drop_key_everywhere(v, key)
    elif isinstance(node, list):
        for v in node:
            n += drop_key_everywhere(v, key)
    return n


def write_cfg(cfg: dict, dest: Path):
    import yaml
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(yaml.safe_dump(cfg, sort_keys=False, allow_unicode=True), encoding="utf-8")


# ----------------------------------------------------------------------------- self repair
def auto_fix(tail: str, cfg: dict, tried: set) -> bool:
    """Looks at the error and fixes the commonest small problems. True = changed something, retry."""
    changed = False
    for m in re.finditer(r"No module named ['\"]([A-Za-z0-9_\.]+)['\"]", tail):
        top = m.group(1).split(".")[0]
        pkg = PIP_NAMES.get(top, top)
        key = ("pip", pkg)
        if key in tried:
            continue
        tried.add(key)
        log("auto-fix: python package '%s' is missing - installing it" % pkg)
        code, _ = sh([sys.executable, "-m", "pip", "install", pkg])
        if code == 0:
            changed = True
    # pydantic: "some.key\n  Extra inputs are not permitted"
    for m in re.finditer(r"^([A-Za-z0-9_\.\[\]\-]+)\s*\n\s+Extra inputs are not permitted", tail, re.M):
        last = re.sub(r"\[\d+\]", "", m.group(1)).split(".")[-1]
        key = ("extra", last)
        if key in tried or not last:
            continue
        tried.add(key)
        if drop_key_everywhere(cfg, last):
            log("auto-fix: the exporter does not know the config key '%s' - removed it" % last)
            changed = True
    return changed


# ----------------------------------------------------------------------------- export
def run_attempt(n, spec, src: Path, out_dir: Path):
    """One exporter. Returns (bundle_dir or None, error text)."""
    LOGDIR.mkdir(parents=True, exist_ok=True)
    lf = open(LOGDIR / ("attempt-%d.log" % n), "w", encoding="utf-8")
    exporter = Path("/tmp/exporter")
    shutil.rmtree(exporter, ignore_errors=True)
    shutil.rmtree(out_dir, ignore_errors=True)
    out_dir.mkdir(parents=True)
    env = dict(os.environ)
    env["GIT_TERMINAL_PROMPT"] = "0"
    env["PYTHONUNBUFFERED"] = "1"
    env["HF_HUB_OFFLINE"] = "1"          # everything is local; never try the gated Kyutai repo
    env["PYTHONPATH"] = "." + os.pathsep + env.get("PYTHONPATH", "")

    code, tail = sh(["git", "clone", spec["url"], exporter], env=env, logfile=lf)
    if code != 0:
        return None, "git clone of %s failed:\n%s" % (spec["url"], short_error(tail))
    code, tail = sh(["git", "checkout", spec["ref"]], cwd=exporter, env=env, logfile=lf)
    if code != 0:
        return None, "git checkout %s failed:\n%s" % (spec["ref"], short_error(tail))

    # the exporter wants the model files next to it (relative paths) AND absolute ones in the config
    for p in list(src.rglob("*.safetensors")) + list(src.rglob("*.model")):
        d = exporter / p.name
        if not d.exists():
            shutil.copy2(p, d)

    cfg_src = src / "config.yaml"
    if not cfg_src.exists():
        cfg_src = src / "student.yaml"
    if not cfg_src.exists():
        return None, "the downloaded model folder has no config.yaml"
    cfg = load_yaml(cfg_src)
    log("config.yaml of the model:")
    log(json.dumps(cfg, indent=1, ensure_ascii=False, default=str)[:3000])

    tried = set()
    last_tail = ""
    for rnd in range(1, 8):
        absolutize(cfg, src)
        if spec["mode"] == "config":
            cfg_path = exporter / "thanush_config.yaml"
            write_cfg(cfg, cfg_path)
            cmd = [sys.executable, "export.py", "--config", str(cfg_path), "--quantize", "--output_dir", str(out_dir)]
            label = cfg_path.stem
        else:
            write_cfg(cfg, exporter / "pocket_tts" / "config" / "thanush.yaml")
            cmd = [sys.executable, "export.py", "--language", "thanush", "--quantize", "--output_dir", str(out_dir)]
            label = "thanush"
        code, tail = sh(cmd, cwd=exporter, env=env, logfile=lf)
        last_tail = tail
        if code == 0:
            bundle = out_dir / label
            if not bundle.exists():
                found = [p.parent for p in out_dir.rglob("*.onnx")]
                bundle = found[0] if found else bundle
            return bundle, ""
        log("export.py exit code %s (round %d)" % (code, rnd))
        if not auto_fix(tail, cfg, tried):
            break
    return None, "export.py failed:\n%s" % short_error(last_tail)


# ----------------------------------------------------------------------------- collect
def pick(bundle: Path, cands):
    for c in cands:
        if (bundle / c).exists():
            return bundle / c
    raise FileNotFoundError("%s not found in %s (has: %s)" % (cands, bundle, sorted(p.name for p in bundle.iterdir())))


def bake_bos(encoder_in: Path, bos_npy: Path, encoder_out: Path):
    """Puts the model's learned BOS-before-voice frame in front of the encoder output (a Concat
    inside the graph). sherpa-onnx has no option for it and the model needs it (without it the
    output is 40-80 seconds of noise)."""
    import numpy as np
    import onnx
    from onnx import helper, numpy_helper

    bos = np.load(str(bos_npy)).astype("float32")
    bos = bos.reshape(1, 1, -1)
    m = onnx.load(str(encoder_in))
    g = m.graph
    out = g.output[0]
    dims = out.type.tensor_type.shape.dim
    if len(dims) == 3 and dims[2].dim_value > 0 and dims[2].dim_value != bos.shape[2]:
        raise RuntimeError("BOS width %d does not match encoder output width %d" % (bos.shape[2], dims[2].dim_value))
    orig = out.name
    inner = orig + "__without_bos"
    for n in g.node:
        for i, o in enumerate(n.output):
            if o == orig:
                n.output[i] = inner
        for i, x in enumerate(n.input):
            if x == orig:
                n.input[i] = inner
    g.initializer.append(numpy_helper.from_array(bos, name="bos_before_voice_const"))
    g.node.append(helper.make_node("Concat", ["bos_before_voice_const", inner], [orig], axis=1))
    keep = [v for v in g.value_info if v.name != orig]
    del g.value_info[:]
    g.value_info.extend(keep)
    if len(dims) >= 2:                      # the number of frames changes (+1): make it unknown
        dims[1].Clear()
    onnx.checker.check_model(m)
    onnx.save(m, str(encoder_out))
    log("BOS frame baked into the encoder:", bos.shape, "->", encoder_out.name)


def convert_tokenizer(sherpa: Path, tok_model: Path, out: Path):
    """vocab.json + token_scores.json. First the official sherpa-onnx script, then our own."""
    work = out.parent / "tokwork"
    shutil.rmtree(work, ignore_errors=True)
    work.mkdir(parents=True)
    script = sherpa / "scripts" / "pocket-tts" / "convert_tokenizer.py"
    got = False
    if script.exists():
        text = script.read_text(encoding="utf-8", errors="replace")
        log("---- official convert_tokenizer.py (first 2500 chars) ----")
        log(text[:2500])
        log("----------------------------------------------------------")
        opts = re.findall(r"add_argument\(\s*[\"']([^\"']+)[\"']", text)
        pos = [o for o in opts if not o.startswith("-")]
        flags = [o for o in opts if o.startswith("--")]
        attempts = []
        if pos:
            attempts.append([str(tok_model), str(work)][: len(pos)])
        in_flags = [f for f in flags if re.search(r"model|token|input|spm|sp$", f)]
        out_flags = [f for f in flags if re.search(r"out|dir|save|dest", f)]
        if in_flags:
            a = [in_flags[0], str(tok_model)]
            if out_flags:
                a += [out_flags[0], str(work)]
            attempts.append(a)
        attempts.append([str(tok_model)])
        for a in attempts:
            code, _ = sh([sys.executable, script] + a, cwd=work)
            if code != 0:
                continue
            vj = sorted(work.rglob("vocab.json")) + sorted(tok_model.parent.glob("vocab.json"))
            ts = sorted(work.rglob("token_scores.json")) + sorted(tok_model.parent.glob("token_scores.json"))
            if vj and ts:
                shutil.copy2(vj[0], out / "vocab.json")
                shutil.copy2(ts[0], out / "token_scores.json")
                got = True
                log("tokenizer converted with the official script")
                break
    if not got:
        gh_warning("official tokenizer script did not give vocab.json/token_scores.json - using the built-in converter")
        import sentencepiece as spm
        sp = spm.SentencePieceProcessor(model_file=str(tok_model))
        n = sp.get_piece_size()
        vocab = {sp.id_to_piece(i): i for i in range(n)}
        scores = {sp.id_to_piece(i): float(sp.get_score(i)) for i in range(n)}
        (out / "vocab.json").write_text(json.dumps(vocab, ensure_ascii=False), encoding="utf-8")
        (out / "token_scores.json").write_text(json.dumps(scores, ensure_ascii=False), encoding="utf-8")
        log("tokenizer written by the built-in converter, pieces:", n)


def do_collect(bundle: Path, src: Path, sherpa: Path, out: Path):
    shutil.rmtree(out, ignore_errors=True)
    out.mkdir(parents=True)
    log("bundle folder:", bundle, sorted(p.name for p in bundle.iterdir()))
    for sherpa_name, cands in ROLES.items():
        shutil.copy2(pick(bundle, cands), out / sherpa_name)
    bos = next(iter(list(bundle.rglob("bos_before_voice.npy"))), None)
    if bos is not None:
        shutil.copy2(out / "encoder.onnx", out / "encoder.nobos.onnx")   # spare for the self test
        bake_bos(out / "encoder.nobos.onnx", bos, out / "encoder.onnx")
    else:
        gh_warning("no bos_before_voice.npy was written by the exporter - encoder is used as it is")
    tok = next(iter(list(bundle.glob("tokenizer.model")) + list(src.rglob("tokenizer.model"))), None)
    if tok is None:
        raise FileNotFoundError("tokenizer.model not found")
    convert_tokenizer(sherpa, tok, out)
    log("final files:")
    bad = []
    for p in sorted(out.iterdir()):
        size = p.stat().st_size
        log("  %-28s %12d bytes" % (p.name, size))
        if p.name in MIN_BYTES and size < MIN_BYTES[p.name]:
            bad.append("%s is only %d bytes" % (p.name, size))
    if bad:
        raise RuntimeError("; ".join(bad))


def do_all(src: Path, sherpa: Path, out: Path):
    LOGDIR.mkdir(parents=True, exist_ok=True)
    errors = []
    for n, spec in enumerate(ATTEMPTS, 1):
        log("\n=================== attempt %d/%d: %s ===================" % (n, len(ATTEMPTS), spec["name"]))
        try:
            bundle, err = run_attempt(n, spec, src, Path("/tmp/exporter_out"))
            if bundle is not None:
                try:
                    do_collect(bundle, src, sherpa, out)
                    log("\nCONVERSION OK with: %s" % spec["name"])
                    return 0
                except Exception as e:  # noqa
                    traceback.print_exc()
                    err = "collect failed: %r\n%s" % (e, traceback.format_exc()[-900:])
        except Exception as e:  # noqa
            traceback.print_exc()
            err = "crashed: %r" % (e,)
        log("attempt %d failed: %s" % (n, err))
        errors.append("[%s]\n%s" % (spec["name"], err))
    (LOGDIR / "summary.txt").write_text("\n\n".join(errors), encoding="utf-8")
    for e in errors:
        gh_error("Clone model convert failed", e)
    log("\nALL ATTEMPTS FAILED. The real errors are above and in the red annotations of this run.")
    return 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("step", choices=["all"])
    ap.add_argument("--src", required=True)
    ap.add_argument("--sherpa", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    sys.exit(do_all(Path(a.src), Path(a.sherpa), Path(a.out)))


if __name__ == "__main__":
    main()
