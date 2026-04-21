# PlayerInvBackup

[中文说明](README.zh-CN.md)

Paper / Folia `1.21.1+` player backup plugin. Backup data includes player inventory, ender chest, and experience.

---

## 🔂 Features
- Supports automatic backups with both scheduled and event-based triggers
- Event triggers support join, quit, death, and world change
- Provides GUI backup list, preview, search, filter, fast pagination, and restore confirmation
- Allows claiming a full item stack directly from the preview GUI
- Adds an experience bottle button at the bottom of the preview GUI for standalone experience restore
- When the inventory is full, items go into a pending-delivery queue and can be claimed later with `/pib pending`
- Supports pinned backups and notes; pinned backups are excluded from automatic cleanup
- Creates a safeguard backup before restore and validates snapshot `SHA-256`
- Provides console-friendly commands such as `list`, `info`, `lock`, `unlock`, `note`, `status`, and `backupall`
- Supports five storage backends: `SQLite`, `Local`, `MySQL`, `PostgreSQL`, and `H2`
- Supports automatic fallback between native Bukkit GUI and ProtocolLib packet GUI
- Supports configurable GUI button sounds and time filter presets
- Supports audit logging

## 🌋 Runtime Environment
Built against the Paper API:
- Supports Paper / Folia `1.21.1+`
- Java `21`
- Optional dependency: `ProtocolLib`

`ProtocolLib` only affects the packet GUI. If it is not installed, the plugin still works normally and falls back to the native Bukkit GUI automatically.

## 🖥️ Server Support

| Server             | Support         |
|--------------------|-----------------|
| Paper 1.21.1+      | ✅ Supported     |
| Folia 1.21.1+      | ✅ Supported     |
| Leaf 1.21.1+       | ✅ Supported     |
| PurPur 1.21.1+     | ✅ Supported     |
| Pufferfish 1.21.1+ | ✅ Supported     |
| Spigot             | ❌ Not supported |
| CraftBukkit        | ❌ Not supported |

Other server types have not been fully tested. Evaluate compatibility yourself.

## ⌨️ Commands
Main command:

- `/playerinvbackup`
- Aliases: `/pib`, `/invb`, `/invbackup`

Argument convention:

- `<>` required `[]` optional

Command list:

- `/pib open [player name]`  
  Opens your own backup list when omitted; otherwise opens the specified player's backup list
- `/pib backup [player name]`  
  Backs up yourself when omitted; otherwise backs up the specified online player
- `/pib backupall`  
  Creates one batch backup run for all currently online players
- `/pib pending`  
  Claims pending-delivery items
- `/pib restore <player name> <backup id>`  
  Restores the specified backup to the target online player
- `/pib list <player name> [page]`  
  Lists backups in command form
- `/pib info <player name> <backup id>`  
  Shows backup details
- `/pib lock <player name> <backup id> [note]`  
  Pins a backup and optionally writes a note
- `/pib unlock <player name> <backup id>`  
  Unpins a backup
- `/pib note <player name> <backup id> [note]`  
  Sets or clears a note
- `/pib status`  
  Shows plugin runtime status
- `/pib reload`  
  Reloads config and language files
- `/pib help`  
  Shows help
- `/pib tips`  
  Shows usage tips

## 📝 Usage Notes
- `/pib open` and `/pib backup` both default to self when called without arguments
- `/pib backupall` only allows one batch backup task at a time and periodically reports progress while running
- `/pib backupall` reports success, skipped, failed, and elapsed time when finished
- Clicking an item slot in the preview GUI claims the whole stack
- The experience bottle in the preview GUI opens a dedicated experience-restore confirmation screen; confirming restores only experience and does not restore items at the same time
- Old backups that do not contain experience data are shown in GUI as unavailable for standalone experience restore
- If the inventory is full, items are moved into the pending-delivery queue
- Pinned backups are not automatically cleaned up
- Backups with undelivered items are also excluded from automatic cleanup
- By default, `keep-per-player = 0` and `keep-days = 0`, so old backups are not deleted automatically
- On first config generation, Chinese environments receive the Chinese config template; all other environments receive the English config template by default

Target resolution rules:

- Online-mode server (`online-mode=true`)  
  If the target is offline, prefer UUID or an offline name already cached by the server
- Offline-mode server (`online-mode=false`)  
  If the target is offline, the plugin can calculate the offline UUID from the player name

Restore limitations:

- Current restore implementation requires the target player to be online
- Both GUIS restore and `/pib restore` are executed against an online `Player` entity

## 🛠️ Build
Windows
```powershell
./gradlew.bat clean build
```

Linux
```bash
./gradlew clean build
```

Local artifact:

- `build/libs/PlayerInvBackup.jar`

----

## 📊 bStats
[![bStats](https://bstats.org/signatures/bukkit/PlayerInvBackup.svg)](https://bstats.org/plugin/bukkit/PlayerInvBackup/30660)
