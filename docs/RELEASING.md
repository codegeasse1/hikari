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
| Publish main release | `workflow_dispatch` + `release=true` **+ `confirm_release=CONFIRM-RELEASE`** | `v<version>/hikari.apk`, with the release body taken from that version's `CHANGELOG.md` section |

So a push to `main` already refreshes the `continuous` test channel, but a push
alone creates **no** versioned release.

## The rule

**A push to `main` publishes `continuous` and NOTHING ELSE. Do not create a main
release unless the owner has explicitly asked for one in the current
conversation.** The owner has said this more than once, and a main release
cannot be undone silently: it becomes the version `Updater` offers every install
and the first download on the releases page.

The main-release step is therefore gated TWICE (see the header of
`.github/workflows/build.yml`): the `release` checkbox AND a `confirm_release`
input that must be typed as exactly `CONFIRM-RELEASE`. A dispatch with
`release=true` but no matching `confirm_release` logs a warning and publishes
nothing — which is what makes an accidental dispatch harmless.

Only when the owner asks for a release, and only from a commit whose CI run is
green:

```bash
curl -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  https://api.github.com/repos/codegeasse1/hikari/actions/workflows/build.yml/dispatches \
  -d '{"ref":"main","inputs":{"release":"true","confirm_release":"CONFIRM-RELEASE","version":"0.6.6"}}'
```

`Updater.checkForUpdate()` lists `releases?per_page=10`, skips the `continuous`
tag, takes the newest release and compares dotted versions against
`BuildConfig.VERSION_NAME` — so once `v<version>` exists and its `versionName` is
newer than the installed one, the in-app **Update** button finds it, and it is
also the first download on the releases page. A dispatch stores three assets per
release (`hikari.apk` universal, plus `hikari-arm64-v8a.apk` and
`hikari-armeabi-v7a.apk`).

Bump `versionCode` **and** `versionName` in `app/build.gradle.kts` and add a
`## <version>` section to `CHANGELOG.md` before any release — the release body is
taken from that section.

If a main release happens by mistake, delete the RELEASE and its TAG
(`DELETE /repos/.../releases/<id>` and `DELETE /repos/.../git/refs/tags/v<version>`),
then confirm no non-prerelease release is newer than the version that was
intended to be the live one.

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
