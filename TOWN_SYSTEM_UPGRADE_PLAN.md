# Town 系统升级实现方案

状态：设计稿 v3（镇所有制 + 全员 Outpost 模型）；**Phase 1 已实现（5.0.0）**

游戏内手册（当前实现）：

- `/town help [章节] [页码]`，别名 `/town guide`；章节支持 Tab 补全：
  `start`、`roles`、`templates`、`trust`、`land`、`levels`、`faq`。
- 主菜单与小镇首页提供手册；权限模板、属地权限组、Trust、属地管理和等级任务页提供本页帮助，
  支持返回原页面。GUI / TUI 共用主语言文件的 `town-guide-text`，四语均包含完整内容。
- 工具、等级任务、属地限制、价格和解散政策读取当前子服配置；小镇上下文显示实际权限模式。
  所有跳转只打开现有页面，并按当前身份检查入口；阅读不修改权限，不执行删除或升级。
- 新生成配置默认 `language: zh_cn`，已有配置的语言选择保留。无需删数据库或重建 config；
  现有语言加载流程会补入手册条目。GUI 帮助放在未占用的 `#` 边框位，保留自定义布局；
  没有空闲边框位的布局仍可通过命令阅读。
- 这是指引手册；实时“为什么不能操作”权限来源查询不在本次范围。
  自动开服验证入口见 `validation/server/README.md`。

Phase 1 实现落点（对照 §9）：

- DDL：`town` / `town_member` / `town_trust` / `dominion_member_archive` /
  `dominion_archive`（解散归档，§7.1）建表，`dominion` 加五列
  （`town_id` / `claim_kind` / `outpost_admin_uuid` / `attached_by` /
  `attached_at`），全部走 `DatabaseTables.migrate()` 幂等启动流程；
  新表已入备份白名单（含 `dominion_archive`）；
- DOO/缓存：`TownDOO` / `TownMemberDOO` / `TownTrustDOO` + 全局
  `TownCache`（跨服实体，挂 CacheManager 直属，仿 PlayerCache）；
  `town.balance` 以 BIGINT 分存储（ORM FieldFloat 在三种方言都是
  32 位，大额会漂移）；扣款走原子条件更新
  （`balance = balance - ? WHERE balance >= ?`，并发不透支），
  金额在入口量化到分（杜绝子分印钞），Vault/DB 两腿失败自动补偿
  并留 XLogger 对账日志；
- 并发与一致性（第二轮 review 加固）：join/disband 竞争复核回滚、
  createTown 失败补偿回滚、镇名大小写不敏感唯一（对齐 dominion
  名字符集）、TownCache 原子快照 + 写后即时刷新（reloadNow）、
  归档表 flag 列幂等（新版本新增 flag 不击穿归档）、
  disband 子区先于父区归档删除（防 FK 级联击穿）、
  并入成员的自有 legacy 领地随之一并挂靠（§3 不变量 3）；
- 第四轮 review 加固：createTown/joinTown/createRemoteOutpost 全链路
  补偿回滚（含已挂靠领地 detach）；mergeMembers 成员判定改读 DB；
  disband 子区枚举改走 DB（跨服可见）且贡献子区先改挂根再返还；
  `DominionDOO.setOwner` 镇属硬禁（第三方不可改写 contributor）；
  镇驱动的建/删地显式触发 DominionCreateEvent/DominionDeleteEvent
  （仅通知，不可否决）；town.founder/mayor 不设 FK（防回滚灭镇）；
  恢复镇前备份显式清空镇表并告警；跨服合并对镇数据丢弃告警；
  `@town` 组保留名仅限镇属状态；resize UI 与 handler 门控一致；
  Info UI owner 显示镇名、按钮按管理权归属；auto-create-radius
  一律钳到 -1；
- 命令：独立 `/town` TabExecutor（create/disband/invite/join/leave/
  kick/transfer/trust/untrust/deposit/withdraw/outpost/admin/info/
  list）；`/town release <dominion> [confirm]` 管理员仲裁；
  join 需要镇长/助理的邀请（内存邀请，5 分钟，对应 §7.1 威胁模型）；
- 权限接管：`Others.checkPrivilegeFlagSilence` 单点注入镇级联
  （127 个版本事件处理器自动覆盖）；管理路径由
  `Asserts.checkDominionAdmin` 路由（镇属 Outpost 上 owner_uuid
  不再给权）；镇成员默认 flags 由每 Outpost 内建组 `@town` 承载；
- 闸门：个人/子区禁建在 `DominionProviderHandler.createDominion`；
  legacy resize/delete 与镇属扣费（TownBank）在 resize/delete 内路由；
  镇属领地禁止普通转让；管理员可通过 release 返还，或 `dom give ... force`
  解除关联后转交（含原镇已不存在的遗留属地）；受让人在镇时仍尝试自动挂靠；
- 偏差说明：
  - 镇原生 Outpost（founding/remote）以 `attached_by IS NULL` 标记
    （出生即镇属，无挂靠事件），dissolve-all 之外的
    return-contributed 策略据此区分存量地；
  - 解散归档新增 `dominion_archive` 镜像表（§6 未列，§7.1 要求）；
  - `{OWNER}` 在镇属 Outpost 上显示镇名（§12.2 小改已做）；
  - `autoCleanAfterDays` 已移除（§12.2）；`autoCreateRadius`
    默认值改为 -1；
  - 次级管理员退镇/被踢自动清空已实现；
  - `DominionDTO.getOwner()` 在镇属 Outpost 上返回 contributor
    （第三方语义漂移已在 `setOwner` 硬禁兜底；Phase 2 补 TownDTO
    让插件可区分，见 §9 Phase 2）；
  - release/return 把地还给"已加入其他镇"的 contributor 时产生
    无镇 Outpost（§3 操作矩阵设计内），Phase 1 无玩家自助再挂靠
    命令，该地冻结至管理员介入或 Phase 2；
  - 镇驱动的建/删地触发 DominionCreateEvent/DominionDeleteEvent
    仅通知语义（不可否决）；attach/detach 无专属事件，Phase 2
    以 Town 事件族统一补齐。

目标插件：Dominion（当前仓库）

## 1. 目标与边界

本次升级在 Dominion 上增加 Town 社会层。**所有存量 Dominion 统一视为 Outpost**（暂时无镇的 Outpost），区域模型保持 Cuboid 不变；区块（Chunk）化属于 Phase 2。

```text
旧模式：  玩家 -> 多个个人 Dominion Cuboid
Phase 1： 无镇玩家 -> 无镇 Outpost（存量 Cuboid，只减不增，旧权限链不变）
         Town     -> 镇属 Outpost（挂靠的存量 Cuboid）
                  -> Founding Outpost（新镇首个免费 Cuboid）
Phase 2： Town     -> TownClaim（16x16 Chunk）+ 未迁移的 Outpost
```

目标：

- 新玩家只能创建或加入 Town，不能创建个人领地；
- 老玩家可以无镇保留领地（存量特例），但任何人不能再新建个人领地；
- 玩家建镇或入镇时，其名下全部 Dominion 自动挂靠为该镇 Outpost，**所有权转移给 Town**；
- 挂靠时旧成员默认去重并入 Town（见 §4.2）；
- 每个 Outpost 保留独立 flags；权限管理由 Town 层面接管；每个 Outpost 可任命一名次级管理员；
- 子 Dominion 冻结新建，随 Phase 2 迁移逐渐消亡。

非目标：

- Phase 1 不做批量数据迁移，不强制老玩家入镇；
- Phase 1 不改变区域模型（仍 Cuboid），不做 Chunk 圈地；
- 个人新建任何领地永远禁止；远程 Outpost 仅镇级、付费、限量（§4.6）。

## 2. 核心概念

```text
Town
├── TownMember：正式成员（MAYOR / ASSISTANT / RESIDENT），受 max-memberships 限制
├── TownTrust：独立授权，不占成员名额，scope = TOWN / OUTPOST
├── Founding Outpost：建镇时创建的免费 Cuboid（仅无存量领地的玩家）
└── Outpost：挂靠的存量 Dominion Cuboid，镇所有，保留独立 flags

无镇玩家（老玩家特例）
└── 无镇 Outpost：town_id = NULL 的 Dominion，旧权限链一字不改
```

### 2.1 Outpost 统一模型

所有 Dominion 都是 Outpost，用 `town_id` 区分归属：

- `town_id IS NULL`：无镇 Outpost，权限判定完全沿用 Dominion 旧链
  （owner → dominion_member → dominion_group → guest flags）；
- `town_id = T`：镇属 Outpost，`owner_uuid` 降级为 contributor 历史记录，
  不再参与判定，权限由镇级级联接管（见 §5）。

### 2.2 TownMember

角色：`MAYOR` / `ASSISTANT` / `RESIDENT`。

主键 `(town_id, player_uuid)`。`source` 取值：

- `FOUNDER`：建镇者；
- `JOINED`：主动入镇者；
- `MERGED`：挂靠时从 dominion_member 并入者。

不设计 grandfathered：成员并入受 max-memberships 容量约束（§4.2），
不存在任何产生超额成员的路径。`max-memberships` 统计
`COUNT(DISTINCT town_id)`，对所有玩家统一生效。

### 2.3 TownTrust

独立授权关系：被 Trust 者无需入镇、可被任意多镇 Trust、不占成员名额、
可与 TownMember 并存。scope 支持整镇（TOWN）和单个 Outpost（OUTPOST）。
Trust 判定从 Phase 1 起即生效（镇属 Outpost 的级联中包含 Trust 层）。

### 2.4 次级管理员（Outpost Admin）

每个镇属 Outpost 可由 MAYOR / ASSISTANT 任命一名次级管理员：

- 必须是本镇成员；
- 拥有该 Outpost 的 owner 级管理权：改 flags、管理该 Outpost 的成员覆盖与 Trust；
- 不能 release Outpost、不能删除领地；
- 其退镇/被踢时自动清空。

典型用途：任命原主（contributor）继续日常管理自己的旧领地，迁移体验平滑。

## 3. 全局不变量与操作矩阵

不变量：

1. `town_id` 只跟随 Town，不跟随任何玩家；只在显式事件中改变；
2. 镇成员名下不存在无镇 Outpost（在镇期间获得的 Dominion 自动挂靠本镇）；
3. 无镇 Outpost 的 owner 必是无镇玩家；
4. 子 Dominion 无独立挂靠规则——它是普通 Dominion，owner 是谁就随谁的事件
   （owner 不同的子区不随父挂靠，成为过渡态"飞地"，见 §4.3）；
5. **无镇 Outpost 全部来自 legacy 存量**：个人领地禁新建 + Founding 随镇消亡，
   不存在任何产生新无镇领地的路径，新人无法带领地入镇。

操作矩阵：

| 操作 | 效果 |
|---|---|
| `/town create` | 玩家全部存量 Dominion 挂靠为 Outpost；无存量者在当前位置获得免费 Founding Outpost |
| `/town join` | 列出将被转为镇所有的 Dominion 清单，二次确认后全部挂靠 |
| 退出 / 被踢 | 领地留在镇里（镇所有，不随人走）；contributor 记录保留 |
| mayor 转让 | 纯 `town_member` 角色变更，零 Dominion 行写入；Founding 不受影响 |
| `/town outpost create`（MAYOR/ASSISTANT） | 新建远程镇属 Outpost（§4.6），TownBank 扣费；数量/尺寸受上限；个人不可创建 |
| 镇属 Outpost resize | 允许并扣费（§4.6）；无镇 legacy 地 resize 仍被拒绝 |
| 解散 Town | **全部 Outpost 同步解散**（删除保护数据，不碰方块），写入归档供 admin 恢复；Founding 一并解散——任何镇属领地都不转化为个人领地 |
| 无镇 Outpost 个人转让 | 受让人在镇 → 自动挂靠其镇并通知；受让人无镇 → 保持无镇 |
| 镇属 Outpost | 个人不可转让（镇财产）；管理员可用 `dom give ... force` 解除关联后转交 |
| `/town release <dominion> [confirm]` | （仅 OP）Outpost → 无镇，返还 contributor，用于仲裁 |
| `/dom give <dominion> <player> force` | OP / `dominion.admin` 强制转交；原镇不存在也可执行；受让人在镇时尝试自动挂靠，失败则保留个人所有权并提示 |
| `/dom delete <dominion> confirm`（兼容 `force`） | 镇长、副镇长、管理员可删除单块镇属地（含 Founding）；保留小镇和全部下级领地，不退款 |

单块属地删除：GUI/TUI 管理页对镇长、副镇长及管理员显示“删除属地”，五秒内再次点击确认。
普通成员、贡献者或仅被任命的属地管理员没有删除权。提交前在数据库中重查角色与属地归属，
并与转交、挂靠、解散共用镇/属地行锁；只归档并删除所选领地及其权限，失败全部回滚。
直属下级领地调整到被删领地的父节点，全部保留，不因外键级联消失；不删除方块或箱内物品。
管理员可清理原镇已不存在的遗留属地；删除最后一块属地也不会解散小镇。

旧版个人领地删除也使用数据库事务：锁定当前个人领地树、重查所有者和挂靠状态，
归档并删除个人领地，保留遇到的镇属领地及其完整下级分支，改挂最近保留祖先。
预览明确列出待删除的个人领地和保留的镇属分支，执行时再次查库；退款只针对本次实际删除的个人领地，
退款异常单独提示，不会把已经提交的删除误报为失败。

领地缓存按一份快照发布：ID、名称、所有者、父子关系和空间索引构建完毕后一次替换；
全量与单行刷新串行执行，删除通知重新加载当前父子关系。空间节点持有该快照的领地数据，
读请求在切换期间仍可使用完整旧树，不再通过旧节点回查新表中的已删 ID。

管理员强制转交补充：

- 普通转交使用 `confirm` 确认，仍禁止直接转交镇属地；`force` 仅限管理员，
  可处理 Founding、子领地及原镇已不存在的遗留属地。
- 在同一数据库事务内归档原领地和旧镇成员镜像，清理所选领地的旧镇关联、
  成员镜像、镇权限组镜像、属地 Trust/override 及已付费记录，再更新所有者；
  不修改方块、坐标、世界或子服，不删除旧镇及其镇级成员/模板/Trust。
- 所选领地的个人子领地一并转交，镇属子领地及其下级保留；单独转交子领地时
  保留原父子关系，避免与仍存在的父领地成为重叠的顶层领地。不会从缓存复活已删除的领地。
- 转交成功后沿用自动挂靠流程；接收者属于多个镇时按镇长、副镇长、成员的顺序选镇。
  挂靠仍检查属地上限，失败不撤销已经成功的转交。跨服保护缓存通过现有约 60 秒轮询刷新。
- 解散与强制转交、挂靠共用数据库的镇/领地行锁。解散在一个事务内按当前归属
  归档、删除/返还属地及删除小镇，中途失败全部回滚；不会按旧缓存清单删地。
  个人子领地及其他镇的属地保留；若父节点被删除，则改挂最近的保留祖先，无祖先才提升到根。
  Bukkit 删除通知在提交后发送，避免持有数据库锁时等待事件监听器。
- 挂靠的事务提交后，缓存刷新或通知异常不改变挂靠成功结果；提交后的状态读取失败
  单独提示，不能误报为“仍保留个人领地”。

创建禁用世界配置（各子服 `config.yml`，默认空列表）：

```yaml
town:
  creation-disabled-worlds: []
```

填入本子服的实际世界名（不是子服 ID 或显示映射名）。普通玩家在这些世界不能
建镇、创建属地或通过旧版入口创建个人/子领地，OP / `dominion.admin` 不受此项限制。
不改变现有领地、挂靠或 resize 规则。启动时自动补充缺失的配置项，无需删除配置文件。

## 4. 数据迁移与成员并入

### 4.1 无批量迁移

Phase 1 上线 = 幂等 DDL（建表加列）+ 新命令，随插件启动执行，
无维护窗口，无 report/apply 流程。老玩家不入镇则数据和权限零变化。

### 4.2 挂靠时的成员并入（默认行为）

玩家建镇/入镇导致 Dominion 挂靠时，对每块被挂靠的 Dominion：

1. 收集其全部 `dominion_member`；
2. 按 `player_uuid` 去重；
3. 若该玩家当前 Town 数 < `max-memberships`
   → 并入 `town_member`（role=RESIDENT，source=MERGED）；
4. 若已达上限（如已属他镇）
   → **不并入**，转为该 Outpost 的 TownTrust（scope=OUTPOST），
     四布尔按 §4.5 映射表从旧 member flags 生成；
5. 被并入/被转换者的原 `dominion_member` 行全部移入
   `dominion_member_archive`（归档不物理删除，支持回滚与审计）。
   挂靠完成后该 Outpost 不再存在 dominion_member 个人覆盖层。

注意：

- 并入意味着旧成员获得镇级 RESIDENT 权限（覆盖该镇全部 Outpost），
  权限范围比原来单块领地更大——这是有意设计的默认行为，
  可用 `town.merge-members-on-attach: false` 关闭（关闭时**全部**旧成员
  转为 Trust，不并入成员）；
- 转为 Trust 是有损映射（§4.5）：FLY / TELEPORT / PVP 等特殊 flags
  不带入，镇长事后可调整或改走正常邀请入镇；
- `dominion_group` 不并入不转换，保留为 Outpost 覆盖层，镇级可管理和清除。

### 4.5 旧 member flags → Trust 四布尔映射

来源 flag 任一为 true，对应布尔即置 true：

| Trust 布尔 | 来源 PriFlag |
|---|---|
| `build` | PLACE, SOWING, VEHICLE_SPAWN |
| `destroy` | BREAK_BLOCK, HARVEST, VEHICLE_DESTROY |
| `switch` | DOOR, BUTTON, LEVER, PRESSURE, REPEATER, COMPARER, NOTE_BLOCK, CONTAINER, HOPPER, ITEM_FRAME_INTERACTIVE, EDIT_SIGN, DRAGON_EGG, JUKEBOX, LECTERN, BOOKSHELF, BED, ANCHOR, CAKE |
| `item_use` | CRAFT, CRAFTER, ANVIL, ENCHANT, BREW, BEACON, USE_MAP, IGNITE, SHOOT, EGG, HOOK, TRADE, FEED, SHEAR, LEASH, DYE, HONEY, PICK_UP, DROP_ITEM |

不映射（直接丢弃）：ADMIN（安全：永不自动转换）、TELEPORT、FLY、
RIDING、ENDER_PEARL、PVP、MONSTER_KILLING、ANIMAL_KILLING、
VILLAGER_KILLING、GLOW。MOVE 不映射但 guest flags 默认 true，
日常移动不受影响。

### 4.3 跨 owner 子 Dominion

挂靠按 `owner_uuid = 玩家本人` 逐块匹配。owner 不同的子 Dominion
不随父挂靠，成为镇属 Outpost 内的"飞地"，权限判定不变（innermost wins）。
这是过渡态：子 Dominion 冻结新建（§5.3），并随 Phase 2 迁移消亡，
飞地集合单调收敛，不需要主动清理逻辑。

### 4.4 Founding Outpost

无存量领地的玩家（新玩家）`/town create` 时，在当前位置复用现有
Cuboid 创建管线生成 Founding Outpost：

- 免费（`town.founding-cost: 0`）；
- 尺寸受 `town.founding-max-size` 约束；
- `claim_kind = FOUNDING`，无 contributor；正常解散时随镇一并解散，不提供玩家返还入口；
  管理员可使用 `dom give ... force` 单独仲裁转交。

老玩家建镇不另发新地（已有存量）。

### 4.6 镇扩张：扩建与远程 Outpost（Phase 1 唯一的新建路径）

个人新建 Dominion 全局禁止后，镇扩张是 Phase 1 唯一合法的新增领地路径。
"相邻"是区块网格概念，不属于 Cuboid 阶段——Cuboid 下连续生长 = resize，
"新建 Outpost"天然是远程的（前哨）。镇扩张因此有两个操作：

两者共用 `town` 下的全局约束，与执行操作的 MAYOR/ASSISTANT、
default/VIP limitation 完全解耦：

- 尺寸与 Y 范围：`outpost-max-horizontal-size`、`outpost-min-size-*`、
  `outpost-max-size-y`、`outpost-no-higher/lower-than` 和
  `outpost-auto-include-vertical`；
- 数量：小镇持久化 `level`（默认 1，当前最高 10），1 级使用
  `outpost-max-count` / `outpost-max-count-per-world` 的基础额度 3，之后每级按
  `outpost-count-per-level: 2` 增加，公式为 `3 + (level - 1) * 2`；
  成员共同任务默认开放至 5 级：1→2 共同提交 9 个下界合金锭，2→3 共同提交
  32 个幽匿催发体，3→4 共同提交 64 个回响碎片，4→5 共同提交 8 个沉重核心
  （沉重核心需要 1.21+）；提交进度持久化且允许全体成员分批贡献，OP 仍可在 1～10
  之间直接设置等级；OP 手动降级时，同一事务内清除高于新等级的任务进度，
  避免已完成进度在降级后让小镇无消耗恢复等级；
  Founding/贡献/新建全部计入，远程新建和挂靠都会校验；已有老镇若超过等级上限，
  不删除现有领地，只阻止继续增加；
- 不与任何 Dominion 重叠（常规重叠检查）；
- 计费：使用 `town.outpost-pricing` 的 X*Z 面积阶梯价格，
  **计费主体为 TownBank**；缩小按已付款账本与 `refund-rate` 退款，解散不退款；
- `town.outpost-allow-remote: false` 可整体关闭远程新建，镇只剩扩建一条路。

防插旗论证：原禁令防的是**个人**到处圈地。远程 Outpost 只能由镇创建，
受三重闸——max-memberships 限制镇数量、max-count 限制每镇前哨数、
cost 提供经济闸。个人依旧没有任何新建路径。

出生即镇属：`town_id = T`、`claim_kind = NORMAL`、无 contributor、
`owner_uuid` 仅记录创建者。个人不可转让、退镇不涉及、解散随镇消亡
（不变量 5 不受影响——它从不处于无镇状态）。

Founding Outpost 即其免费特例（建镇时第一个）；Phase 2 与 legacy
Outpost 走同一条 `migrate-base` 管线转 chunk。
"相邻"概念在 Phase 2 的 chunk claim 才回归（连片 Town proper 的网格生长）。

## 5. 权限判定

### 5.1 镇属 Outpost（Town 接管后的级联）

```text
服务器管理员 bypass
  ↓
Town MAYOR / ASSISTANT           —— 全权：改 flags、任命次级管理员、管理 Trust
  ↓
Outpost 次级管理员                —— 该 Outpost 的 owner 级管理（不可 release/删除）
  ↓
TownMember                       —— 按该 Outpost 的"镇成员默认 flags"
  ↓
TownTrust（scope=TOWN 或 scope=该 OUTPOST；含挂靠时转换的旧成员）
  ↓
保留的 dominion_group            —— 旧群组覆盖层（Phase 1 不可达，见下注记）
  ↓
该 Outpost 的 guest flags
  ↓
Outsider
```

> **Phase 1 注记（第二轮 review 结论）**：dominion_group 覆盖层在挂靠后
> 不可达——群组只能经由 dominion_member 行触达，而挂靠已归档并删除
> 全部成员行；镇属 Outpost 上同时禁止创建自定义组（仅内建 `@town`
> 组生效）。该层仅概念保留，功能无损。OUTPOST 级 Trust 在
> detach/release 时随 `detachFromTown` 删除，不会在重新挂靠时复活。

- 每个 Outpost 的独立 flags 零成本：Dominion 本来就带 env flags + guest flags。
  新增的"镇成员默认 flags"每 Outpost 一份，实现时复用现有 group/template
  机制（每 Outpost 一个内置组承载），不新建 flags 存储；
- 挂靠后 dominion_member 个人覆盖层不复存在（§4.2），此后对单人的
  特殊授权一律走 TownTrust（scope=OUTPOST）——授权路径统一；
- owner_uuid 在镇属 Outpost 上仅作 contributor 展示与解散返还依据。

### 5.2 无镇 Outpost

判定链与升级前逐点一致，一字不改。

### 5.3 子 Dominion 冻结

- `land-system.allow-new-sub-dominion: false`（默认），禁止新建子 Dominion；
- 存量子区判定不变（innermost wins），包括镇属 Outpost 内的飞地；
- Phase 2 `migrate-base` 转 Chunk 时摊平，preview 报告中列出被摊平的子区。

## 6. 数据库结构

现有 `dominion`、`dominion_member`、`dominion_group` 表保留。

### 6.1 Town

```sql
town (
    id,
    name,
    founder_uuid,
    mayor_uuid,
    balance,
    status,              -- ACTIVE / DISBANDED
    created_at
)
```

### 6.2 TownMember

```sql
town_member (
    town_id,
    player_uuid,
    role,                -- MAYOR / ASSISTANT / RESIDENT
    source,              -- FOUNDER / JOINED / MERGED
    joined_at,
    PRIMARY KEY (town_id, player_uuid)
)
CREATE INDEX idx_town_member_player ON town_member(player_uuid);
```

### 6.3 TownTrust

```sql
town_trust (
    town_id,
    player_uuid,
    scope_type,          -- TOWN / OUTPOST
    scope_id,            -- town.id 或 dominion.id
    build,
    destroy,
    switch,
    item_use,
    created_by,
    created_at,
    PRIMARY KEY (town_id, player_uuid, scope_type, scope_id)
)
```

无全局玩家唯一约束。

### 6.4 Dominion 加列

```sql
ALTER TABLE dominion ADD COLUMN town_id BIGINT NULL;
ALTER TABLE dominion ADD COLUMN claim_kind VARCHAR(32) DEFAULT 'NORMAL';
                             -- NORMAL / FOUNDING
ALTER TABLE dominion ADD COLUMN outpost_admin_uuid VARCHAR(36) NULL;
ALTER TABLE dominion ADD COLUMN attached_by VARCHAR(36) NULL;
ALTER TABLE dominion ADD COLUMN attached_at TIMESTAMP NULL;
```

`owner_uuid` 不改名不迁移；在镇属 Outpost 上语义为 contributor。

### 6.5 成员归档表

```sql
dominion_member_archive (
    -- dominion_member 原列镜像
    town_id,             -- 并入目标镇
    archived_at,
    archive_reason       -- MERGED_INTO_TOWN
)
```

### 6.6 TownClaim（Phase 2 才建）

```sql
town_claim (
    town_id,
    world_uid,
    chunk_x,
    chunk_z,
    claim_kind,          -- FOUNDING_BASE / NORMAL_CLAIM / MIGRATED
    source_dominion_id,
    claimed_by,
    claimed_at,
    PRIMARY KEY (world_uid, chunk_x, chunk_z)
)
```

推迟到 Phase 2 建表，避免空表语义漂移。

## 7. 风险与缓解

### 7.1 邀请-踢人圈地（镇所有制固有攻击面）

恶意镇长邀请老玩家入镇 → 对方全部领地转镇所有 → 踢人 → 白得土地。缓解：

- 入镇确认列出**将被转为镇所有的全部 Dominion 清单与数量**，二次确认；
- attach / detach / kick / leave / release 全部写审计
  （`attached_by` / `attached_at` + 操作日志）；
- 管理员 `/town release <outpost> confirm` 仲裁通道；释放时清除 Outpost override、
  `@town`、小镇镜像组/成员与 Outpost Trust；建国属地拒绝释放；
- 配置 `town.allow-join-with-legacy-land: false` 可整体禁止带地入镇；
- **解散同步删地是最高危操作**（含他人贡献的 legacy 地一并解散）：
  解散确认强制列出将解散的 Outpost 数量与贡献者名单、二次确认、
  全量 Outpost 数据写入归档，admin 可恢复（防盗号/冲动解散）。

### 7.2 Town 覆盖率靠自愿驱动

老玩家不建镇就永远无镇有地，收敛慢但方向单调。Phase 1 不加软压力；
后续可考虑无镇领地数量/面积上限，不在本期范围。

### 7.3 回滚语义

回滚 = 换回旧 JAR。新表新列对旧代码透明；无镇 Outpost 数据从未改变。
已并入成员的原始 `dominion_member` 行在 `dominion_member_archive` 中
可恢复。镇属 Outpost 在旧代码下按 owner_uuid 回到旧链判定（owner 从未被
修改），行为可预期。

### 7.4 多服同步

`town_id`、成员、Trust、次级管理员变更的缓存语义与现有 Dominion 变更一致。
`multi-server.server-id` 不修改。

实现期验证结论（Phase 1 已落地）：

- 现有跨服缓存**没有显式失效通道**（无 plugin-message 同步、无轮询），
  Dominion 数据靠 DB 回退读 + 重启收敛；
- Town 数据采用同代弱一致模型，并加两层缓解：所有重载入口
  （`/dom reload cache`、`DominionInterface.reloadCache()`）包含
  TownCache；CacheManager 启动一个 60s 周期异步全量刷新，把跨服
  Town 变更的可见延迟从"重启"收敛到"最长约 60 秒"；
- TownBank 余额更新为 SQL 原子自增（`balance = balance + ?`），
  跨服并发存取/扣费不丢更新；
- 若后续需要即时一致性，再引入跨服失效广播（不在 Phase 1 范围）。

## 8. 配置建议

```yaml
land-system:
  allow-new-personal-dominion: false     # 任何人不得新建个人领地
  allow-new-sub-dominion: false          # 子 Dominion 冻结
  legacy-allow-resize: false
  legacy-allow-delete: true

town:
  max-memberships: 1
  merge-members-on-attach: true          # true: 有容量并入成员、无容量转 Trust
                                         # false: 旧成员全部转 Trust
  allow-join-with-legacy-land: true      # §7.1 开关

  founding-cost: 0
  founding-max-size: 32                  # Founding Outpost 单边上限

  outpost-allow-remote: true             # 允许新建远程 Outpost；false 时只能扩建现有
  max-level: 10                          # 系统最高等级；OP 可手动设置
  progression-max-level: 5              # 当前向成员共同任务开放的最高等级
  level-requirements:                    # key 是目标等级；支持全体成员分批提交；已有配置不自动覆盖
    2:
      type: item
      material: NETHERITE_INGOT
      amount: 9
    3:
      type: item
      material: SCULK_CATALYST
      amount: 32
    4:
      type: item
      material: ECHO_SHARD
      amount: 64
    5:
      type: item
      material: HEAVY_CORE
      amount: 8
  outpost-count-per-level: 2             # 每高一级增加的属地上限
  outpost-max-count: 3                   # 1 级全服基础属地上限，-1 无限
  outpost-max-count-per-world: 3         # 1 级单世界基础属地上限，-1 无限
  outpost-max-horizontal-size: 2000
  outpost-min-size-x: 4
  outpost-min-size-y: 4
  outpost-min-size-z: 4
  outpost-max-size-y: -1
  outpost-no-higher-than: 400
  outpost-no-lower-than: -100
  outpost-auto-include-vertical: false
  outpost-pricing:
    enable: true
    price-per-block-500-to-1000: 0.0133
    price-per-block-1000-to-1500: 0.0133
    price-per-block-1500-to-2000: 0.0133
    refund-rate: 0.85

  disband-policy: dissolve-all           # dissolve-all: 全部 Outpost 随镇解散（默认）
                                         # return-contributed: 仅无 contributor 的解散，
                                         #   存量地返还 contributor

  trust:
    enabled: true
```

`max-memberships` 只检查 TownMember，不检查 TownTrust、邀请中玩家或访客。

## 9. 实施顺序

### Phase 1（一次发布）

1. 幂等 DDL：`town` / `town_member` / `town_trust` /
   `dominion_member_archive` 建表，`dominion` 加列，
   走现有 `DatabaseMigration` 启动流程；
2. Town 命令组：create / disband / join / leave / kick / transfer /
   trust / untrust / release（admin）；
3. 挂靠与成员并入逻辑（§3 操作矩阵 + §4.2）；
4. 镇属 Outpost 判定链接管（§5.1）+ 次级管理员；
5. 全局禁建个人领地 + 禁建子 Dominion + `max-memberships`；
   同步移除 `autoCleanAfterDays` 自动清理功能（§12.2）；
6. **前置项：TownBank（镇账户存取命令）**——Vault 集成已存在
   （`VaultConnect`），新增的只是镇余额与镇账户扣费路径，镇扩张计费已需要；
   ~~内建备份扩展~~ **已完成**：内建 CSV 备份已重写为动态全表导出 +
   schema 漂移容错导入（见 §11），town 新表自动纳入备份，无需逐表登记；
7. 镇扩张：远程新建 + 镇属 Outpost resize（§4.6，扣费与上限校验）；
8. 无镇 Outpost 判定链零改动。

### Phase 2（后续发布）

1. `town_claim` 建表；
2. Chunk 圈地：首块免费、四方向相邻扩张、TownBank 扣费、
   新 Chunk 不与任何 Outpost 占用 Chunk 重叠；
3. Chunk 判定链复用镇级级联（无"保留成员覆盖"层）；
4. `/town migrate-base`：preview（Chunk 数、边界扩大、子区摊平、冲突）
   → 事务转换 → 保留 `source_dominion_id` 可回滚。
   Outpost 与 Founding Outpost 走同一管线。
5. **扩展 `/dom export mca`**：现实现只遍历 dominion cuboid，chunk 领地
   不在内——不补的话世界裁剪白名单会漏保新镇地。把 `town_claim` 的
   chunk 一并换算进 MCA 区域列表（LegacyBase 未迁移的 cuboid 已被
   现有逻辑覆盖）。
6. **角色管理命令**：promote/demote（RESIDENT↔ASSISTANT）。Phase 1 里
   助理只能由镇长转让产生，且镇长不能踢助理（仅管理员可）；
7. **API 镇感知**：`TownDTO`/`getTownId` 或 `isTownOutpost` 暴露、
   Town 事件族（attach/detach/disband 专属事件）、开发者文档登记
   "getOwner 在镇属地返回 contributor"的语义变更；
8. **PAPI 成员占位符映射**：`%dominion_is_member%/members/member_count%`
   在镇属地上当前返回空，应映射到镇成员；
9. **归档表 schema 纪律**：给 dominion/dominion_member 新增任何
   非 flag 列时必须同步 dominion_archive/dominion_member_archive
   的 DDL（归档 INSERT..SELECT 按列名动态生成，列缺失即 SQL 错误）；
10. **多镇命令消歧**：`maxMemberships>1` 时给 deposit/withdraw/disband
    等子命令加可选 `[town]` 参数（Phase 1 以角色优先排序缓解）。

## 10. 测试场景

挂靠与生命周期：

- 建镇/入镇确认清单完整，确认后全部 Dominion 树转为镇所有；
- 退镇/被踢后领地留镇、contributor 记录完整、admin release 可返还；
- mayor 转让后 Founding 与 Outpost 判定不变、零 Dominion 写入；
- 镇解散后全部 Outpost（Founding + 他人贡献的 legacy 地）同步解散、
  不碰方块、归档可恢复；新人无任何路径持有无镇领地（不变量 5）；
- 无镇 Outpost 转让：受让人在镇则自动挂靠，无镇则保持无镇。

成员并入：

- 同一玩家在多块领地的成员记录并入后只计一个 TownMember；
- 已达 max-memberships 的旧成员不并入，转为该 Outpost 的 Trust，
  四布尔与 §4.5 映射表逐点一致，旧 ADMIN flag 不被转换；
- 被并入/被转换者的原成员行进入 archive，可恢复；
- 挂靠后该 Outpost 的 dominion_member 覆盖层为空；
- `merge-members-on-attach: false` 时旧成员全部转为 Trust。

权限：

- 镇属 Outpost：mayor/assistant 全权，次级管理员边界正确
  （能改 flags、不能 release/删除），次级管理员退镇自动清空；
- Trust 不占名额，scope=TOWN 与 scope=OUTPOST 判定正确；
- 未挂靠无镇 Outpost 判定链与升级前逐点一致；
- 飞地（他人子 Dominion 在镇属 Outpost 内）判定不变；
- 禁止新建个人领地/子 Dominion；系统（root）Dominion 豁免挂靠。

扩张：

- 镇属 Outpost resize 生效并扣费；无镇 legacy 地 resize 仍被拒绝；
- 远程新建 Outpost 成功并扣 TownBank；重叠/超尺寸/超数量/余额不足被拒绝；
- `outpost-allow-remote: false` 时新建被拒、扩建仍可用；
- 新建 Outpost 出生即镇属：个人不可转让、解散随镇消亡；
- Phase 2 chunk claim 仍要求相邻（网格概念回归）。

一致性：

- 重启后 Town/成员/Trust/挂靠与数据库一致；
- 多服环境下 town_id 变更同步生效（先验证 §7.4 的失效传输行为）；
- 内建 CSV 导出/导入往返后 town 全部新表数据完整、外键顺序正确；
- 回滚旧 JAR 后无镇 Outpost 行为不变，镇属 Outpost 退回旧链可预期。

## 11. 上线与回滚

Phase 1 无维护窗口。上线前常规整库备份：

```bash
mysqldump --single-transaction --routines --triggers \
  --hex-blob --no-tablespaces --default-character-set=utf8mb4 \
  --databases <db> > dominion_pre_town_$(date +%Y%m%d).sql
```

上线（多服环境）：

1. 先只更新并启动**一台**后端服，等启动 DDL 完成
   （避免多服并发 ALTER 互踩；大表加列短暂锁表，建议低峰）；
2. 确认新表新列就绪后，再滚动更新其余后端服
   （旧版代码对新列新表透明，滚动期间可共存）。

备份：文档 §11 的 mysqldump 命令覆盖整库——所有后端服共享一个数据库，
`--databases` + `--single-transaction` 一次导出全部表（含 server_info、
tp_cache 与全部 town 新表），多服同时写入也是一致性快照。

注意（内建 CSV 备份新语义，已实现并经两轮独立 review）：

- 导出：白名单制，只导出插件自有表（PLUGIN_TABLES，含 town 时代预置名），
  共享库上其他插件的表永不触碰；排除瞬态 tp_cache；
  优先按 id 列升序（dominion 自引用 FK 的父先子后保证）；
- 导入 = 真正的恢复，且**单连接单事务原子执行**：先全量严格解析
  （唯一严格入口 parseTable，损坏文件在碰库前中止），再同事务内
  逆序清空 + 正序插入，失败整体回滚；
- 导入侧同一张白名单：备份目录里的外来/未知 csv 一律跳过；
  核心表（player_name/dominion/dominion_group/dominion_member/
  privilege_template）缺文件直接中止；
- 跨版本容错：未知表/未知列警告跳过；SQL NULL 以 \N 标记往返
  （字面 "\N" 强制加引号区分）；旧格式裸引号值原样还原，
  未闭合引号响亮中止；PG 序列导入后自动 setval 重同步；
- 世界 UID 锚点改为 **`world_map(server_id, name, uid)` 表**（白名单
  备份内含）：各后端服启动时登记自己加载的世界，共享库自动攒齐全网
  映射；启动/WorldLoadEvent（晚加载世界）/导入后三处对账，发现世界
  同名重建（实际 UID ≠ 库存 UID）即自动把本服名下领地改写到新
  UID——UID 漂移"重启即修复"，与备份恢复解耦；其余服重启时各自
  对账（server_id 隔离）；旧备份（仅 yml 锚点）导入时先桥接进
  world_map 再走同一自愈路径；`migrate_db` 映射源改为
  world_map.csv 优先、yml 兜底、缺失仅告警不中止；
  Phase 2 需把 `town_claim` 加入 `WorldMapManager.WORLD_UID_TABLES`；
- 只刷新本服缓存，其余后端服不管——多服环境请在维护模式执行导入。

回滚：

1. 换回旧 JAR（新表新列对旧代码透明）；
   注：`town.founder_uuid/mayor_uuid` **不设外键**（5.0.0 起），
   旧 JAR 的 autoClean 删除 player_name 行不会级联灭镇；
2. 如需彻底回退：drop 四张新表 + `dominion` 新列，
   并从 `dominion_member_archive` 恢复被并入成员；
3. 数据库级回退才需要恢复备份（优先恢复到新库名验证，不直接 DROP 原库）；
   恢复镇时代之前的备份时，5.0.0 会显式告警并在同一事务内清空
   town/town_member/town_trust 三表（不依赖 FK 级联，三方言终态一致）。

## 12. 传统配置融入分析

现有配置逐项对照新模型。总原则：**小镇土地规则集中到主配置的 `town`；
limitations 仅保留旧个人领地与玩家传送兼容。**

### 12.1 Limitation（world-limitations）

| 字段 | 传统作用 | 新模型 |
|---|---|---|
| `noHigherThan` / `noLowerThan` | Y 范围约束 | 仅用于旧个人领地兼容；镇属规则迁到 `town.outpost-no-*` |
| `sizeMax/MinXYZ`、`autoIncludeVertical` | 尺寸约束 | 仅用于旧个人领地兼容；镇属规则迁到 `town.outpost-*` |
| `amount`（per-world）、`amountAllOverTheWorld` | 每玩家创建配额 | 仅用于旧个人领地兼容；镇属数量由小镇等级和 `town.outpost-max-count*` 控制 |
| `maxSubDominionDepth`、`doNotCountSubs` | 子区深度/计数 | 仅为重新开放旧子区创建保留 |
| `priority`（多 limitation 文件优先级） | VIP 阶梯选择 | 仅选择旧个人领地/传送配置，不再影响镇土地 |
| `economy`（pricePerBlock/squareOnly/refundRate） | 个人创建计费 | 仅用于旧个人领地兼容；镇属计费迁到 `town.outpost-pricing` |
| `teleportation` | /dom tp 冷却 | 正交不动；`/town spawn` 类为后续特性 |

Vault 集成已存在（`utils/VaultConnect/`，支持 Vault 与 VaultUnlocked），
Phase 1 只需新增 TownBank（镇余额 + 存取命令 + 镇账户扣费），无需新 hook。

### 12.2 Configuration（主配置）

| 设置 | 新模型 |
|---|---|
| `autoCreateRadius`（新玩家自动圈地） | **必须设为 -1**：它创建的是个人领地，不禁会击穿不变量 5；加入上线检查清单 |
| `autoCleanAfterDays`（不活跃清理） | **Phase 1 直接移除该功能**（配置项 + `Others.autoClean()` +
  启动调用 + 语言文本）：以后不存在按日期自动清理，镇模型无需任何跳过逻辑；
  无镇 legacy 之后仅靠玩家手动删除收敛（§7.2 的慢收敛预期不变） |
| `serverSpawnProtectionRadius` | 保留，镇创建/新建同样校验 |
| `adminBypass` | 保留，`bypassLimit` 对 max-memberships 与禁建规则同样生效 |
| `groupTitle` | 正交；镇名进 tab 为后续可选 |
| `pluginMessage` | 镇属 Outpost 的 `{OWNER}` 显示镇名（小改） |
| `residenceMigration` | 上线后建议关闭；若开放，导入产物按 legacy 无镇地处理 |
| `defaultUiType` / `selectTool` / `infoTool` / `flyPermissionNodes` / `checkUpdate` | 正交不动 |

### 12.3 不动的部分

- **WorldWide**（per-world 荒野 flags）：正交，判定顺序不变；
- **flags.yml**：flag 默认值/启用不变，兼作 Outpost"镇成员默认 flags"
  的初始模板来源；
- **multi-server / database**：不动，server-id 语义不改。

## 13. 镇时代 UI 设计（TUI/CUI 改造；**已实现，5.0.0**）

实现落点（对照 §13.7）：四步合并一次落地——`uis/town/` 六页
（TownList/TownInfo/TownMembers/TownTrusts/TownBank/TownOutposts，
TUI/CUI/Console 三端），DominionList 镇属分区（树内 `[镇名]` 标记 +
"我管理的镇属地"跨服段），DominionManage 镇属变体（Member→Trust、
Delete→次级管理员/release），MainMenu 镇入口（玩家建镇引导、管理员
保留旧建地入口），裸 `/town` 直接开 UI；promote/demote 命令随 UI-3
前置落地。CUI 新节文本依赖配置回写（Java 默认英文；zh_cn 翻译属
既有债务，与 jp/zh_tw 同批后补）。

### 13.1 现状问题

1. **DominionList 只显本服**:数据源是本服 DominionCache
   (`getPlayerOwnDominionDTOs(owner)` + `getPlayerAdminDominionDTOs`
   member-ADMIN 行),跨服领地不可见;
2. **镇属地混入"我的领地"**:contributor 仍是 owner,老列表照列,
   但点进去所有管理操作被 handler 拒(死路);
3. **次级管理员的前哨不出现**:非 owner、无 member 行,任何列表
   都看不到自己管的地;
4. **镇零 UI**:镇列表/信息/成员/Trust/银行/前哨/解散全部只有命令;
5. **MainMenu"创建领地"是死路**(personalCreationDisabled);
6. MemberList/GroupList 在镇属地上是空列表死路(Info/SetSize 已在
   Phase 1 适配)。

### 13.2 设计原则

- **TUI/CUI 镜像**:沿用 AbstractUI 双轨,不引入第三套;
- **按角色渲染,不按错误拦截**:渲染前统一过一次角色判定
  (mayor / assistant / outpost-admin / member / outsider),按钮集
  由角色决定,消灭"点进去才报错"的死路;
- **老路径零改动**:无镇领地的所有 UI 逐像素不变;
- **UI 只是入口**:一切写操作仍走现有命令/handler 路径,UI 层不
  新增写通道、不绕过二次确认(解散确认清单在 UI 同样强制)。

### 13.3 信息架构

```text
MainMenu
├── [我的小镇]      ← 新增;多镇时先进 TownList 选择
│   └── TownInfo(镇名/镇长/余额/成员数/前哨数)
│       ├── MemberList(town)   成员:邀请/踢出/转让镇长(角色过滤)
│       ├── TrustList          按 scope 分组:镇级 | 各前哨;trust/untrust
│       ├── Bank               余额 + 存/取(取仅镇长/助理)
│       ├── OutpostList        全镇前哨(跨服,DB 分页)→ OutpostManage
│       └── Disband            红色确认页(列贡献者清单,同命令口径)
├── [小镇列表]      ← 新增;全服分页,只读信息入口(玩家)
├── [我的领地]      ← DominionList 改造(§13.4)
├── 其余行不变(头衔/模板/迁移/管理员工具)
└── 管理员区(互不混合的两个入口)
    ├── [所有小镇]    ← TownList 管理员模式:含解散仲裁、银行/成员查看
    └── [LIST ALL]   ← 现有 AllDominion(所有 Outpost/领地,本服);
                       行上加 [镇名] 标记,镇属 Outpost 的管理页给
                       管理员 [release] 仲裁按钮（/town release，二次确认）
```

OutpostManage = 现有 DominionManage 的镇属变体:

| 行 | 无镇 | 镇属 Outpost |
|---|---|---|
| Info / EnvFlags / GuestFlags | 不变 | 不变(env/guest flags 仍挂在 dominion 上,有效) |
| SetSize | owner/admin | 仅镇长/助理(handler 口径) |
| MemberList | 正常 | **隐藏**,替换为 [Trust 管理] 入口 |
| GroupList | 正常 | 只读:`@town` 组可进 GroupFlags 改 flags(镇长/助理);建组/改名/删除按钮隐藏 |
| SetTp / Rename / 进出消息 | 不变 | 不变(仍 dominion 级属性) |
| Delete / Transfer | 正常 | **隐藏**(镇属不可删不可转) |
| — | — | 新增 [次级管理员] 行(镇长/助理:任命/清除) |
| — | — | 新增 [release] 行(仅管理员:仲裁剥离;Founding 不可) |

### 13.4 DominionList 三段式

| 分组 | 数据源 | 行内操作 |
|---|---|---|
| 我管理的镇属地 | TownCache 反查(mayor/assistant 的镇 + 任次级管理员的前哨),跨服 | 进 OutpostManage(角色过滤) |
| 我的无镇领地 | 原 owner 查询,过滤 `isTownOutpost()` | 不变 |
| 我贡献的镇属地 | owner 查询中 `isTownOutpost()` 的部分 | 只读:Info/传送;行尾标 `[已挂靠:镇名]` |

行后缀统一标 `[镇名]` / `[无镇]`;跨服条目加服务器标签
(`server_id`;如需友好名,另立 server-name 映射,不在本期)。

### 13.5 跨服数据策略

- 镇列表/镇信息/成员/Trust:读本服 TownCache 副本(60s 收敛)即可,
  不为 UI 加实时通道;
- 前哨清单:`selectByTownId` 无分页 → 新增 DB 分页查询
  (按 id 排序 offset/limit),总数用现有 COUNT 查询;
- UI 打开时不触发写后刷新以外的额外 reload,避免镇长连点刷库。

### 13.6 角色 × 可见性矩阵(镇属地)

| 功能 | 镇长/助理 | 次级管理员 | 成员 | 游客 |
|---|---|---|---|---|
| Info/Env/Guest 查看 | ✓ | ✓ | ✓ | ✓ |
| flags 编辑(@town 组/guest/env) | ✓ | ✗ | ✗ | ✗ |
| resize | ✓ | ✗ | ✗ | ✗ |
| Trust(本前哨) | ✓ | ✓ | ✗ | ✗ |

| Trust(镇级)/成员/银行取/任命/解散 | ✓ | ✗ | ✗ | ✗ |
| 删除单块属地 | ✓（二次确认） | ✗ | ✗ | ✗ |
| 转让属地 | 仅服务器管理员 `give force` 仲裁 | ✗ | ✗ | ✗ |

> 实现注记：次级管理员经 `assertDominionAdmin`(isOutpostManager)实际可
> 编辑本前哨的 env/guest/`@town` flags——与上表"✗"的有意偏离（本前哨
> 的 flags 管理合乎次级管理员职责直觉，后端语义不再收紧）。resize 入口
> 对次级管理员在 UI 层隐藏（§13.3）。另：镇前哨清单未做 DB 分页——
> 与全插件所有列表页一致走 UI 内存分页（前哨数受数量上限约束）。

### 13.7 依赖与拆分

- **UI-1**:TownList + TownInfo 只读骨架(无操作,纯展示);
- **UI-2**:DominionList 三段式 + OutpostManage 镇属变体;
- **UI-3**:成员/Trust/银行操作页;promote/demote 按钮依赖 §9
  Phase 2 第 6 项命令先落地(否则 UI 只读);
- **UI-4**:MainMenu 入口替换([创建领地]→[创建小镇] 引导:
  有地→显示挂靠确认清单;无地→提示先选区;admin 保留旧入口);
- 文案:`town-*` 系列进 TextUserInterface/ChestUserInterface,
  四语言(jp/zh_tw 英文兜底惯例);
- 每步独立可发布,互不阻塞。
