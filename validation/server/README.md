# 小镇手册自动开服验证

参考 `~/mc_projects/genshin/GenshinArtifactSystem/validation/server/automation`，复用其进程管理、
固定服务端哈希及 Minecraft 1.21.11 协议修正。测试启动独立的 Folia / Leaf 1.21.11，
使用 Mineflayer 真实登录、点击 GUI、发送命令和执行 TUI 的点击链接。

每次使用 `mkdtemp` 创建目录，只监听 `127.0.0.1`，使用新世界、新玩家与本地 SQLite。
不读取现有服的配置或数据库；可复用已下载的服务端 JAR 和运行库。
占用端口会直接报错，不停止已有进程。结束时正常发送 `stop`，强制结束算失败。

需要 JDK 21、Node.js 22+。从仓库根目录执行：

```sh
./gradlew :core:test shadowJar -PBuildFull=true
npm ci --ignore-scripts --prefix validation/server/automation
node validation/server/automation/download.mjs --server folia
node validation/server/automation/run.mjs --accept-eula --server folia
```

`--accept-eula` 表示接受 [Minecraft EULA](https://aka.ms/MinecraftEULA)。Leaf 使用 `--server leaf`。
`--cache <目录>` 复用包含固定哈希 JAR 的缓存；`--libraries <目录>` 复制已有运行库到临时服。
默认端口为 25596，可通过 `--port` 更换。默认读取当前项目版本的 full JAR，也可用 `--jar` 明确指定。

验证内容：

- 不指定语言时，新生成配置为 `zh_cn`；未入镇的非 OP 玩家可以阅读手册。
- 七章内容、GUI 目录和返回、无效章节及异常页码处理。
- 自定义圈选工具、最大边长、阶梯单价和升级任务物品展示。
- 主菜单入口、真实建镇后的手册入口、模板与信任页的上下文帮助。
- 阅读并返回模板页后，权限状态没有改变。
- 切换 TUI 后的章节链接、实际翻页链接、目录返回和直接页码命令。
- 服务端正常退出，日志没有异常或线程检查错误。

输出路径打印为 `RUN_DIR` / `RESULTS`。临时目录保留 `environment.json`（JAR 哈希）、
`results.json`、`ui-snapshots.json`（客户端收到的界面物品）、`chat.json` 和服务端日志，便于复查。

本套件验证手册和相关导航，不代表已完成跨服代理、MySQL 并发或所有权限判定的端到端测试。
数据库事务回归由现有 JUnit 测试覆盖；手册专项 JUnit 校验四语条目、动态配置替换及文本分页。
