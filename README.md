# ModernUserStyles

Aliucord plugin for Discord Android `126.21` / `126021`.

ModernUserStyles backports DiscordRN gradient role colors and custom display names to legacy Aliucord surfaces, including chat, replies, member list, profiles, mentions, autocomplete, DMs, voice, stage, and reaction user lists.

## Features

- Global display names where legacy Discord still shows usernames.
- Display-name fonts from Discord's WOFF2 font assets.
- Gradient role colors from modern role payloads.
- Role color precedence based on the user's highest colored role.
- Settings toggles for each patched surface.

## Build

Initialize submodules before building:

```powershell
git submodule update --init --recursive
```

```powershell
.\gradlew.bat --no-configuration-cache :plugins:ModernUserStyles:make
```

The output is written to:

```text
plugins/ModernUserStyles/build/outputs/ModernUserStyles.zip
```

## Deploy

```powershell
.\gradlew.bat --no-configuration-cache :plugins:ModernUserStyles:deployWithAdb
```

## Notes

This plugin targets Aliucord on Discord Android `126021`. It uses Aliucord patching APIs and Discord's existing authenticated REST/Gateway state where available; it does not copy Discord web client code.

WOFF2 decoder native libraries are compiled from the `khoben/woff2-android` submodule during the Gradle build and are only packaged into the generated plugin zip.

## License

ModernUserStyles is licensed under GPLv3. Third-party license notices are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
