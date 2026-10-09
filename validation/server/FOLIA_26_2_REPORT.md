# Folia 26.2 升级准备与实测

验证日期：2026-10-09。当前版本为 `Dominion 5.2.6-release`，已完成 Folia 26.2 的兼容改造。
下面的开服测试基于 `5.2.5-release`；递增版本后重新构建并通过 86 项单元测试，逐项核对两份 JAR 的解压内容，
确认只有 `plugin.yml` 的版本字段发生变化。准备升级时使用 **Java 25**。

## 成品与证据

- 成品：[Dominion-5.2.6-release-full.jar](../../build/libs/Dominion-5.2.6-release-full.jar)
- 发布包 SHA-256：`d067167ff46f2045b2ffd002d7c1682cb04d2c2625e77b26dc1182fafdd5e2d6`
- 开服实测包 `5.2.5-release` SHA-256：`9484a1748ffbd50955ceb2ed444dc03ea6469ae35aed984a7ed935b044b5b796`
- 版本递增核验：[release-5.2.6.json](./evidence/folia26_2/2026-10-09/release-5.2.6.json)。原始开服证据保持其实际测试版本和哈希。
- 服务端：官方 Folia 26.2 build 7，官方发布渠道标记为 BETA。
- 服务端 SHA-256：`128a634192261cd38bb4a5dc54075018a0f896fd6c6f529e37dca6e99e32b3b3`
- 构建依赖：Paper `26.2.build.124-stable`；paperweight `2.0.0-beta.21`。
- 本地持久证据：[summary.json](./evidence/folia26_2/2026-10-09/summary.json)。每套测试保存环境、结果、完整服务端日志，以及压缩的客户端交互记录。
- 重现命令：[README.md](./README.md#folia-262-升级验证)。没有安装、替换或重启生产服务器。

## 测试结果

| 测试 | 服务端与客户端 | 通过 | 原始结果 |
| --- | --- | ---: | --- |
| JUnit 回归 | 版本识别、事务、缓存、传送计算、手册、搜索 | 86 | [unit](./evidence/folia26_2/2026-10-09/unit/) |
| 原生 26.2 交互 | Folia 26.2 / MCProtocolLib 26.2，协议 776，无协议转换插件 | 41 | [native/results.json](./evidence/folia26_2/2026-10-09/native/results.json) |
| 手册、菜单、权限与搜索 | Folia 26.2 / Mineflayer 1.21.11，经 ViaVersion + ViaBackwards 5.12.1 | 26 | [guide/results.json](./evidence/folia26_2/2026-10-09/guide/results.json) |
| 跨维度与安全传送 | Folia 26.2 / 同上协议转换客户端 | 23 | [teleport/results.json](./evidence/folia26_2/2026-10-09/teleport/results.json) |
| 旧版回归 | Folia 1.21.11 build 14 / Java 21 / Mineflayer 1.21.11 | 26 | [legacy/results.json](./evidence/folia26_2/2026-10-09/legacy/results.json) |

四次最终开服测试均正常退出，无强制终止，日志扫描未发现插件异常或线程检查错误。
所有服务只绑定 `127.0.0.1`，使用独立临时世界、玩家和 SQLite；证据中的插件哈希均与上面记录的 `5.2.5-release` 实测包一致。

原生客户端使用固定的 `MCProtocolLib 26.2-20260907.143312-19`，运行时要求版本为 `26.2`、协议为 `776`。
客户端真实登录、发送命令、点击菜单、开箱取物、交互和攻击；夹具只生成测试场景、读取实际状态，
不手动触发 Bukkit 事件。爆炸场景中的操作玩家设为无敌，并在铜傀儡场景前恢复地面，
保证测试检查目标方块和搬运行为，不受操作玩家死亡或上一场爆炸挖出的坑影响。

## 已修复的升级缺口

1. **版本识别和模块加载**：识别 `26.2.build.7-beta`、`26.2-R0.1-SNAPSHOT` 等版本格式，新增 `v26_2`。
   未识别版本立即结束启动。旧服事件注册先过滤高版本模块，避免 Java 21 加载 Java 25 字节码。
2. **NMS**：新实体类型常量位于 `EntityTypes`；实体编号由按世界分配的 API 获取，替换已移除的
   `Entity.ENTITY_COUNTER`。26.2 开发包不提供 reobf 映射，新增模块直接打包 Mojang 命名的 JAR。
   实测新版本方块／物品展示实体的创建、元数据、位置更新和删除调用，并成功接收其生成及元数据包。
3. **Adventure 5 聊天菜单**：旧 `ClickEvent.Action.RUN_COMMAND` 字段的类型发生变化，造成实际运行中的
   `NoSuchFieldError`。按钮改用 `runCommand`、`openUrl`、`copyToClipboard` 工厂方法；原生聊天链接和两代服务端的完整 TUI 回归通过。
4. **容器保护**：铜箱改用 `Tag.COPPER_CHESTS`，覆盖全部氧化和涂蜡变种；普通监听补齐陷阱箱。
   增加实际库存开启检查，双箱分别检查两侧领地，避免从领地外的一半打开领地内库存。
5. **铜傀儡**：搬运不触发 `InventoryMoveItemEvent`，改用 `ItemTransportingEntityValidateTargetEvent`。
   当目标箱子所在领地与铜傀儡所在领地不同，对目标箱子检查既有 `hopper_outside`。
   实测领地外的铜傀儡在关闭权限时取不到钻石，开启后可以取走。
6. **Sulfur Cube**：新增吞入／替换方块、桶捕获、玩家攻击及爆炸监听；剪取复用已有的剪羊毛事件保护。
   已实测存入／剪取／桶捕获／攻击的拒绝与允许，以及爆炸对禁止和允许破坏方块的差异。

## 容器与新增交互的权限映射

| 对象或操作 | 既有权限 | 实测范围 |
| --- | --- | --- |
| 箱子、陷阱箱、木桶、潜影盒 | `container` | 普通箱子、陷阱箱、木桶、普通及白色潜影盒；关闭拒绝，开启后实际取出 7 个钻石 |
| 铜箱 | `container` | 未氧化、斑驳、锈蚀、氧化，各自含涂蜡版本，共八种，逐种检查拒绝及开箱取物 |
| 置物架 | `container` | 橡木、竹、苍白橡木；实际交换物品，关闭时库存保持不变，开启后取出物品 |
| 跨边界双箱 | 两侧的 `container` | 从领地外的一侧右键，另一侧关闭权限时拒绝，开启后访问完整双箱 |
| 铜傀儡选择其他领地的箱子 | `hopper_outside` | 领地外向受保护铜箱取物的拒绝与允许 |
| Sulfur Cube 吞入或替换方块 | `container` | 吞入石头前后的实体装备与玩家物品数量 |
| Sulfur Cube 剪取方块 | `shear` | 关闭保持石头，开启后实际取出 |
| Sulfur Cube 桶捕获 | `break` | 关闭保留实体，开启后实体被收进桶 |
| Sulfur Cube 玩家攻击 | `animal_killing` | 实际生命值在关闭时不变，开启后降低 |
| Sulfur Cube 爆炸破坏 | `tnt_explode` | 实际爆炸发生，关闭时保留目标泥土，开启后目标变为空气 |
| 硫磺、朱砂方块放置／破坏 | `place` / `break` | 原生数据包触发实际方块变化，分别检查拒绝与允许 |

没有新增权限列；新增行为使用现有权限配置，旧数据库正常迁移和读取。
手册／菜单套件同时验证建镇、权限组、信任类别的独立设置和数据库结果；传送套件覆盖三个维度、
GUI/TUI 入口、冷却、取消、安全落点、空中回退和楼板表面等 23 项。

## 升级范围

这是官方 Folia 26.2 单服环境的升级验证；Lophine/DeerFolia 等定制核心只验证了版本格式识别，
未在本次实测中作为服务端运行。真实代理跨服、MySQL/PostgreSQL 并发及整套生产插件组合仍需各自验收。

原生客户端是协议客户端，没有官方图形客户端的渲染；记录证明实际服务端交互和数据包兼容，
不包含材质、模型或画面人工验收。Sulfur Cube 对玩家／画框的爆炸保护已增加监听，
但尚未专项实测这些分支；毒气、间歇泉、火热形态、发射器自动装卸、被物理推动跨界的行为，
以及铜傀儡所有跨界搬运路线不在本次通过清单内。86 项单元测试和 90 项 26.2 开服场景不能代表全部原版机制的穷举验证。

升级使用上面的完整 JAR 和 Java 25；新权限行为会沿用服务器现有的 `container`、`shear`、
`break`、`animal_killing`、`tnt_explode`、`hopper_outside` 配置。现有世界、数据库和语言自定义内容应按服务器既有升级流程保留。
