# 游戏内手册验证记录

2026-09-19，Dominion 5.1.11-release。

完成以下验证后，仅将发布版本号更新为 `5.2.0-release`，重新构建 full JAR 并核对包内 `plugin.yml`。
手册实现未再改动；以下运行记录与哈希仍对应实际接受端到端测试的 5.1.11 构建。

构建与单元测试：`./gradlew :core:test shadowJar -PBuildFull=true --offline --console=plain` 成功。
39 项测试全部通过，其中手册专项 7 项；包含四语资源完整性、配置值、物品任务、变量替换、换行与页码。

真实客户端：Folia 1.21.11 build 14 和 Leaf 1.21.11 build 178 各 13 项全部通过。
使用 Node.js 24.3.0、JDK 21、Mineflayer 4.39.0，两个独立回环端口与临时 SQLite 服务器。
两个服务端均正常停止，无强制终止；日志扫描未发现异常。

- Folia 原始证据：`/tmp/dominion-guide-folia-aWL7Cg/results.json`
- Leaf 原始证据：`/tmp/dominion-guide-leaf-smfmmJ/results.json`
- 同目录包含 `environment.json`、`ui-snapshots.json`、`chat.json` 和完整服务端日志。

被测 full JAR SHA-256：

```text
96b4fb061ecebdc0a2eee69d4255811ee5a9ec755098c0207669850ab5505647
```

验证了未入镇玩家访问、默认中文、七章内容、实际配置替换、异常输入、主菜单往返、
真实建镇后模板和信任页面的本页帮助，以及 TUI 章节链接、翻页和返回目录。
模板页的权限卡片在阅读前后保持一致；没有执行权限修改、归还、删除或解散操作。

过程中修复了数字与命令单词被断行的问题，并为手册翻页按钮补齐中文等四语文本。
测试使用 Java 版协议客户端；未进行基岩客户端、真实代理跨服和 MySQL 并发测试。
界面记录来自客户端物品组件，尚未做人工截图验收。
