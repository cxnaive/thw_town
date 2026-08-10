# Dominion 插件开发进度

## 项目概述

**Dominion** - Minecraft 领地保护插件 (1.20.1+)
- 版本: 4.7.0-beta
- 构建: Gradle 8.8 + Kotlin DSL
- Java: 17 (core) / 21 (v1_21 模块)

---

## 本次开发：新增"使用地图"权限

### 功能描述

添加 `USE_MAP` 权限标志，控制玩家在领地内使用空地图创建地图的权限。

### 修改的文件

| 文件 | 修改内容 |
|------|----------|
| `api/src/main/java/cn/lunadeer/dominion/api/dtos/flag/Flags.java:115` | 添加 `USE_MAP` PriFlag |
| `v1_20_1/src/main/java/cn/lunadeer/dominion/v1_20_1/events/player/Using/UseMap.java` | 创建事件处理器 (1.20.1) |
| `v1_21/src/main/java/cn/lunadeer/dominion/v1_21/events/player/Using/UseMap.java` | 创建事件处理器 (1.21) |
| `languages/en_us.yml:523` | 英文翻译 |
| `languages/zh_cn.yml:473` | 简体中文翻译 |
| `languages/zh_tw.yml:553` | 繁体中文翻译 |
| `languages/jp_jp.yml:523` | 日文翻译 |

### 权限标志定义

```java
// Flags.java:115
public static final PriFlag USE_MAP =
        new PriFlag("use_map", "Use Map", "Whether player can use empty map to create map in dominion.", false, true, Material.MAP);
```

| 属性 | 值 | 说明 |
|------|-----|------|
| flag_name | `use_map` | 数据库列名 |
| display_name | `Use Map` | 显示名称 |
| default_value | `false` | 默认禁止 |
| enable | `true` | 启用该权限 |
| material | `MAP` | GUI 图标 |

### 事件处理器核心逻辑

```java
@EventHandler(priority = EventPriority.LOWEST)
public void onInteract(PlayerInteractEvent event) {
    // 1. 只处理右键
    if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
        return;
    }

    // 2. 检查是否已被其他插件禁止
    if (event.useItemInHand() == Event.Result.DENY) {
        return;
    }

    // 3. 方块交互取消检查
    if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.isCancelled()) {
        return;
    }

    // 4. 获取手持物品
    ItemStack item = event.getHand() == EquipmentSlot.HAND
        ? inventory.getItemInMainHand()
        : inventory.getItemInOffHand();

    // 5. 检查是否为空地图
    if (item == null || item.getType() != Material.MAP) {
        return;
    }

    // 6. 权限检查
    boolean allowed = checkPrivilegeFlag(location, Flags.USE_MAP, player, event);

    // 7. 无权限时拦截
    if (!allowed) {
        event.setUseItemInHand(Event.Result.DENY);  // 关键：禁止物品使用
        event.setCancelled(true);
        event.getPlayer().updateInventory();         // 修复客户端预测
    }
}
```

### 关键技术点

**问题**: 对着空气右键使用空地图，`PlayerInteractEvent` 无法通过简单的 `setCancelled()` 拦截。

**解决方案**: 三重拦截机制
1. `event.setUseItemInHand(Event.Result.DENY)` - 显式禁止物品使用
2. `event.setCancelled(true)` - 通用事件取消
3. `event.getPlayer().updateInventory()` - 修复客户端预测导致的视觉 Bug

### 数据库自动迁移

首次启动时自动添加列：
```sql
ALTER TABLE dominion_member ADD COLUMN use_map BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE dominion_group ADD COLUMN use_map BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE privilege_template ADD COLUMN use_map BOOLEAN NOT NULL DEFAULT false;
```

### 本地化翻译

| 语言 | display-name | description |
|------|--------------|-------------|
| English | Use Map | Whether player can use empty map to create map in dominion. |
| 简体中文 | 使用地图 | 是否可以使用空白地图创建地图 |
| 繁體中文 | 使用地圖 | 是否可以使用空白地圖創建地圖 |
| 日本語 | 地図を使用 | 空白地図で地図を作成できるかどうか |

### 配置文件

首次启动后在 `plugins/Dominion/flags.yml` 自动生成：
```yaml
privilege:
  use_map:
    enable: true
    default: false
    material: "MAP"
```

### 构建命令

```bash
# Lite 版本（依赖需服务器单独安装）
./gradlew shadowJar

# Full 版本（包含所有依赖）
./gradlew shadowJar -PBuildFull=true
```

输出: `build/libs/Dominion-4.7.0-beta-full.jar` (7.5 MB)

---

## 项目结构总结

```
Dominion/
├── api/                   → DominionAPI (外部调用)
├── core/                  → 核心功能
│   ├── configuration/     → 配置管理
│   ├── cache/            → 缓存系统
│   ├── doos/             → 数据对象 (DominionDOO, MemberDOO, GroupDOO)
│   ├── events/           → 事件注册
│   └── commands/         → 命令处理
├── v1_20_1/              → Minecraft 1.20.1-1.20.6 兼容
│   └── events/player/Using/UseMap.java
└── v1_21/                → Minecraft 1.21.x 兼容
    └── events/player/Using/UseMap.java
```

---

## 权限系统架构

### Flag 类型
- **PriFlag**: 玩家权限 (place, break, move, etc.)
- **EnvFlag**: 环境标志 (animal_spawn, tnt_explode, etc.)

### 权限检查流程
```
PlayerInteractEvent → checkPrivilegeFlag() → checkPrivilegeFlagSilence()
                                               │
                       ┌───────────────────────┼───────────────────────┐
                       │                       │                       │
                   flag.getEnable()    CacheManager.getDominion()  WorldWide
                   (跳过检查)           (领地权限)              (全局设置)
                       │                       │                       │
                   return true           Admin/Group/Member     Guest Flag
                                           / Guest
```

### 禁用某个权限的方法

在 `plugins/Dominion/flags.yml` 中设置：
```yaml
privilege:
  use_map:
    enable: false  # 禁用后跳过权限检查，所有玩家允许
```

---

## 数据库架构

### 主要表结构
- `player_name` - 玩家信息
- `dominion` - 领地信息
- `dominion_member` - 领地成员
- `dominion_group` - 领地组
- `privilege_template` - 权限模板

### 权限标志存储
每个 PriFlag 在以下表中都有对应的布尔列：
- `dominion_member` - 成员个人权限
- `dominion_group` - 组权限
- `privilege_template` - 模板权限

---

## 开发说明

### 添加新权限的步骤

1. **在 `Flags.java` 中添加 Flag 定义**
2. **创建事件处理器** (v1_20_1 和 v1_21)
3. **添加翻译** (4 种语言)
4. **重新构建** → 数据库自动迁移

### 事件注册机制

事件处理器放在对应目录下，自动注册：
- `v1_20_1/events/player/Using/` → 自动注册
- `v1_21/events/player/Using/` → 自动注册

---

## Git 状态

```
Current branch: (detached)
Status: (修改未提交)
```

### 建议的提交信息

```
feat: add USE_MAP permission flag

Add new permission flag to control empty map usage in dominions.

- Add USE_MAP PriFlag in Flags.java
- Create UseMap event handler for v1_20_1 and v1_21
- Add translations for en_us, zh_cn, zh_tw, jp_jp
- Implement triple-blocking mechanism for air click protection
```

---

*生成时间: 2025-01-20*
