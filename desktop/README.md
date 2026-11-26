# Hikari for Windows

This is a separate native desktop MVP. It does not wrap or require the Android APK, and it does not change the Android `:app` module.

The first milestone supports Stremio add-on catalogs, searching, title details, and handing direct stream URLs to VLC/MPV or the default browser. Other provider ecosystems and an embedded media player are outside this milestone.

## Build and run

Use Gradle 8.11.1 or newer with JDK 17 or newer:

```sh
gradle :desktop:test
gradle :desktop:run
gradle :desktop:packageMsi
gradle :desktop:packageExe
```

The MSI and EXE installers must be generated on a Windows runner. CI builds both from `windows-latest` and uploads them as workflow artifacts; no Android build or release is changed by that workflow.
