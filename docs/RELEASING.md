# How a build reaches the user

**Read this before telling the user a fix is "in the new build".** Twice now the
code was fixed, CI went green, and the user still saw the old behaviour — because
the build they installed was not the build that was pushed.

## Where builds go

There is a single branch: `main`. `.github/workflows/build.yml` runs on every
push to it, and three of its publishing steps are branch-gated:

| Step | Condition | What it produces |
| --- | --- | --- |
| Upload APKs (artifact) | always | `hikari-apk` CI artifact — **only downloadable by someone logged into GitHub, as a zip** |
| Publish test APK to `build` branch | `refs/heads/main` | `hikari.apk` on the `build` branch (skipped when the APK is ≥100 MB — it currently is) |
| Publish test APK to `continuous` release | `refs/heads/main` | `continuous/hikari-signed.apk`, which is **what `Updater.UPDATE_URL` downloads** |
| Publish main release | `workflow_dispatch` + `release=true` | `v<version>/hikari.apk`, with the release body taken from that version's `CHANGELOG.md` section |

So a push to `main` already refreshes the `continuous` test channel, but a push
alone creates **no** versioned release — and only a versioned release makes the
in-app update dialog appear on installs older than it.

## The rule

Every round that changes user-visible behaviour must end with a **versioned
release** built from the pushed commit:

```bash
# 1. push to main and wait for the CI run to go green
# 2. publish that same commit as a release
curl -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  https://api.github.com/repos/codegeasse1/hikari/actions/workflows/build.yml/dispatches \
  -d '{"ref":"main","inputs":{"release":"true","version":"0.6.6"}}'
```

`Updater.checkForUpdate()` lists `releases?per_page=10`, skips the `continuous`
tag, takes the newest release and compares dotted versions against
`BuildConfig.VERSION_NAME` — so once `v<version>` exists and its `versionName` is
newer than the installed one, the in-app **Update** button finds it, and it is
also the first download on the releases page. Dispatch only stores the APK in
one asset (`hikari.apk`, ~145 MB), so it costs nothing but a CI run.

Bump `versionCode` **and** `versionName` in `app/build.gradle.kts` and add a
`## <version>` section to `CHANGELOG.md` first — the release body is taken from
that section.

## Notes

- Docs-only pushes (`.md`) do not start CI (`paths-ignore: '**.md'`), so editing
  the changelog costs no CI minutes and republishes nothing.
- Release assets are capped at 2 GB; the release APK is ~145 MB.
- The `build` branch holds a stale `hikari.apk` and cannot be refreshed while the
  APK is over GitHub's 100 MB per-file limit, so `continuous` is the live test
  channel and the `build` branch is only a historical download link.
- A changelog section is written for **users**, not for us: what was added and
  what was fixed, in plain words. No bug-report quotes, no "test build" framing,
  no chat references — the release page is the public face of the app.
- When pushing files programmatically, `.github/workflows/build.yml` must be
  included: an uploader that skips dot-directories silently leaves the workflow
  on an older revision.
