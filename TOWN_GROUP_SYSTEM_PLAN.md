# 小镇分组系统实现方案（v5.1.0）

状态：Phase 0–4 已实现（v5.1.0）；真实数据库/多人服运行时验收待部署环境执行
目标插件：Dominion（当前仓库）
依赖版本：5.0.2（已上线，含 Town 基础 + @town 内置组）

## 1. 目标与边界

在现有小镇系统上增加**小镇级成员分组**：镇长/副镇长可以定义组（如 管理员、建筑师、商人），把成员分进组，并让每个 Outpost（前哨领地）自动获得这些组，**各组权限由各 Outpost 独立设置**（"领地里面自己去设置"）。

```text
现状: Town -> @town 内置组(隐式全员) -> 各 Outpost 独立 flag
目标: Town -> town_group(自定义组) + @town(保留)
              ├── 镜像到每个 Outpost（dominion_group, town_group_id 链接）
              └── 成员按 town_member.group_id 分配，各 Outpost 独立设 flag
```

非目标：

- 不做多组成员（一个玩家一个组，与 `dominion_member.group_id` 单值语义一致）；
- 不改身份系统（`TownRole = {MAYOR, ASSISTANT, RESIDENT}`，无 FOUNDER 角色；
  `town.founder_uuid` 仅是历史标记，无权限检查）；
- 不动 `@town` 内置组机制（隐式成员、受保护、每 Outpost 独立 flag）；
- 不动 Trust 授权系统（`/town trust` 与组并存，trust 只增不减）；
- 不做 Outpost 级"自定义组同步回小镇"（单向：小镇 -> Outpost）。

## 2. 现状分析（代码事实，v5.0.2）

### 2.1 身份系统（已完整，零改动）

| 身份 | 存储 | 权限 | 实现状态 |
|------|------|------|---------|
| 镇长 MAYOR | `town.mayor_uuid` + `town_member.role=MAYOR` | 最高权限（解散/转让/任命） | ✅ 完整 |
| 副镇长 ASSISTANT | `town_member.role=ASSISTANT` | `assertManager` 允许（管理组/信任） | ✅ 完整 |
| 居民 RESIDENT | `town_member.role=RESIDENT` | 按 @town 组 flag | ✅ 完整 |
| 创始人标记 | `town.founder_uuid`（无 setter） | 无特殊权限 | ✅ 仅显示 |

转让镇长时旧镇长自动降为 ASSISTANT（TownManager.transferMayor）。
Phase 0 已完成：zh_cn.yml 中 `assistant` 已修正为“副镇长”。

### 2.2 @town 内置组（已完整，保持不变）

```java
public static final String TOWN_MEMBER_GROUP_NAME = "@town";   // TownManager:124
```

- Outpost 挂靠时自动创建（`ensureTownMemberGroup`，TownManager:1105）；
- 所有小镇成员**隐式持有**（不可添加/删除成员，`townGroupMembersImplicit`）；
- 每 Outpost 独立设置 flag（走现有组 flag 命令/UI）；
- 受保护：不可删除/重命名（`townGroupReserved`，GroupProviderHandler）；
- 权限级联已实现（`checkTownOutpostPrivilege`，TownManager:962）：
  `member(有) -> @town flag；无则 trust；再无则 legacy/guest 链`。

### 2.3 自定义组限制（需配合调整）

```java
// GroupProviderHandler.createGroup（:87-92）
// 在 Outpost 上，非 bypassLimit 玩家禁止创建自定义组
// 现有信息：custom groups have no effect on town outposts
```

v5.1.0 后该限制继续适用：Outpost 上的**普通领地本地组**仍被禁止；
但**小镇组镜像**由小镇组管理命令创建（走 TownManager，不受此限制）。

### 2.4 当前数据结构（Phase 1 完成后）

```sql
town(id, name, founder_uuid, mayor_uuid, balance, status, created_at)
town_group(id, town_id, name, display_name, priority, created_at)
town_member(town_id, player_uuid, role, source, group_id, joined_at)  -- PK(town_id,player_uuid)
town_trust(town_id, player_uuid, scope_type, scope_id, build, destroy, switch, item_use, created_by, created_at)
dominion_group(id, dom_id, name, town_group_id, <flag 列...>) -- 领地组，flag 在组行
dominion_member(id, dom_id, player_uuid, group_id, admin, <pri-flag 列...>)  -- 已有 group_id！
```

## 3. 数据库设计（最小改动：1 新表 + 2 新列）

### 3.1 新表 `town_group`（小镇组定义）

```sql
CREATE TABLE town_group (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    town_id      INTEGER NOT NULL,
    name         VARCHAR(32) NOT NULL,      -- 内部名（小写，如 admin）
    display_name VARCHAR(64),               -- 显示名（可本地化，如 管理员）
    priority     INTEGER NOT NULL DEFAULT 0, -- 预留：排序/未来继承用
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (town_id) REFERENCES town(id) ON DELETE CASCADE
);
-- 显式唯一索引（对齐 town_member 的做法，MySQL/PG 兼容）
CREATE UNIQUE INDEX idx_town_group_uk ON town_group(town_id, name);
```

`VARCHAR(32/64)` 是 Phase 2 业务层需要执行的逻辑长度上限；当前数据库
DSL 的 `FieldString` 会按方言生成项目统一字符串类型（MySQL
`VARCHAR(255)`，PostgreSQL/SQLite `TEXT`）。

### 3.2 修改 `dominion_group`：加 `town_group_id` 列

```sql
ALTER TABLE dominion_group ADD COLUMN town_group_id INTEGER;
-- NULL      = 普通领地本地组（现状，完全不变）
-- NOT NULL  = 小镇组镜像（由小镇组管理，受保护）
-- 不设 FK：镜像组随 Outpost 解散清理，不依赖级联
```

### 3.3 修改 `town_member`：加 `group_id` 列

```sql
ALTER TABLE town_member ADD COLUMN group_id INTEGER DEFAULT -1;
-- -1 = 未分组（默认，只吃 @town）
-- >0 = town_group.id
-- 不设 FK（照抄 dominion_member.group_id 模式，灵活、无级联负担）
```

### 3.4 注册进 DatabaseTables（关键，防止 export/import 丢数据）

备份注册需要补两处：

```java
PLUGIN_TABLES: 加入 "town_group"（备份白名单）
IMPORT_ORDER:  在 "town" 之后、"town_member" 之前插入 "town_group"
```

当前 `REQUIRED_CORE` 是恢复旧 Dominion 数据所需的五张基础表名单，
不是按表/列定义的 schema map。Town 表及 `town_group` 保持可选，
这样 5.0.2 或更早的备份可以在新版本中恢复；新增列会由动态 CSV
导出/导入逻辑自动携带，不需要单独登记列名。

恢复 5.0.x 备份时不会存在 `town_group.csv`；导入器会在同一事务内
将当前 `town_group` 视为空快照并清空，防止旧分组残留后错误关联到
恢复后复用的 Town ID。

所有 DDL 走 `Show.show().tables()/columns()` 守卫的幂等 Alter/Create
（对齐 DatabaseTables.migrate() 现有模式）。

## 4. 核心概念与权限优先级

### 4.1 层级

```text
Town
├── town_group（组定义：admin / builder / ...）
│   ├── town_member.group_id（成员分配，单组）
│   └── 镜像到每个 Outpost：
│       dominion_group(town_group_id=G) ── flag 由该 Outpost 独立设置
├── @town 内置组（隐式全员，默认权限）
└── /town trust（个人授权，只增不减）
```

### 4.2 权限检查顺序（Outpost 内）

```text
1. mayor / assistant / outpost-admin   -> 完全控制（现有 Asserts.checkDominionAdmin）
2. town member 有自定义组              -> 该组在 <本 Outpost> 的镜像组 flag
3. town member（任意）                 -> @town 内置组 flag（默认）
4. trusted player                      -> trust 授权（只增不减，现有）
5. 其他                               -> legacy member/group/guest 链（现有）
```

个人 trust 永远只增不减，组权限只在成员身份内生效 —— 与现有
`checkTownOutpostPrivilege` 的语义一致，只新增第 2 层。

### 4.3 单组约束

- `town_member.group_id` 单值；分配新组覆盖旧组；
- 与 `dominion_member.group_id` 语义一致，权限检查简单直接；
- 未来如需多组，可加 `town_group_member` 关联表（本版本不做）。

## 5. 同步机制（5 种场景全覆盖）

所有同步走 TownManager（异步任务内），失败整事务回滚。

### 5.1 创建小镇组（/town group create）

1. INSERT `town_group`；
2. 为每个现有 Outpost 创建镜像 `dominion_group(name, town_group_id=G)`
   （flag 用组默认值，创建后各 Outpost 独立调整）；
3. 刷新 TownCache + DominionCache。

### 5.2 删除小镇组（/town group delete）

1. `UPDATE town_member SET group_id=-1 WHERE group_id=G`（成员回退 @town）；
2. `DELETE FROM dominion_group WHERE town_group_id=G`（清所有 Outpost 镜像，
   对应 dominion_member 行按 FK 级联或被显式清理）；
3. `DELETE FROM town_group WHERE id=G`；
4. 刷新缓存。

### 5.3 分配成员到组（/town group add <player> <group>）

1. `UPDATE town_member SET group_id=G WHERE town_id=? AND player_uuid=?`；
2. 为每个 Outpost 的镜像组 `dominion_group(town_group_id=G)` 插入
   `dominion_member(dom_id, player_uuid, group_id=镜像组.id)`；
3. 刷新缓存。

### 5.4 新 Outpost 挂靠（/town attach / attachOutpost）

现有 `attachOutpost` 追加：
1. `ensureTownMemberGroup`（@town，现有）；
2. 为该 Outpost 创建所有小镇组镜像 `dominion_group(town_group_id=...)`；
3. 按 `town_member.group_id` 复制成员到对应镜像组；
4. 刷新缓存。

### 5.5 成员离开/被踢/小镇解散

- 离开/踢：删 `town_member` 后，清理该玩家在各 Outpost 镜像组的
  `dominion_member` 行（按 group_id 归属判定）；
- 解散：`town` 级联删 `town_group`（FK）；Outpost 解散时
  `dominion_group` 随领地删除（现有逻辑），镜像组随之消失。

## 6. 命令设计

```text
/town group create <name> [display_name]          # 镇长/副镇长
/town group delete <name>                         # 镇长/副镇长
/town group list                                  # 所有人可看
/town group info <name>                           # 所有人可看
/town group add <player> <group>                  # 镇长/副镇长（分配成员）
/town group remove <player>                       # 镇长/副镇长（取消分组）
```

- 权限检查：`assertManager(sender, town)`（现有，MAYOR 或 ASSISTANT）；
- `create`/`delete`/`add`/`remove` 走异步任务 + 事务；
- Tab 补全：组名、成员名。

## 7. UI 设计（复用现有，最小侵入）

### 7.1 Outpost 组管理（现有 GroupManage UI）

- 镜像组显示"§7[来自小镇]"标记（`group.getTownGroupId() != null`）；
- 镜像组不可删除/重命名（对齐 @town 的 `townGroupReserved` 保护）；
- 镜像组 flag 可编辑（这就是"领地里面自己去设置"）；
- 镜像组成员不可在 UI 添加/删除（成员由小镇组分配管理）。

### 7.2 小镇组管理（新增，TownGroups UI）

- 列出小镇所有组 + 成员数；
- 创建/删除组、分配/移除成员；
- TUI + CUI 双界面（对齐现有 Town 系列 UI）。

### 7.3 翻译

- ✅ zh_cn.yml `assistant: 管理员` -> `副镇长`（Phase 0 已完成）；
- 新增 `town-group-*` 文案（zh_cn 完整，其他语言回退英文默认值）。

## 8. 数据迁移与兼容

### 8.1 迁移策略：零数据迁移

- 新表/新列全部有默认值或可空，现有数据不受影响；
- 现有 @town 组：`town_group_id=NULL`，完全不变；
- 现有领地本地组：`town_group_id=NULL`，完全不变；
- 现有成员：`group_id=-1`，只吃 @town；
- 现有 Outpost 挂靠流程不变，只是 attachOutpost 追加镜像创建。

### 8.2 回滚

- 回滚到 5.0.2 JAR：新列/新表被 5.0.2 代码忽略，数据无破坏；
- 若已创建组：回滚后镜像组变普通组（town_group_id 被忽略），
  成员 group_id 被忽略（回退 @town），无数据损坏。

## 9. 边界情况

| # | 场景 | 处理 |
|---|------|------|
| 1 | 成员已在组，再分配新组 | 覆盖 group_id，旧镜像分配清理 |
| 2 | 组删除时成员 | group_id 重置 -1，回退 @town |
| 3 | 玩家被踢出小镇 | 删 town_member + 清理各镜像分配 |
| 4 | 小镇解散 | town_group FK 级联，镜像随 Outpost 消失 |
| 5 | 挂靠前领地已有本地组 | 保留（town_group_id=NULL），与镜像共存 |
| 6 | 镜像组改 flag | 允许（仅本 Outpost，不影响小镇定义） |
| 7 | 镜像组重命名/删除 | 禁止（受保护，对齐 @town） |
| 8 | 本地组与镜像组同名 | 允许（town_group_id 区分），UI 打标记 |
| 9 | 组名非法/重复 | `assertGroupName` 校验（对齐领地组） |
| 10 | Outpost 释放回个人 | 镜像组变普通组（town_group_id 失效无害），可手动清理 |

## 10. 必须测试的场景

- 镇长创建组 -> 所有现有 Outpost 出现镜像组；
- 分配成员 -> 该成员在所有 Outpost 镜像组中；
- 在 Outpost A 改镜像组 flag -> 只影响 A，小镇定义和其他 Outpost 不变；
- 新 Outpost 挂靠 -> 自动获得所有小镇组镜像 + 成员分配；
- 成员离开/被踢 -> 所有 Outpost 清理该成员；
- 组删除 -> 成员回退 @town，所有镜像组消失；
- 成员权限：自定义组 flag 优先于 @town；
- trust 与组并存：trust 只增不减；
- 导出 -> 清库 -> 导入 -> 组、镜像、成员分配完整；
- 重启后 TownCache / DominionCache 与 DB 一致；
- 回滚到 5.0.2：无数据损坏，@town 正常。

## 11. 实施阶段

### Phase 0：翻译修正（已完成）
- zh_cn.yml `assistant: 管理员` -> `副镇长`。

### Phase 1：数据库 + 注册（已完成）
- DatabaseTables：新表 `town_group` + 2 新列（幂等守卫）；
- `PLUGIN_TABLES` / `IMPORT_ORDER` 注册 `town_group`；
- 旧库启动时自动补 `dominion_group.town_group_id` 与
  `town_member.group_id`，旧备份仍可导入；
- 恢复 5.0.x 备份时显式清空缺失的小镇组快照，避免残留数据串镇；
- `:core:compileJava --offline` 编译验证通过；真实数据库启动冒烟待
  部署测试环境执行。

### Phase 2：核心 API + 同步（已完成）
- `TownGroupDOO` 负责查询模型，写操作统一收口到事务化
  `TownGroupSyncHandler`；
- 已覆盖创建/删除组、分配/移除成员、新 Outpost、成员离开/被踢；
- founding/remote/attach 三条 Outpost 创建或挂靠路径均同步镜像和成员；
- `checkTownOutpostPrivilege` 已增加自定义组层：镜像 flag 明确优先于
  `@town`，镜像缺失时安全回退 `@town`；
- Town/Group/Member 缓存在事务提交后立即刷新。

### Phase 3：命令接口（已完成）
- `/town group create/delete/list/info/add/remove` 已实现；
- 创建、删除、分配和移除复用 `assertManager` 与事务同步层；
- 已补组动作、玩家和组名 Tab 补全，并修复原先第二参数补全错误访问
  `args[1]` 的越界问题；
- 裸 `/town group` 会打开按玩家偏好选择的 TUI/CUI 页面。

### Phase 4：UI + 翻译（已完成）
- TownInfo 的 TUI/CUI 均提供“挂靠领地”和“小镇组”入口（仅镇成员
  显示挂靠入口）；
- 新增 `TownGroups` + `TownGroupManage` 双 TUI/CUI：列表、创建、删除、
  分配和移除成员；
- 镇属地的普通本地组创建入口隐藏；`@town` 与小镇镜像组均隐藏
  删除/重命名/成员编辑，仅保留本 Outpost 的 flag 设置；
- 镜像组显示“[来自小镇]”标记，Provider 层同时强制保护，避免绕过 UI；
- zh_cn 主语言/TUI/CUI 文案完整，其余语言使用 Java 英文默认值回退。

### 静态验证（已完成）
- `./gradlew :core:compileJava --offline` 通过；
- 12 个 language YAML 均可解析；
- `git diff --check` 通过；
- §10 的真实 SQLite/MySQL/PostgreSQL、多人服和导入导出场景仍需在部署
  测试环境执行，仓库当前没有可复用的自动化集成测试。

### 总计：Phase 0–4 代码完成；部署测试待执行

## 12. 交付物

- `TOWN_GROUP_SYSTEM_PLAN.md`（本文件）；
- 代码：DatabaseTables / TownGroupDOO / TownGroupSyncHandler /
  TownCommand(group 子命令) / GroupManage 标记 / TownGroups UI / 翻译；
- 构建产物：`Dominion-5.1.0-release-{lite,full}.jar`；
- 手动测试清单（§10）。
