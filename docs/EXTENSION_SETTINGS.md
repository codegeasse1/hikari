# Extension settings (the gear)

Every installed extension row has a settings gear. What that gear *does* is
decided in exactly one place — `openProviderSettings` in
`ui/screens/ExtensionsScreen.kt` — because every engine Hikari supports declares
its settings differently, and answering all of them with one generic dialog is
what produced the old lie ("addons have no settings of their own") over addons
that really do have a settings page.

## How the gear decides

| Provider | What the gear opens | Where the settings live |
|---|---|---|
| `STREMIO` | the addon's own configuration page, in a WebView (`WebViewActivity` with the `stremioConfig` extra) | the addon's own server, addressed by `behaviorHints.configurationURL`; the manifest the page hands back is installed as the addon |
| `CS3` | the plugin's own settings screen (an Activity/BottomSheetDialogFragment) | the plugin's own storage, through `Plugin.openSettings` |
| `NUVIO` | the schema dialog, from the provider's `onSettings()` | `NuvioRuntime.settingsFile(id)` |
| `VEGA` | the schema dialog, from the provider's `settings.js` (`getSettingsSchema`) | the provider's own `kv.json` (its `providerContext.kvStore`) |
| `ANIYOMI`, `MANGA` | the schema dialog, from the extension's own `setupPreferenceScreen` tree | the extension's own SharedPreferences, written through its own `Preference` objects |
| `IPTV` | what the playlist IS: source, channel count, re-read | — |
| `HIKARI`, `UNIVERSAL`, `SKYSTREAM`, anything else | the honest info dialog: which kind of extension this is, where it came from, and that its format has no settings screen | — |

`hasSettingsScreen()` decides whether the gear is DRAWN at all, and it must stay
cheap — the rows are built from stored data and the provider runtime may not be
loaded. A CS3 plugin's answer comes from the `settingsReady` **cache**
(`Cs3PluginManager.hasSettings`; never `settingsAvailable`, which dex-loads the
plugin and would ANR on the tap's thread). A Vega provider's is a directory
listing (`VegaPluginManager.hasSettings`). A Stremio addon's is the
`StremioAddon.configPages`/`noConfigPage` pair, which is filled in wherever a
manifest is read — `StremioAddon.noteConfiguration` is called by
`loadManifest()` and by the install path (`ExtensionsViewModel.addStremio`), so
an addon's answer is known from the moment it is added. Aniyomi/Manga always
offer the gear: whether an extension declares a preference screen is only
knowable by loading it, and an extension that HAS settings must not lose its
button to save that lookup.

## Stremio addons

`StremioAddon.configurationPage()` answers the addon's config page or null:

- `behaviorHints.configurationURL` (absolute, or relative to the addon's base), else
- `<base>/configure` when the manifest only sets `behaviorHints.configurable`.

`WebViewActivity` runs that page with the `stremioConfig` extra, which (a)
switches off the main-frame redirect protection (a config wizard legitimately
navigates between its own hosts) and (b) turns the navigation the page ends on
into the install signal: `addonInstallUrl()` recognises both spellings a config
page uses — `stremio://<host>/<path>/manifest.json` (and the older
percent-encoded `stremio://https%3A%2F%2F…`) and a plain link to a
`…/manifest.json` — and `finishWithManifest()` returns it as an activity
result. The Extensions screen's `addonConfigLauncher` then installs it with
`vm.addStremio(url)`, which is the same path a pasted addon URL takes, so the
id, the name, the icon and the replace-in-place rule are all the shared ones.

Nothing about a config page is scraped or re-implemented: the page IS the
settings screen, and the addon it produces is what gets installed.

## Aniyomi / Manga extensions

`data/ExtensionPreferences.kt` is the whole implementation, and the important
part is that it maps the extension's own `androidx.preference` tree — it does
not invent a schema:

- `load()` instantiates the source (`AniyomiExtensionManager.extensionOf` /
  `MangaExtensionManager.sourceOf`), checks it for `ConfigurableAnimeSource` /
  `ConfigurableSource`, builds a `PreferenceScreen` with
  `PreferenceManager(context).createPreferenceScreen(context)`, and lets the
  extension's own `setupPreferenceScreen` fill it. The tree is then mapped onto
  the JSON the app's `SchemaDialog` draws: `TwoStatePreference` → toggle,
  `ListPreference` → select, `MultiSelectListPreference` → multi (carried as a
  comma-joined string), `SeekBarPreference` → numeric text, `EditTextPreference`
  → text (with `isPassword` from its `inputType`), `PreferenceCategory` →
  header, a keyless `Preference` → info.
- **Values come from the preference's own getter** (`isChecked`/`value`/`text`),
  never from a raw `SharedPreferences.getString` — an extension that stores an
  Int under a key would otherwise throw `ClassCastException` on read.
- `save()` rebuilds the tree and pushes each value through
  `callChangeListener(value)` and then the setter, exactly as the preference's
  own dialog does. That is what makes an extension's own listeners run (a text
  field validated into an `Int`, a switch that rewrites a dependent key).

An extension whose `setupPreferenceScreen` throws (its own `R.string` cannot be
resolved against Hikari's context, or a preference wants a real Activity) is
caught and falls back to the info dialog — the gear must never crash the screen
that opened it.

## Vega providers

A Vega provider's `settings.js` exports `getSettingsSchema({providerContext})`
and returns `{key, type, label, description, defaultValue, options}` fields.
`VegaRuntime.settingsSchema()` runs it like any other provider call (one QuickJS
engine, `__vegaCall("getSettingsSchema", "{}")`), and `savedSettings()` /
`saveSettings()` read and write the provider's own `kv.json` — the same file its
`providerContext.kvStore` persists to. Saving anywhere else would show settings
the provider never sees.

## The honest fallback

`ProviderInfoDialog` is what remains when there is genuinely nothing to open: it
names the extension, its kind (`CloudStream plugin` / `Hikari extension` /
`Universal scraper` / `SkyStream extension` / …), its source, and the reason
there is no settings screen. It is reached from the dispatcher (an optimistically
offered gear whose manifest turns out to declare no config page, a Vega provider
whose schema is empty, an Aniyomi extension whose preference tree cannot be
read). Wording matters: the point of the dialog is to be true about *that*
extension, not to claim no extension has settings.
