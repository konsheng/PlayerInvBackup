# PlayerInvBackup

[English](README.md)

Paper / Folia `1.21.1+` 玩家备份插件，备份范围包括玩家背包、末影箱和经验值

---

## 🔂 功能特性
- 支持自动备份，包含定时触发与事件触发
- 事件触发支持上线、下线、死亡、切换世界
- 支持 GUI 备份列表、预览、搜索、筛选、快速翻页与恢复确认
- 预览界面可直接整组领取物品
- 预览界面底部提供经验瓶按钮，可单独恢复备份中的经验值
- 背包已满时，物品会进入待投递队列，可通过 `/pib pending` 继续领取
- 备份支持置顶显示与备注；置顶备份不会被自动清理
- 恢复前会自动创建保底备份，并校验快照 `SHA-256`
- 支持控制台友好的 `list`、`info`、`lock`、`unlock`、`note`、`status`、`backupall` 等命令
- 支持 `SQLite`、`Local`、`MySQL`、`PostgreSQL`、`H2` 五种存储后端
- 支持原生 Bukkit GUI 与 ProtocolLib Packet GUI 自动切换
- 支持自定义 GUI 按钮音效与时间筛选周期
- 支持审计日志

## 🌋 运行环境
基于 Paper API 开发：
- 支持 Paper / Folia `1.21.1+`
- Java `21`
- 可选依赖：`ProtocolLib`

`ProtocolLib` 只影响 Packet GUI；未安装时插件仍可正常使用，会自动回退为原生 Bukkit GUI。

## 🖥️ 服务端支持情况

| 服务端                | 支持情况  |
|--------------------|-------|
| Paper 1.21.1+      | ✅ 支持  |
| Folia 1.21.1+      | ✅ 支持  |
| Leaf 1.21.1+       | ✅ 支持  |
| PurPur 1.21.1+     | ✅ 支持  |
| Pufferfish 1.21.1+ | ✅ 支持  |
| Spigot             | ❌ 不支持 |
| CraftBukkit        | ❌ 不支持 |

其他类型的服务端尚未经过完整测试，请自行甄别

## ⌨️ 命令
主命令：

- `/playerinvbackup`
- 别名：`/pib`、`/invb`、`/invbackup`

参数约定：

- `<>` 必填 `[]` 选填

命令列表：

- `/pib open [玩家名]`  
  不填参数时打开自己的备份列表；填写后打开指定玩家的备份列表
- `/pib backup [玩家名]`  
  不填参数时立即备份自己；填写后备份指定在线玩家
- `/pib backupall`  
  为当前所有在线玩家创建一轮批量备份任务
- `/pib pending`  
  领取待投递物品
- `/pib restore <玩家名> <备份编号>`  
  将指定备份恢复到目标在线玩家
- `/pib list <玩家名> [页码]`  
  以命令形式列出备份
- `/pib info <玩家名> <备份编号>`  
  查看备份详情
- `/pib lock <玩家名> <备份编号> [备注]`  
  置顶备份，并可顺带写入备注
- `/pib unlock <玩家名> <备份编号>`  
  取消置顶
- `/pib note <玩家名> <备份编号> [备注]`  
  设置或清除备注
- `/pib status`  
  查看插件运行状态
- `/pib reload`  
  重载配置与语言文件
- `/pib help`  
  查看帮助
- `/pib tips`  
  查看使用提示

## 📝 使用说明
- `/pib open` 与 `/pib backup` 都支持无参数默认作用于自己
- `/pib backupall` 同一时间只允许一个批量备份任务运行；运行中会定期提示进度
- `/pib backupall` 完成时会输出成功、跳过、失败和耗时汇总
- 预览界面点击物品格子会整组领取
- 预览界面底部经验瓶可打开“恢复经验值”独立确认框；确认后只恢复经验值，不会同时恢复物品
- 旧备份如果不包含经验值数据，会在 GUI 中显示为不可单独恢复经验值
- 如果背包已满，物品会进入待投递队列
- 已置顶的备份不会被自动清理
- 存在未投递物品的备份也不会被自动清理
- 默认配置下 `keep-per-player = 0`、`keep-days = 0`，不会自动删除旧备份
- 首次生成配置文件时，中文环境会释放中文配置模板，其他环境默认释放英文配置模板

目标解析规则：

- 在线模式服务器（`online-mode=true`）  
  目标离线时，建议使用 UUID 或服务器已缓存的离线名称
- 离线模式服务器（`online-mode=false`）  
  目标离线时可以按名字计算离线 UUID

恢复限制：

- 当前恢复实现要求目标玩家在线
- `GUI` 恢复与 `/pib restore` 都基于在线 `Player` 实体执行

## 🛠️ 构建
Windows
```powershell
./gradlew.bat clean build
```

Linux
```bash
./gradlew clean build
```

本地产物：

- `build/libs/PlayerInvBackup.jar`

----

## 📊 bStats
[![bStats](https://bstats.org/signatures/bukkit/PlayerInvBackup.svg)](https://bstats.org/plugin/bukkit/PlayerInvBackup/30660)
