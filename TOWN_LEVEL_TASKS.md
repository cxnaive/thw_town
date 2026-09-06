# 小镇共同任务：开放至 5 级

成员可分批共同提交物品，进度达到要求后自动晋升，无额外金币条件。

| 晋升 | 共同提交总量 | 晋升后的属地上限（默认配置） |
| --- | --- | --- |
| 1 → 2 | 9 个下界合金锭（NETHERITE_INGOT） | 5 |
| 2 → 3 | 32 个幽匿催发体（SCULK_CATALYST） | 7 |
| 3 → 4 | 64 个回响碎片（ECHO_SHARD） | 9 |
| 4 → 5 | 8 个沉重核心（HEAVY_CORE） | 11 |

默认 1 级有 3 个属地名额，每级增加 2 个；已有超限属地不受影响。
系统最高等级仍为 10，OP 可以手动设置等级；成员任务默认只开放到 5。

## 新安装

新生成的 config.yml 默认包含 2～5 级任务，town.progression-max-level 默认为 5。

## 已有服务器升级

已有的 town.level-requirements 整节保留，已有的 progression-max-level 也不会被覆盖。
仅替换 JAR 不会自动将旧配置的开放上限从 3 改成 5。

在每个子服的 plugins/Dominion/config.yml 中：

1. 将已有的 town.progression-max-level 改为 5，并确认 town.max-level 不低于 5（默认是 10）。
2. 在已有的 town.level-requirements 下添加目标等级 4、5；保留原有 2、3 级任务。
3. 保持所有子服的等级任务配置一致，使用新 JAR 重启子服。

以下仅为合并片段，不要用它替换整个 config.yml 或整个 level-requirements，也不要重复添加 town 键：

```yaml
town:
  progression-max-level: 5
  level-requirements:
    # 保留此处原有的 2、3 级及其他自定义任务
    4:
      type: item
      material: ECHO_SHARD
      amount: 64
    5:
      type: item
      material: HEAVY_CORE
      amount: 8
```

无需删除配置、语言文件或数据库。新增物品名翻译会由现有语言加载器补齐。
这次修改不重置现有小镇等级或任务进度，也不会让 3 级小镇自动升到 5 级。

沉重核心需要 Minecraft 1.21+。1.20.1 子服会跳过该物品任务，无法在该子服提交 5 级任务。
