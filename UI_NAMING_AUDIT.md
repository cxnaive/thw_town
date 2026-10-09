# 玩家界面命名检查

检查日期：2026-09-22。范围：默认中文 GUI/TUI、聊天通知、命令帮助、游戏内手册，以及对应的界面与权限代码。
下列审查发现已在 5.2.5-release 统一处理。引用行号记录的是审查时的位置。
本轮调整玩家可见名称与说明；命令参数、配置键、权限判断及数据库字段保持原有含义。

## 已完成的命名统一

5.2.4-release 已将小镇入口改为“成员权限管理”，列表改为“成员权限组”，模板入口改为“全镇默认权限”，
内置组显示“小镇成员（默认组）”。默认权限页和修改成功提示使用组显示名；自定义组的内部名继续用于命令和数据关联。
GUI/TUI、四语对应入口和手册操作路径已同步。

## 已纠正的误导提示

1. **提拔副镇长，却提示“提拔为管理员”；降职则写“居民”。**
   - 来源：[中文通知](languages/zh_cn.yml:772)，角色正确显示则在同文件 `town-role-text`。
   - [成员界面](core/src/main/java/cn/lunadeer/dominion/uis/town/TownMembers.java:168) 确实使用这条确认文案。
   - 已将按钮及确认统一为“任命副镇长”“设为普通成员”，角色显示统一为“镇长／副镇长／成员”。
   - 同时修正 `/town promote|demote` 帮助中的“镇长/助理”：实际仅镇长或服务器管理员有权操作。

2. **“自定义权限组在城镇前哨上不生效，请使用 @town 组或 /town trust”。**
   - 来源：[中文通知](languages/zh_cn.yml:259)、[创建旧领地组的限制](core/src/main/java/cn/lunadeer/dominion/handler/GroupProviderHandler.java:99)。
   - 该提示在普通玩家尝试从旧领地入口创建组时仍会触发。现在小镇自定义成员权限组已经生效，只是应从小镇统一创建。
   - 已改为“小镇属地的成员权限组由小镇统一管理，请到‘小镇 → 成员权限管理’创建和分配”。
   - 相邻的旧成员入口现在明确区分：小镇成员用成员权限管理，镇外玩家用信任。

3. **“隐式镇成员组：小镇的全体成员自动拥有此组权限”。**
   - 来源：[属地组管理说明](languages/cui/zh_cn.yml:1116)。
   - [权限判断](core/src/main/java/cn/lunadeer/dominion/managers/TownManager.java:1504) 会先使用成员所属自定义组；它不是额外叠加给所有成员的权限。
   - 已改为“默认成员权限”，说明“未分配自定义组的成员使用此组；成员分组请到小镇统一管理”。

4. **创建帮助固定写“免费建国属地”。**
   - 来源：[命令帮助](languages/zh_cn.yml:872)。
   - 建镇费用可配置，[实际扣款读取 foundingCost](core/src/main/java/cn/lunadeer/dominion/managers/TownManager.java:690)。默认免费不代表始终免费。
   - 已改为“无领地时获得建镇属地，费用以创建提示为准”。

## 已统一的术语和入口名称

| 原来的叫法 | 统一后的叫法 | 玩家容易误解的原因 |
| --- | --- | --- |
| 前哨／远程前哨／镇属地／附属领地 | 小镇属地，列表按钮“属地列表” | 同一个功能在 GUI 和 TUI 像是两套系统；“远程”还容易被理解成跨服操作。 |
| 建国属地／建国前哨／建镇属地 | 建镇属地 | 统一新建小镇时获得的第一块地。 |
| 授权／信任授权／Trust | 镇外玩家权限（信任）；按页面区分“小镇信任”“属地信任” | “授权”无法区分是在管理镇成员还是镇外访客。信任是额外允许，关闭不等于禁止。 |
| 群组／权限群组／权限组 | 权限组；称号功能称“权限组称号” | 群组容易被误认为另一个社交组织。 |
| 模板管理器／权限模板（主菜单旧入口） | 个人权限预设（旧领地） | 这是手动套用给旧领地成员的预设，不是现在会随修改同步的全镇默认权限。 |
| 标志／旗子／flag | 权限项；环境开关称“环境设置” | 对玩家没有直观含义，部分是 Flag 的直译。 |
| 已启用／已禁用（成员权限） | 允许／禁止 | 玩家要知道能否操作；“启用”容易误解为启用这条限制。环境功能开关可保留启用／关闭。 |
| 覆盖：允许／覆盖：拒绝 | 单独允许／单独禁止；继承显示“跟随全镇设置” | 呈现行为和作用范围；保留手册中的“覆盖”概念解释。 |
| 切换到 CUI／TUI | 切换箱子菜单／切换聊天菜单 | 玩家不必理解界面缩写。 |
| 银行／镇银行／镇库／小镇金库 | 小镇金库 | 明确资金归小镇，不是玩家个人余额。 |
| 全服镇属地上限 | 本镇属地总上限（跨服合计） | 现有用语容易被当成全服所有小镇共享一个限额。 |
| 次级管理员／属地管理员 | 属地管理员 | 与副镇长、服务器管理员分清作用范围。 |
| 升级权限模式 | 启用全镇默认权限（一次性） | 容易与“小镇等级升级”混淆，确认中仍须清楚说明重置及不可撤回。 |

位置举例：

- 前哨与授权：[TUI 小镇首页](languages/tui/zh_cn.yml:191)、[TUI 属地列表](languages/tui/zh_cn.yml:267)。
- 旧预设入口：[主菜单](languages/cui/zh_cn.yml:56)、[手动套用的实际实现](core/src/main/java/cn/lunadeer/dominion/commands/TemplateCommand.java:181)。
- 权限标志、开关及覆盖：[属地组权限](languages/cui/zh_cn.yml:1166)。
- 界面缩写：[GUI 切换](languages/cui/zh_cn.yml:93)、[TUI 切换](languages/tui/zh_cn.yml:17)。
- 等级及属地上限：[小镇等级按钮](languages/cui/zh_cn.yml:1514)、[任务页](languages/tui/zh_cn.yml:215)。

## 已处理的内部值显示

- 属地类型直接输出 `NORMAL`／`FOUNDING`：[属地列表](core/src/main/java/cn/lunadeer/dominion/uis/town/TownOutposts.java:288)、[挂靠选择器](core/src/main/java/cn/lunadeer/dominion/uis/town/TownAttachSelector.java:295)。
  现显示“普通领地／建镇属地”。`NORMAL` 不能用于区分自建还是贡献，那是另一项来源信息。
- 信任成功提示直接输出 `TOWN`／`OUTPOST`：[信任管理](core/src/main/java/cn/lunadeer/dominion/managers/TownManager.java:1089)。
  现显示“全镇／仅此属地”。
- 属地组权限修改通知仍会显示组内部名、`true/false`；旧成员权限通知使用权限内部 ID：
  [组权限通知](core/src/main/java/cn/lunadeer/dominion/handler/GroupProviderHandler.java:85)、[成员权限通知](core/src/main/java/cn/lunadeer/dominion/handler/MemberProviderHandler.java:75)。
  现使用组显示名、权限中文名称以及“允许／禁止”。
- 归还提示中的“镜像成员”“Trust”，以及“按原旗子获得授权”是实现术语。
  现描述玩家能理解的结果，例如“解除小镇关联，清除这块地的小镇成员权限和信任设置”。
- 原来的 `Page:` 分页提示已改成中文页码。

信任列表同时移除了 `BDSI` 摘要和聊天菜单中的 `build/destroy/switch/item_use` 按钮名，
改为“建造／破坏／机关与容器／物品使用”。列表列出额外允许的类别；关闭显示“不额外授权”，
明确其余权限按原有设置判断。四项开关顺序、独立性与信任优先级不变。
共享动态标签集中在 `DisplayNames`，对应四种语言均补齐翻译。

## 其他已完成的说明调整

主菜单原有“镇时代的土地”“炫耀你的排名”等说明，以及“旧系统——请尽快……”迁移催促。
已改成简短用途说明；只有用户确实需要迁移时再说明操作，不把实现历史塞入常用入口。
“环境行为”“设置传送”也分别简化为“环境设置”“设置传送点”。
管理员“所有服务器领地”入口实际读取本服务器缓存，现明确为“本服务器全部领地”。
手册中的信任、创建属地及权限升级路径同步到新的菜单名称。

## 本轮验证（5.2.5）

- `./gradlew :core:test shadowJar -PBuildFull=true --offline --console=plain` 成功。
- 83 项 JUnit：82 项通过，1 项需要外部数据库的测试跳过；12 个语言 YAML 文件解析通过。
- Folia 和 Leaf 1.21.11 各 26 项真实 GUI/TUI 场景通过，正常退出，插件异常及线程检查错误均为 0。
  包括个人预设入口、成员组名称、默认权限、属地类型、两种范围的信任摘要与四项独立开关、
  聊天菜单开关、手册上下文返回，以及 7 项搜索回归。
- 信任开关测试在隔离临时 SQLite 中核对每次点击的四个字段，确认只修改所点的类别。
- Folia：`/tmp/dominion-guide-folia-hvde3g/results.json`
- Leaf：`/tmp/dominion-guide-leaf-NGXShJ/results.json`
- JAR：`build/libs/Dominion-5.2.5-release-full.jar`；包内版本为 `5.2.5-release`。
- SHA-256：`a85d02cfd4448cfd3388a1a120c57118213865f42bf7abd9c2b0316f90accbe5`

运行场景使用默认简体中文。其余三种语言校验了结构并补齐共享标签及关键误导提示，
本轮没有进行其他语言的人工阅读或真实客户端逐页验证。
本轮未改数据库写入流程及权限规则，未重复执行跨服代理、外部 MySQL 并发等专项测试。

## 前一轮验证（5.2.4）

`5.2.4-release` full JAR 已构建，包内版本已核对。83 项 JUnit 中 82 项通过、1 项外部数据库测试跳过。
Folia 和 Leaf 各 17 项 GUI/TUI 场景通过，覆盖主入口说明、默认组中文名、自定义组显示名、默认权限页及上下文返回；阅读手册没有改变模板权限。

- Folia：`/tmp/dominion-guide-folia-zwY5Vn/results.json`
- Leaf：`/tmp/dominion-guide-leaf-PxvFBM/results.json`
- JAR：`build/libs/Dominion-5.2.4-release-full.jar`
- SHA-256：`e47aa30708ac60095d174f06752e91ad928a51bb7bdd48cd95372120490ad95b`

服主使用默认中文时，停服替换 JAR，并手动删除以下三个语言文件后启动生成新版：
`plugins/Dominion/languages/zh_cn.yml`、`plugins/Dominion/languages/cui/zh_cn.yml`、`plugins/Dominion/languages/tui/zh_cn.yml`。
本轮未增加语言文件删除或文案迁移代码。
