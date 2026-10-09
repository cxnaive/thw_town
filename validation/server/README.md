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
`--search` 额外执行小镇与领地搜索场景：中文部分／顺序字符匹配、排序、分页、聊天输入、
清除和取消、普通玩家范围、管理员失权、跨服位置提示及已删除结果的点击。
该选项仅在临时测试服对测试命令放宽聊天刷屏限制，避免高频自动化操作被当成真人刷屏踢出。

验证内容：

- 不指定语言时，新生成配置为 `zh_cn`；未入镇的非 OP 玩家可以阅读手册。
- 七章逐节阅读、每页一个完整主题、前后翻节、下一章、目录返回及异常页码处理。
- 自定义圈选工具、最大边长、阶梯单价和升级任务物品展示。
- 主菜单入口、真实建镇后的手册入口、模板与信任页的上下文帮助。
- 阅读并返回模板页后，权限状态没有改变。
- 切换 TUI 后的章节链接、实际翻页链接、目录返回和直接页码命令。
- GUI/TUI 的小镇属地与旧领地列表中，建镇及直接新建属地显示创建者，挂靠属地显示贡献者。
  临时数据库样例覆盖本人及他人创建的普通属地，避免仅凭 `claim_kind` 错误区分来源。
- “个人权限预设（旧领地）”入口及手动套用说明；建镇／普通领地类型的中文显示。
- 小镇信任及属地信任的中文范围、权限类别和额外授权状态；实际逐项点击四个开关，
  查询临时数据库确认只改变对应类别；聊天菜单点击中文权限按钮也能切换对应设置。
- 服务端正常退出，日志没有异常或线程检查错误。

输出路径打印为 `RUN_DIR` / `RESULTS`。临时目录保留 `environment.json`（JAR 哈希）、
`results.json`、`ui-snapshots.json`（客户端收到的界面物品）、`chat.json` 和服务端日志，便于复查。

本套件验证手册和相关导航，不代表已完成跨服代理、MySQL 并发或所有权限判定的端到端测试。
数据库事务回归由现有 JUnit 测试覆盖；手册专项 JUnit 校验四语条目、动态配置替换及文本分页。

## 更新已有服务器的文案

本次没有新增语言文件检测或自动删除逻辑。旧文件已有的正文和按钮说明会继续保留。
使用默认中文的服务器，可在停服替换 JAR 时手动删除以下三个文件，再启动以生成新版：

- `plugins/Dominion/languages/zh_cn.yml`：手册正文、通知和动态名称。
- `plugins/Dominion/languages/cui/zh_cn.yml`：箱子菜单名称、说明和状态。
- `plugins/Dominion/languages/tui/zh_cn.yml`：聊天菜单名称、说明和状态。

无需删除 `config.yml` 或数据库。若使用其他语言，将文件名替换为对应语言即可。

## Folia 26.2 升级验证

26.2 需要 **Java 25**。构建新增 `versions:v26_2`，固定 Paper 开发包
`26.2.build.124-stable`；服务端固定 Folia 26.2 build 7 并校验 SHA-256。

先构建插件及原生客户端、测试夹具，再运行：

```sh
./gradlew :core:test :shadowJar -PBuildFull=true
./gradlew -p validation/client26_2 installDist
./gradlew -p validation/server/fixture jar
node validation/server/automation/download.mjs --server folia26_2
node validation/server/automation/native26.mjs --accept-eula
node validation/server/automation/run.mjs --accept-eula --server folia26_2 --search
node validation/server/automation/teleport.mjs --accept-eula --server folia26_2
```

`native26.mjs` 使用固定 `MCProtocolLib 26.2-20260907.143312-19`，握手协议 **776**，
直接连接 26.2 服务端。该套件**不安装 ViaVersion / ViaBackwards**。客户端真实发送命令、
开箱、物品点击、实体交互和攻击数据包，并接收实际的容器、组件及展示实体数据。
它是协议客户端，没有官方客户端的图形渲染，因此不能替代视觉或材质验收。

夹具只负责建立隔离场景和读取实际方块、物品、生命值等状态，不模拟 Bukkit 事件。
必须存在临时目录标记并绑定 `127.0.0.1` 才启用，命令仅接受本地控制台调用。
插件 JAR 版本变化时，夹具构建可指定 `-PdominionJar=/绝对路径/插件.jar`；
原生测试运行可指定 `--jar /绝对路径/插件.jar`。证据包括 `environment.json`、
`results.json`、`native-trace.json`、夹具读取结果及完整服务端日志。
权限写入后，测试等待正常的保护缓存更新，再执行访问，覆盖实际缓存行为。

`run.mjs` 和 `teleport.mjs` 的 `folia26_2` 配置使用 1.21.11 Mineflayer 客户端，经固定的
ViaVersion / ViaBackwards 5.12.1 转换协议，用于复用完整的菜单、搜索和传送回归。
这些结果单独记录客户端与服务端版本，不计为原生 26.2 客户端验证。

旧 Folia 1.21.11 回归继续使用 `--server folia` 和 Java 21。26.2 实际结果及升级边界见
[FOLIA_26_2_REPORT.md](./FOLIA_26_2_REPORT.md)。
