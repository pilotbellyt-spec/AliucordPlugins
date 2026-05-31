# AliucordPlugins

My Aliucord plugins for legacy Discord Android `126.21` / `126021`.

## Plugins

### ModernUserStyles

Backport of gradient user roles & custom display names features from DiscordRN.

- Global display names where legacy Discord still shows usernames.
- Display-name fonts from Discord's WOFF2 font assets.
- Gradient and holographic role colors from modern role payloads.
- Role color precedence based on the user's highest colored role.
- Settings toggles for patched surfaces.

### MessageBookmarks

Backport of Discord message bookmarks and reminders.

- Local bookmarks and reminders by default.
- Optional sync mode for Discord's saved-message API if your account has access.
- Bookmark/reminder actions in the message action sheet.
- Bookmarks view from Recent Mentions.
- In-app reminder notices while Aliucord is open and Android notifications while it is not.

## Build

Initialize submodules before building:

```powershell
git submodule update --init --recursive
```

Build all plugins:

```powershell
.\gradlew.bat --no-configuration-cache make
```

Build one plugin:

```powershell
.\gradlew.bat --no-configuration-cache :plugins:ModernUserStyles:make
.\gradlew.bat --no-configuration-cache :plugins:MessageBookmarks:make
```

Plugin zips are written to each plugin's `build/outputs` folder.

## Deploy

```powershell
.\gradlew.bat --no-configuration-cache :plugins:ModernUserStyles:deployWithAdb
.\gradlew.bat --no-configuration-cache :plugins:MessageBookmarks:deployWithAdb
```

## GitHub Actions

A push to `main` builds every plugin and publishes the generated zips plus `updater.json` to the `builds` branch.

## Notes

These plugins target Aliucord on Discord Android `126021`. They use Aliucord patching APIs and Discord's existing authenticated REST/Gateway state where available; they do not copy Discord web client code.

ModernUserStyles compiles the WOFF2 decoder from the `khoben/woff2-android` submodule during Gradle builds. Native libraries are only packaged into the generated plugin zip.

## License

This repo is licensed under GPLv3. Third-party license notices are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
