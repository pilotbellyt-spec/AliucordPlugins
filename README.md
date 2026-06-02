# Aliucord plugins

A small set of Aliucord plugins for Discord Android `126.21` / `126021`.

## Plugins

| Plugin | What it does |
| --- | --- |
| ModernUserStyles | Gradient role colors and styled display names. |
| MessageBookmarks | Bookmarks/reminders for messages. Has local mode and an optional sync mode. |
| MessageRequests | Accept or deny Discord message requests on Aliucord. |
| IgnoreFeature | Adds Ignore/Unignore to user profiles and hides ignored users in chat. |
| ServerSettingsFix | Fixes a few broken server settings screens on Aliucord. |
| JumpToTop | Adds a jump-to-top button inside forum posts. |

## Build

Build all plugins:

```powershell
.\gradlew.bat --no-configuration-cache make
```

Build one plugin:

```powershell
.\gradlew.bat --no-configuration-cache :plugins:ModernUserStyles:make
.\gradlew.bat --no-configuration-cache :plugins:MessageBookmarks:make
.\gradlew.bat --no-configuration-cache :plugins:MessageRequests:make
.\gradlew.bat --no-configuration-cache :plugins:IgnoreFeature:make
.\gradlew.bat --no-configuration-cache :plugins:ServerSettingsFix:make
.\gradlew.bat --no-configuration-cache :plugins:JumpToTop:make
```

The zip for each plugin ends up in that plugin's `build/outputs` folder.

## Deploy

```powershell
.\gradlew.bat --no-configuration-cache :plugins:ModernUserStyles:deployWithAdb
.\gradlew.bat --no-configuration-cache :plugins:MessageBookmarks:deployWithAdb
.\gradlew.bat --no-configuration-cache :plugins:MessageRequests:deployWithAdb
.\gradlew.bat --no-configuration-cache :plugins:IgnoreFeature:deployWithAdb
.\gradlew.bat --no-configuration-cache :plugins:ServerSettingsFix:deployWithAdb
.\gradlew.bat --no-configuration-cache :plugins:JumpToTop:deployWithAdb
```

## GitHub Actions

`main` builds the plugins and updates the `builds` branch.

## Notes

These are for Aliucord on Discord Android `126021`. Some features call Discord's normal authenticated REST/Gateway routes when the old app has no local state for them.

## License

This repo is licensed under GPLv3. Third-party license notices are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
