# How a build reaches the user

**Read this before telling the user a fix is "in the new build".** Twice now the
code was fixed, CI went green, and the user still saw the old behaviour — because
the build they installed was not the build that was pushed.

## Where builds go

`.github/workflows/build.yml` runs on every push to `main` *and* `Downloader`,
but only three of its publishing steps are branch-gated:

| Step | Condition | What it produces |
| --- | --- | --- |
| Upload APKs (artifact) | always | `hikari-apk` CI artifact — **only downloadables by someone logged into GitHub, as a zip** |
| Publish test APK to `build` branch | `refs/heads/main` | `hikari.apk` on the `build` branch (skipped when the APK is ≥100 MB — it currently is) |
| Publish test APK to `continuous` release | `refs/heads/main` | `continuous/hikari-signed.apk`, which is **what `Updater.UPDATE_URL` downloads** |
| Publish main release | `workflow_dispatch` + `release=true` | `v<version>/hikari.apk` from **whatever ref was dispatched** |

Development happens on `Downloader`, so a normal push publishes *nothing the
user can reach*: the `continuous` release and the releases feed keep serving the
older `main` line. That is exactly the trap that made "every build" look
identical.

## The rule

Every round that changes user-visible behaviour must end with a **versioned
release** built from the pushed commit:

```bash
# 1. push (branch Downloader only) and wait for the CI run to go green
# 2. publish that same commit as a release
curl -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  https://api.github.com/repos/codegeasse1/hikari/actions/workflows/build.yml/dispatches \
  -d '{"ref":"Downloader","inputs":{"release":"true","version":"0.5.6"}}'
```

`Updater.checkForUpdate()` lists `releases?per_page=10`, skips the `continuous`
tag, takes the newest release and compares dotted versions against
`BuildConfig.VERSION_NAME` — so once `v<version>` exists and its `versionName` is
newer than the installed one, the in-app **Update** button finds it, and it is
also the first download on the releases page. Dispatch only stores the APK in
one asset (`hikari.apk`, ~144 MB), so it costs nothing but a CI run.

Bump `versionCode` **and** `versionName` in `app/build.gradle.kts` and add a
`## <version>` section to `CHANGELOG.md` first — the release body is taken from
that section.

## Notes

- Docs-only pushes (`.md`) do not start CI (`paths-ignore: '**.md'`).
- The dispatched run leaves `main`'s channels alone: every main-gated step is
  skipped when the dispatched ref is `Downloader`, so the `continuous` release
  still holds the last `main` build.
- Release assets are capped at 2 GB; the release APK is ~144 MB.
