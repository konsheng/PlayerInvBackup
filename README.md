# BayMcBackUp (Paper / Folia)

我的世界服务器玩家背包备份插件（背包含盔甲/副手 + 末影箱）

## 功能

- 自动备份: 定时 + 事件触发 (上线/下线/死亡/切换世界)
- 管理员 GUI: 列表/预览/整组领取/置顶显示/搜索与筛选/二次确认恢复
- 控制台友好命令: `list`/`info`/`lock`/`note` 等, 不进入 GUI 也能查询与管理
- 恢复安全: 恢复前校验 sha256, 发现损坏会阻止写入并提示
- 待投递: 背包满时物品进入待投递队列, 玩家可使用 `pending` 领取

## 环境

- Minecraft: 1.21.x (api-version: 1.21)
- 服务端: Paper 或 Folia
- Java: 21
- 构建: Gradle

### 可选依赖

- ProtocolLib: 提供 Packet GUI（纯发包，可选）
  - 未安装时插件仍可正常启用，GUI 自动使用原生 Bukkit Inventory GUI

## 安装

1. 获取插件:
   - Release: 下载 JAR
   - 自行构建:
     - Windows: `./gradlew.bat clean build`
     - Linux: `./gradlew clean build`
2. 自行构建产物位置: `build/libs/BayMcBackUp-*.jar`
3. 放入服务器 `plugins/`, 启动生成配置: `plugins/BayMcBackUp/config.yml`

## 配置

常用配置 `plugins/BayMcBackUp/config.yml`:

- `language`: 语言文件名 (位于 `plugins/BayMcBackUp/lang/`), 修改后可 `/bmbackup reload`
- `backup.interval-minutes`: 自动备份间隔 (分钟, 0 = 关闭自动备份)
- `backup.jitter-seconds`: 错峰秒数
- `backup.keep-per-player`: 每玩家保留最近 N 份 (0 = 不清理)
- `backup.keep-days`: 每玩家保留最近 N 天 (0 = 不清理)
- `backup.triggers.*`: 事件触发备份开关
- `backup.excluded-worlds`: 排除世界 (定时/事件触发都会跳过)
- `storage.type`: `sqlite` | `local` | `mysql` | `h2`
- `storage.sqlite.file`: SQLite 数据文件路径
- `storage.local.base-path`: 本地文件存储目录
- `storage.mysql.*`: MySQL/MariaDB 连接配置 (支持 `url` 或 `host/port/database` 拼接)
- `storage.h2.*`: H2 文件数据库配置 (支持 `url` 或 `file` 拼接)
- `performance.queue-limit` / `performance.max-writes-per-second`: I/O 队列与写入速率限制
- `gui.mode`: `auto` | `bukkit` | `packet` GUI 界面的生成方式
- `gui.list-page-size`: GUI 列表每页数量
- `sounds.gui.*`: GUI 点击音效

### 保留策略

- `backup.keep-per-player` 与 `backup.keep-days` 可同时启用
- 置顶显示的备份不会被自动清理
- 存在待投递物品的备份不会被自动清理

### GUI 模式

- `gui.mode=auto`（默认）: 自动切换，有 ProtocolLib 则使用 Packet GUI，否则使用原生 GUI
- `gui.mode=bukkit`: 强制使用原生 Bukkit Inventory GUI
- `gui.mode=packet`: 强制使用 Packet GUI，需要 ProtocolLib，缺失或初始化失败会自动降级为原生 GUI

## 语言文件

- 默认语言文件: `plugins/BayMcBackUp/lang/zh_CN.yml`
- 所有游戏内提示与控制台提示均从语言文件读取（例如 `console.*`），需要修改提示文本时直接编辑该文件
- 修改后执行 `/bmbackup reload` 立即生效
- 插件会自动补全缺失的语言键，减少升级后出现“语言文件缺少键”的情况

## 命令

`/bmbackup help` 可查看完整帮助与示例

常用子命令:

- `open <玩家>`: 打开备份列表 GUI
- `list <玩家> [页码]`: 列出备份 (控制台友好)
- `info <玩家> <备份编号>`: 查看备份详情
- `backup`: 为自己立即备份
- `now <玩家>` / `nowall`: 立即备份
- `restore <玩家> <备份编号>`: 恢复到玩家 (Folia 下仅支持在线恢复)
- `pending`: 领取待投递物品
- `lock`/`unlock`/`note`: 置顶显示与备注
- `status`, `reload`

## 权限

- `baymcbackup.admin`: 全部权限 (默认 OP)
- 细分权限:
  - `baymcbackup.open`
  - `baymcbackup.now`
  - `baymcbackup.nowall`
  - `baymcbackup.self`
  - `baymcbackup.restore`
  - `baymcbackup.pending`
  - `baymcbackup.status`
  - `baymcbackup.reload`
  - `baymcbackup.list`
  - `baymcbackup.info`
  - `baymcbackup.lock`
- `baymcbackup.backup.exempt`: 玩家免于自动备份 (定时/事件触发)

## 备份说明

- "置顶显示" 的备份不会参与自动清理，并在列表顶部显示
- 在线模式服务器（`online-mode=true`）：目标离线时只能使用 UUID 或服务器已缓存的离线名，未缓存请使用 UUID
- 离线模式服务器（`online-mode=false`）：目标离线时可直接使用名字
