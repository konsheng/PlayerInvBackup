# PlayerInvBackup

[English](README.md)

Paper / Folia `1.21.1+` 玩家备份插件，PlayerInvBackup 会备份玩家物品栏、盔甲、副手、末影箱和经验值，并提供 GUI 与命令用于浏览、领取、导出和恢复备份

## 🔂 功能特性
- 支持定时自动备份，以及上线、下线、死亡、切换世界事件备份
- 支持玩家自助备份、管理员手动备份指定在线玩家、批量备份全部在线玩家
- 备份范围包含物品栏、快捷栏、盔甲、副手、末影箱和经验值
- 支持 Purpur 扩展末影箱：自动识别 9～54 格末影箱容量，兼容 six-rows 与 use-permissions-for-rows
- GUI 备份列表支持分页、时间筛选、触发器筛选、备份编号搜索、日期/时间范围搜索、刷新和快速翻页
- GUI 备份预览支持背包 / 末影箱切换、单格整组领取、恢复确认、单独恢复经验值、待投递领取、置顶切换、潜影盒导出和传送到备份位置
- 支持按当前预览视图导出到潜影盒，背包和末影箱分开导出
- 背包空间不足时，领取失败的物品会进入待投递队列
- 支持备份置顶和备注；置顶备份会排在列表顶部，并跳过自动清理
- 恢复前自动创建保底备份，并校验备份快照 `SHA-256`
- 对旧版本或跨版本不兼容物品提供领取、导出、恢复保护
- 支持审计日志，记录备份、恢复、领取、待投递、置顶、备注、潜影盒导出等关键操作
- 支持异步检查 GitHub Releases 新版本，并在控制台和管理员进服时提醒
- 支持 `SQLite`、`Local`、`MySQL`、`PostgreSQL`、`H2` 五种存储后端
- 支持 Bukkit 原生 GUI 与可选 ProtocolLib Packet GUI，并可自动回退
- 支持自定义 GUI 音效、语言文件、时间筛选项、保留策略、队列限制和潜影盒材质
- 支持 bStats 统计

## 🌋 运行环境
- Java `21+`
- Paper API `1.21`
- 推荐服务端版本：Paper / Folia `1.21.1+`
- 可选依赖：`ProtocolLib`

`ProtocolLib` 只用于 Packet GUI 模式；未安装时插件仍可正常运行，并会自动回退为 Bukkit 原生 GUI

## 🖥️ 服务端支持

| 服务端 | 支持情况 |
|---|---|
| Paper 1.21.1+ | ✅ 支持 |
| Folia 1.21.1+ | ✅ 支持 |
| Leaf 1.21.1+ | ✅ 支持 |
| Purpur 1.21.1+ | ✅ 支持 |
| Pufferfish 1.21.1+ | ✅ 支持 |
| Spigot | ❌ 不支持 |
| CraftBukkit | ❌ 不支持 |

其他服务端分支尚未完整测试，请自行研究测试

## 📦 安装
下载地址：[GitHub Releases](https://github.com/konsheng/PlayerInvBackup/releases)

- 在 Releases 的 Assets 中下载最新版 `PlayerInvBackup.jar`
- 确认服务端使用 Java `21+`，并运行 Paper / Folia `1.21.1+`
- 安装或替换 jar 前先关闭服务端
- 将 `PlayerInvBackup.jar` 放入服务端 `plugins` 目录
- 如需使用 Packet GUI，可额外安装 `ProtocolLib`
- 启动一次服务端，等待生成 `plugins/PlayerInvBackup/` 目录和默认文件
- 按需修改 `plugins/PlayerInvBackup/config.yml`
- 修改配置或语言文件后执行 `/pib reload`，也可以重启服务端
- 给管理员或权限组分配需要的 `playerinvbackup.*` 权限

## ⌨️ 命令

主命令：
- `/playerinvbackup`
- 别名：`/pib`、`/invb`、`/invbackup`

参数约定：
- `<>` 必填
- `[]` 选填

命令列表：
- **`/pib`**<br>
  权限：无<br>
  显示插件信息和可点击的帮助入口
- **`/pib open [玩家名]`**<br>
  权限：`playerinvbackup.open`<br>
  打开自己的备份列表或指定玩家的备份列表，需要游戏内执行
- **`/pib view [玩家名]`**<br>
  权限：查看自己需要 `playerinvbackup.view`，查看其他玩家还需要 `playerinvbackup.view.others`<br>
  以只读模式打开备份列表和预览，需要游戏内执行
- **`/pib backup [玩家名]`**<br>
  权限：不带目标需要 `playerinvbackup.self`，带目标需要 `playerinvbackup.backup`<br>
  立即备份自己或指定在线玩家
- **`/pib backupall`**<br>
  权限：`playerinvbackup.backupall`<br>
  为当前所有在线玩家创建一轮批量备份
- **`/pib pending`**<br>
  权限：`playerinvbackup.pending`<br>
  将待投递物品发放到自己的背包，需要游戏内执行
- **`/pib restore <玩家名> <备份编号>`**<br>
  权限：`playerinvbackup.restore`<br>
  将指定备份恢复到目标在线玩家
- **`/pib list <玩家名> [页码]`**<br>
  权限：`playerinvbackup.list`<br>
  以聊天文本列出备份，每页 10 条
- **`/pib info <玩家名> <备份编号>`**<br>
  权限：`playerinvbackup.info`<br>
  查看备份元数据、位置、SHA-256、已领取槽位、置顶状态和备注
- **`/pib lock <玩家名> <备份编号> [备注]`**<br>
  权限：`playerinvbackup.lock`<br>
  置顶备份，并可同时写入备注
- **`/pib unlock <玩家名> <备份编号>`**<br>
  权限：`playerinvbackup.lock`<br>
  取消置顶备份
- **`/pib note <玩家名> <备份编号> [备注]`**<br>
  权限：`playerinvbackup.lock`<br>
  设置或清除备份备注
- **`/pib status`**<br>
  权限：`playerinvbackup.status`<br>
  查看运行状态、存储、GUI 模式、审计配置和队列使用情况
- **`/pib reload`**<br>
  权限：`playerinvbackup.reload`<br>
  重载配置和语言文件
- **`/pib help`**<br>
  权限：`playerinvbackup.admin`<br>
  查看命令帮助
- **`/pib tips`**<br>
  权限：`playerinvbackup.admin`<br>
  查看使用提示

## 🔐 权限

- **`playerinvbackup.admin`**<br>
  默认：`op`<br>
  命令管理权限；插件命令权限校验中会视为拥有全部命令子权限
- **`playerinvbackup.open`**<br>
  默认：`false`<br>
  打开并使用备份 GUI
- **`playerinvbackup.view`**<br>
  默认：`false`<br>
  以只读模式查看自己的备份 GUI
- **`playerinvbackup.view.others`**<br>
  默认：`false`<br>
  以只读模式查看其他玩家的备份 GUI，同时需要 `playerinvbackup.view`
- **`playerinvbackup.backup`**<br>
  默认：`false`<br>
  手动备份指定在线玩家
- **`playerinvbackup.backupall`**<br>
  默认：`false`<br>
  手动备份所有在线玩家
- **`playerinvbackup.self`**<br>
  默认：`false`<br>
  手动备份自己
- **`playerinvbackup.backup.bypass`**<br>
  默认：`false`<br>
  跳过自助备份冷却
- **`playerinvbackup.restore`**<br>
  默认：`false`<br>
  将备份恢复到在线玩家
- **`playerinvbackup.export`**<br>
  默认：`false`<br>
  将备份预览内容导出为潜影盒
- **`playerinvbackup.teleport`**<br>
  默认：`false`<br>
  在备份预览 GUI 中执行传送到备份位置命令
- **`playerinvbackup.pending`**<br>
  默认：`false`<br>
  将待投递物品发放到自己的背包
- **`playerinvbackup.status`**<br>
  默认：`false`<br>
  查看插件运行状态
- **`playerinvbackup.reload`**<br>
  默认：`false`<br>
  重载配置和语言文件
- **`playerinvbackup.list`**<br>
  默认：`false`<br>
  通过命令列出备份
- **`playerinvbackup.info`**<br>
  默认：`false`<br>
  通过命令查看备份详情
- **`playerinvbackup.lock`**<br>
  默认：`false`<br>
  置顶、取消置顶和编辑备份备注
- **`playerinvbackup.backup.exempt`**<br>
  默认：`false`<br>
  让玩家免于定时和事件自动备份；需要时单独授予

## 🛡️ 数据安全

- 每份备份会保存 SHA-256，恢复前会校验快照内容
- 恢复前会先创建保底备份；保底备份失败时会取消恢复
- 恢复背包和末影箱会覆盖目标玩家当前背包和末影箱
- 单独恢复经验值只覆盖目标玩家的等级、总经验和经验条进度
- 已领取槽位不会参与恢复
- 置顶备份和存在未投递物品的备份不会被自动清理
- 不兼容物品会按操作和配置被拦截或跳过

## 🧾 审计

审计用于记录管理员敏感操作，方便后续追溯和排查问题

- 记录操作者、目标玩家、备份编号、操作类型和操作详情
- 覆盖手动备份、批量备份、恢复、槽位领取、待投递、置顶、备注和潜影盒导出
- 可同步输出到控制台
- 支持按保留天数自动清理

## 🛠️ 构建

Windows：

```powershell
./gradlew.bat clean build
```

Linux：

```bash
./gradlew clean build
```

本地产物：

```text
build/libs/PlayerInvBackup.jar
```

## 📄 许可证

本项目使用 GNU General Public License version 3，详情见 [LICENSE](LICENSE)

## 📊 bStats

[![bStats](https://bstats.org/signatures/bukkit/PlayerInvBackup.svg)](https://bstats.org/plugin/bukkit/PlayerInvBackup/30660)
