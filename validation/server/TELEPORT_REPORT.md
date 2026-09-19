# Teleport Refactor Validation

## Upgrade

- 本次发布版本为 5.2.1-release，包含传送线程修复、落点搜索调整及旧接口清理。
- 正常停服后替换 `build/libs/Dominion-5.2.1-release-full.jar`，不要热卸载/热替换 JAR。
- 不需要删除配置或语言文件。启动时补齐缺少的配置与语言条目，已有自定义内容保留。
- 新增配置如下，玩家等待时间与冷却仍使用原 limitations 设置：

```yaml
safe-teleport:
  search-radius: 8
  max-candidates: 4096
  prepare-timeout-seconds: 10
```

- 半径限制为 0..32，候选数量限制为 1..65536，准备超时限制为 2..120 秒。
- 数据库只给临时表 `tp_cache` 增加可空 `request_id`、`target_server_id`、`expires_at`。
  不修改领地、成员、权限、金币或传送点设置。旧临时记录无效，会被清理。
- 跨服请求 60 秒过期，目标子服领取一次，中转子服不消费。跨服失败需要玩家重试。
- 所有相关子服需要统一升级。旧版会无条件消费 `tp_cache`，不能混合部署并声称新协议有效；
  分批升级期间先停用跨服传送。沿用 BungeeCord/Velocity 插件消息通道，不新增代理协议。

## Automated Checks

```sh
./gradlew :core:test shadowJar -PBuildFull=true --offline --console=plain
node validation/server/automation/teleport.mjs --accept-eula --server folia --port 25596 --cache /path/to/cache --libraries /path/to/libraries
JAVA_HOME=/path/to/jdk25 node validation/server/automation/teleport.mjs --accept-eula --server lophine --server-jar /path/to/lophine.jar --port 25596 --cache /path/to/cache --libraries /path/to/libraries
```

自动化只创建隔离临时服务器和 SQLite 数据库，绑定 localhost；端口占用时直接失败，不操作已有服务器。
每次输出 `RUN_DIR`，保留环境/JAR 哈希、聊天记录、fixture、控制台日志和 `results.json`。

当前常规测试 74 项通过，另有 1 项真实数据库用例按环境变量启用，本轮跳过。
单元测试覆盖状态竞争、迟到资源释放、执行阶段禁止撤回、新旧会话隔离、起始世界校验、
坐标解析、负坐标、碰撞、支撑、危险方块、边界、候选顺序、过期请求及并发领取。

前一轮数据库检查已覆盖 SQLite、MySQL 8.4.11、PostgreSQL 17（本轮不修改 SQL）：真实 JDBC 迁移重复执行、保留旧行、
新旧请求写入冲突、请求 ID 条件删除、中转服不消费、两路领取仅一个成功、过期记录清理。
真实数据库用例通过以下环境变量启用，只接受名字为 `teleport_test` 的专用空数据库：

```sh
DOMINION_TP_JDBC='jdbc:mysql://127.0.0.1:33316/teleport_test?allowPublicKeyRetrieval=true&useSSL=false' ./gradlew :core:test -PBuildFull=true --offline --rerun-tasks
DOMINION_TP_JDBC='jdbc:postgresql://127.0.0.1:35432/teleport_test' DOMINION_TP_DB_USER=postgres ./gradlew :core:test -PBuildFull=true --offline --rerun-tasks
```

## Runtime Coverage

2026-09-20 最终客户端回归（包含露天搜索、空中兜底及旧坐标接口清理）：

| 服务端 | 结果 | 记录目录 |
| --- | --- | --- |
| Lophine 1.21.11 DEV-cf8c377，Java 25 | 23/23 通过 | `/tmp/dominion-teleport-lophine-iLX2Na` |
| Folia 1.21.11 build 14，Java 21 | 23/23 通过 | `/tmp/dominion-teleport-folia-itPRQX` |

两次均正常停服，扫描结果无插件异常或线程归属错误。运行回归使用的 5.2.0 预发布构建 SHA-256：

```text
b0b2674e196f344c388f85d5ce84921d32149bd1ac69ec20bef27f2319f8a848
```

发布 5.2.1 时重新执行测试与 full JAR 构建，74 项常规测试通过、1 项外部数据库测试按配置跳过。
对两个 JAR 解包逐文件比较，仅 `plugin.yml` 的版本号从 `5.2.0-release` 改为 `5.2.1-release`；
类文件及其他资源一致，因此未重复运行相同的客户端场景。5.2.1 交付 JAR SHA-256：

```text
6bd6172d185f36475e7bbce550aed86208063b904564fdc3889d091d95ef44c6
```

场景覆盖：主世界/下界/末地往返、自定义/默认传送点、朝向、整数落地高度、
小镇与旧版领地 GUI 右键、两种 TUI 按钮、普通镇长、缺失世界、成功冷却、重复请求、
倒计时中被原生命令移动、旧回调不执行、冷区块空中领地兜底且不授予飞行、
露天底面与下方矿洞区分、明确地下点优先于屋顶、被堵点仅在附近调整、
空中兜底不绕过封闭矿洞过滤、半砖真实表面、花草不被误认为地板，
以及设置传送点事件从其他维度读取旧默认坐标时不加载地形。

尚未做完整代理网络三服实测，也没有在真实服务器注入 teleport future 异常、外部插件取消、
插件热禁用、坐骑、多玩家同区块取消/资源释放、MOVE 驱离与 Spigot 回归。
状态/SQL 测试不等同于这些端到端场景。运行环境仅本机临时服务器，不涉及生产服。

## Compatibility Details

`/dom tp` 的参数仍是领地名，不是数据库 ID。GUI/TUI 直接传递领地对象后由统一服务重新查询目标。
失败不会消耗正式冷却；实际传送已提交但 future 未完成时阻止第二次请求，不能假装撤销。

当前规则：明确设置的传送点能容纳玩家就优先使用，保留朝向，允许空中点。被堵住时只检查附近 ±4 格高度。
自动搜索要求 3×3、至少 3 格高的露天空间，取其第一层底面，不继续穿过地面寻找矿洞。
半砖等底面按真实碰撞高度处理，花草不会被当成地板。
下界在基岩顶层以下使用 3×3、至少 6 格高的开放空间，自动排除窄矿道；大型天然洞穴无法按形状与大厅区分。
有地面的候选优先于空中；找不到地面时允许领地内经过碰撞检查的空中位置，优先接近中心高度。
空中兜底仍须满足露天/开放空间条件，不能借此进入封闭矿洞。提示“目标为空中落点，请注意下落”。
不提供飞行、缓降、免摔或临时平台，玩家会正常下落；不新增创建时记录默认落点。MOVE 驱离仍要求有支撑。
开阔屋顶也符合露天规则，算法不能判断建筑用途；指定室内/地下层需要手动设置传送点。
落点检查仍偏保守：玩家身体不能越出目标领地，避开其他/子领地及需要跨区块读取碰撞的边缘点。
对于很窄的旧领地或紧贴区块边缘的设置，可能需要调整传送点；地面和空中均无可用点时仍会拒绝。
`DominionDOO.getTpLocation()` 仍供占位符、界面和修改事件查询坐标；删除其中默认点查地形的旧逻辑。
未设置时按 API 文档返回领地几何中心，Y 不再按地表截断，第三方不得把查询结果视作安全落点保证。
保留公开 DTO 方法签名；移除无人调用的两个 `TeleportManager.doTeleportSafely` 内部桥接入口。
