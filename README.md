# 📦 PlayerInvBackup
Paper / Folia 1.21.1+ 玩家背包备份插件, 备份范围为玩家背包和末影箱

---

## 🔔 功能特性
- 自动备份, 支持定时与事件触发
- 事件触发支持上线, 下线, 死亡, 切换世界
- 图形界面支持备份列表, 预览, 搜索, 筛选, 快速翻页, 恢复确认
- 预览中可直接点击物品格整组领取
- 背包已满时, 物品会进入待投递队列, 可通过 `/pib pending` 继续领取
- 备份支持置顶显示与备注, 置顶记录不会被自动清理
- 恢复前会校验快照 `SHA-256`
- 提供控制台友好的 `list`, `info`, `lock`, `unlock`, `note`, `status` 命令
- 支持 SQLite, Local, MySQL, H2 四种存储后端
- 支持原生 Bukkit GUI 与 ProtocolLib Packet GUI 自动切换
- 支持自定义 GUI 按钮音效与时间筛选周期
- 支持审计日志

## 🧩 运行环境
基于 Paper 1.21.1 API 开发
- 支持 Paper / Folia `1.21.1+`
- Java `21`
- 可选依赖 `ProtocolLib`

`ProtocolLib` 仅影响 Packet GUI，未安装时插件仍可正常使用, 会自动回退为原生 Bukkit GUI

## ⌨️ 命令
主命令:

- `/playerinvbackup`
- 别名 `/pib` `/invb` `/invbackup`

参数约定:

- `<>` 必填 `[]` 选填

命令列表:

- `/pib open [玩家名/UUID]` 不填参数时打开自己的备份列表, 填写后打开指定玩家
- `/pib backup [玩家名/UUID]` 不填参数时立即备份自己, 填写后备份指定在线玩家
- `/pib pending` 领取待投递物品
- `/pib restore <玩家名/UUID> <备份编号>` 将指定备份恢复到目标玩家
- `/pib list <玩家名/UUID> [页码]` 以命令形式列出备份
- `/pib info <玩家名/UUID> <备份编号>` 查看备份详情
- `/pib lock <玩家名/UUID> <备份编号> [备注]` 置顶显示备份
- `/pib unlock <玩家名/UUID> <备份编号>` 取消置顶显示
- `/pib note <玩家名/UUID> <备份编号> [备注]` 设置或清除备注
- `/pib status` 查看插件运行状态
- `/pib reload` 重载配置与语言文件
- `/pib help` 查看帮助

## 🔐 权限
主权限
- `playerinvbackup.admin`
  - `playerinvbackup.open`
  - `playerinvbackup.backup`
  - `playerinvbackup.self`
  - `playerinvbackup.restore`
  - `playerinvbackup.pending`
  - `playerinvbackup.status`
  - `playerinvbackup.reload`
  - `playerinvbackup.list`
  - `playerinvbackup.info`
  - `playerinvbackup.lock`
  - `playerinvbackup.backup.exempt`

说明
- `/pib` 主命令当前会先检查 `playerinvbackup.admin`
- 也就是说, 普通玩家即使单独拥有子权限, 也不能直接使用 `/pib`
- `playerinvbackup.backup.exempt` 用于免除自动备份, 包括定时与事件触发

### 🔄 自动备份
- `backup.interval-minutes`  
  自动备份间隔, `0` 表示关闭
- `backup.jitter-seconds`  
  错峰秒数, 用于平滑 I/O 写入压力
- `backup.keep-per-player`  
  每名玩家保留的最近备份数量, `0` 表示不按数量清理
- `backup.keep-days`  
  每名玩家保留的最近天数, `0` 表示不按时间清理
- `backup.triggers.join`
- `backup.triggers.quit`
- `backup.triggers.death`
- `backup.triggers.world-change`
- `backup.excluded-worlds` 排除自动备份的世界列表

## 📘 使用说明
- `/pib open` 与 `/pib backup` 都支持无参数默认作用于自己
- `/pib backup [玩家名/UUID]` 只有在填写参数时才会尝试备份指定在线玩家
- 预览界面点击物品格会整组领取
- 如果背包已满, 物品会进入待投递队列
- 已置顶的备份不会被自动清理
- 存在未投递物品的备份也不会被自动清理

目标解析规则

- 在线模式服务器 `online-mode=true`  
  目标离线时, 建议使用 UUID 或服务器已缓存的离线名称
- 离线模式服务器 `online-mode=false`  
  目标离线时可以直接按名字计算离线 UUID

恢复限制

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

本地产物 `build/libs/PlayerInvBackup.jar`

----

## 🌍 bStats
[![bStats](https://bstats.org/signatures/bukkit/PlayerInvBackup.svg)](https://bstats.org/plugin/bukkit/PlayerInvBackup/30660)
