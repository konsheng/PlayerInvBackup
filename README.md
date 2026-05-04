# PlayerInvBackup

[中文说明](README.zh-CN.md)

Paper / Folia `1.21.1+` player backup plugin, PlayerInvBackup stores player inventory, armor, offhand, ender chest, and experience, then provides GUI and command tools for browsing, claiming, exporting, and restoring backups

## 🔂 Features
- Automatic backups by timer and by player events: join, quit, death, and world change
- Manual self backup, manual target backup, and one-by-one batch backup for all online players
- Backup scope includes inventory storage, hotbar, armor, offhand, ender chest, and experience
- Purpur expanded ender chest support: automatically detects 9-54 ender chest slots and works with `six-rows` and `use-permissions-for-rows`
- GUI backup list with pagination, time filters, trigger filters, backup ID search, date/time range search, refresh, and fast page jumps
- GUI backup preview with inventory / ender chest switching, slot claiming, restore confirmation, standalone experience restore, pending delivery, pin toggle, shulker export, and teleport to backup location
- Shulker export for the current preview view, with separate inventory and ender chest exports
- Pending-delivery queue for items that cannot fit into the operator's inventory during claiming
- Pinned backups and notes; pinned backups are listed first and excluded from automatic cleanup
- SHA-256 snapshot verification and pre-restore safety backup before restore
- Incompatible item protection for claiming, exporting, and restoring old or cross-version data
- Audit logs for sensitive operations such as backup, restore, slot claim, pending delivery, pin, note, and shulker export
- Storage backends: `SQLite`, `Local`, `MySQL`, `PostgreSQL`, and `H2`
- Bukkit native GUI and optional ProtocolLib packet GUI with automatic fallback
- Configurable GUI sounds, language files, time filters, retention, queue limits, and shulker box material
- bStats support

## 🌋 Runtime
- Java `21+`
- Paper API `1.21`
- Recommended server version: Paper / Folia `1.21.1+`
- Optional dependency: `ProtocolLib`

`ProtocolLib` is only used for packet GUI mode; if it is not installed, the plugin still works and falls back to Bukkit native GUI automatically

## 🖥️ Server Support

| Server | Support |
|---|---|
| Paper 1.21.1+ | ✅ Supported |
| Folia 1.21.1+ | ✅ Supported |
| Leaf 1.21.1+ | ✅ Supported |
| Purpur 1.21.1+ | ✅ Supported |
| Pufferfish 1.21.1+ | ✅ Supported |
| Spigot | ❌ Not supported |
| CraftBukkit | ❌ Not supported |

Other server forks have not been fully tested, please evaluate and test them yourself

## 📦 Installation
Download: [GitHub Releases](https://github.com/konsheng/PlayerInvBackup/releases)

- Download the latest `PlayerInvBackup.jar` from the release Assets
- Confirm the server is running Java `21+` and Paper / Folia `1.21.1+`
- Stop the server before replacing or installing the jar
- Put `PlayerInvBackup.jar` into the server `plugins` directory
- Optional: install `ProtocolLib` if you want packet GUI mode
- Start the server once and wait for `plugins/PlayerInvBackup/` files to be generated
- Edit `plugins/PlayerInvBackup/config.yml` as needed
- Run `/pib reload` after editing config and language files, or restart the server
- Grant the required `playerinvbackup.*` permissions to administrators or permission groups

## ⌨️ Commands

Main command:
- `/playerinvbackup`
- Aliases: `/pib`, `/invb`, `/invbackup`

Argument convention:
- `<>` required
- `[]` optional

Command list:
- **`/pib`**<br>
  Permission: none<br>
  Shows plugin information and a clickable help entry
- **`/pib open [player]`**<br>
  Permission: `playerinvbackup.open`<br>
  Opens your own backup list or the specified player's backup list, in-game only
- **`/pib view [player]`**<br>
  Permission: `playerinvbackup.view` for self, plus `playerinvbackup.view.others` for another player<br>
  Opens a read-only backup list and preview, in-game only
- **`/pib backup [player]`**<br>
  Permission: `playerinvbackup.self` without target, `playerinvbackup.backup` with target<br>
  Creates a manual backup for yourself or an online target player
- **`/pib backupall`**<br>
  Permission: `playerinvbackup.backupall`<br>
  Creates one batch backup run for all currently online players
- **`/pib pending`**<br>
  Permission: `playerinvbackup.pending`<br>
  Delivers pending items into your inventory, in-game only
- **`/pib restore <player> <backup id>`**<br>
  Permission: `playerinvbackup.restore`<br>
  Restores the backup to the target online player
- **`/pib list <player> [page]`**<br>
  Permission: `playerinvbackup.list`<br>
  Lists backups in chat, 10 records per page
- **`/pib info <player> <backup id>`**<br>
  Permission: `playerinvbackup.info`<br>
  Shows backup metadata, location, SHA-256, claimed slots, pin state, and note
- **`/pib lock <player> <backup id> [note]`**<br>
  Permission: `playerinvbackup.lock`<br>
  Pins a backup and optionally writes a note
- **`/pib unlock <player> <backup id>`**<br>
  Permission: `playerinvbackup.lock`<br>
  Unpins a backup
- **`/pib note <player> <backup id> [note]`**<br>
  Permission: `playerinvbackup.lock`<br>
  Sets or clears a backup note
- **`/pib status`**<br>
  Permission: `playerinvbackup.status`<br>
  Shows runtime status, storage, GUI mode, audit settings, and queue usage
- **`/pib reload`**<br>
  Permission: `playerinvbackup.reload`<br>
  Reloads config and language files
- **`/pib help`**<br>
  Permission: `playerinvbackup.admin`<br>
  Shows command help
- **`/pib tips`**<br>
  Permission: `playerinvbackup.admin`<br>
  Shows usage tips

## 🔐 Permissions

- **`playerinvbackup.admin`**<br>
  Default: `op`<br>
  Command administration permission, also treated as all command sub-permissions by the plugin permission helper
- **`playerinvbackup.open`**<br>
  Default: `false`<br>
  Open and use the backup GUI
- **`playerinvbackup.view`**<br>
  Default: `false`<br>
  Open a read-only GUI for your own backups
- **`playerinvbackup.view.others`**<br>
  Default: `false`<br>
  Open a read-only GUI for other players' backups, also requires `playerinvbackup.view`
- **`playerinvbackup.backup`**<br>
  Default: `false`<br>
  Manually back up a specified online player
- **`playerinvbackup.backupall`**<br>
  Default: `false`<br>
  Manually back up all online players
- **`playerinvbackup.self`**<br>
  Default: `false`<br>
  Manually back up yourself
- **`playerinvbackup.backup.bypass`**<br>
  Default: `false`<br>
  Bypass self-backup cooldown
- **`playerinvbackup.restore`**<br>
  Default: `false`<br>
  Restore backups to online players
- **`playerinvbackup.export`**<br>
  Default: `false`<br>
  Export backup preview contents to shulker boxes
- **`playerinvbackup.teleport`**<br>
  Default: `false`<br>
  Run the backup location teleport command from the backup preview GUI
- **`playerinvbackup.pending`**<br>
  Default: `false`<br>
  Deliver pending items into your own inventory
- **`playerinvbackup.status`**<br>
  Default: `false`<br>
  View plugin runtime status
- **`playerinvbackup.reload`**<br>
  Default: `false`<br>
  Reload config and language files
- **`playerinvbackup.list`**<br>
  Default: `false`<br>
  List backups by command
- **`playerinvbackup.info`**<br>
  Default: `false`<br>
  View backup details by command
- **`playerinvbackup.lock`**<br>
  Default: `false`<br>
  Pin, unpin, and edit backup notes
- **`playerinvbackup.backup.exempt`**<br>
  Default: `false`<br>
  Exempt a player from automatic backups by timer and events; grant separately when needed

## 🛡️ Data Safety

- Backups store a SHA-256 hash and restore verifies the snapshot before applying it
- Restore creates a pre-restore safety backup first; if that safety backup fails, restore is cancelled
- Restoring inventory and ender chest overwrites the target player's current inventory and ender chest
- Standalone experience restore only overwrites the target player's level, total experience, and progress
- Claimed slots are excluded from restore
- Pinned backups and backups with undelivered items are excluded from automatic cleanup
- Incompatible items are blocked or skipped depending on the operation and config

## 🧾 Audit

Audit records administrator-sensitive operations for later review and troubleshooting

- Records actor, target, backup ID, action type, and operation details
- Covers manual backup, batch backup, restore, slot claim, pending delivery, pin, note, and shulker export
- Can also print audit entries to console
- Supports automatic retention cleanup

## 🛠️ Build

Windows:

```powershell
./gradlew.bat clean build
```

Linux:

```bash
./gradlew clean build
```

Local artifact:

```text
build/libs/PlayerInvBackup.jar
```

## 📄 License

This project is licensed under the GNU General Public License version 3, see [LICENSE](LICENSE)

## 📊 bStats

[![bStats](https://bstats.org/signatures/bukkit/PlayerInvBackup.svg)](https://bstats.org/plugin/bukkit/PlayerInvBackup/30660)
