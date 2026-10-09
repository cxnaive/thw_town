# 玩家手册与属地来源显示验证

2026-09-20，Dominion `5.2.1-release`，本次修改后的 full JAR。

## 改动

- 镇属地列表和旧领地列表的 GUI/TUI 按 `attached_by` 区分来源：直接创建显示创建者，挂靠显示贡献者。
  直接创建的普通属地同样可能是 `NORMAL`，因此不能仅用 `claim_kind` 判断。
  旧领地 GUI 为自建属地显示独立状态，不再标记为已贡献。
- 手册每页显示一个完整小节，支持上一节、下一节、下一章和返回原页面。
  四语正文从基础概念展开；权限继承用住宅区、农场、仓库的连续例子解释模板和单独设置。
- 语言文件由服主管理，本次未添加自动检测、删除或旧正文替换逻辑。
  手动更新路径见 [README](README.md#更新已有服务器的文案)。

## 验证结果

构建命令：`./gradlew :core:test shadowJar -PBuildFull=true --offline --console=plain`。
JUnit 共 76 项：75 项通过，1 项需外部数据库的测试跳过；其中手册专项 8 项通过。
语言资源现已登记为 Gradle 测试输入，修改文案后会重新校验。

Mineflayer 在 Folia 1.21.11 build 14 与 Leaf 1.21.11 build 178 上各通过 15 项场景：

- 默认中文，未入镇玩家可阅读，七章内容逐节展开且只显示当前正文。
- 示例、当前配置与任务材料正确展示，异常页码仍可阅读。
- 上一节、下一节、下一章、目录、上下文返回和聊天按钮可用。
- 从权限模板和信任管理页进入帮助后能返回；阅读前后的模板权限未变化。
- 建镇属地、普通自建属地、挂靠属地，以及本人和他人创建的属地，在 GUI/TUI 两类列表中显示正确来源。

两个测试服均正常停止，未强制终止；日志检查无异常。使用各自临时世界、玩家和 SQLite 数据库。

- Folia 原始记录：`/tmp/dominion-guide-folia-WH2FaF/results.json`
- Leaf 原始记录：`/tmp/dominion-guide-leaf-rxIPiN/results.json`
- 同目录保存 `environment.json`、`ui-snapshots.json`、`chat.json` 和完整日志。

被测 full JAR SHA-256：

```text
d1a92a97edace72178c80eb09ba24e9274ea3681fee2861493c4353e62f3daf8
```

验证范围为客户端收到的界面内容及导航；未进行人工截图验收、基岩客户端或真实代理跨服验证。

## 5.2.2 发布构建

随后将版本号递增为 `5.2.2-release`，重新执行 full JAR 构建并核对包内 `plugin.yml`。
与上述被测 JAR 解包逐文件比较，仅 `plugin.yml` 的版本号不同，类文件和其他资源一致，未重复运行相同的客户端场景。

交付文件：`build/libs/Dominion-5.2.2-release-full.jar`。

SHA-256：

```text
c7a28f79ad2858df06ec35d38fb7b7a74c9bfe8d2cb7ca1ac533063d4e768f7a
```
