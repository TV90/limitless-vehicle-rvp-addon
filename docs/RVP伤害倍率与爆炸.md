# RVP 伤害倍率与爆炸

本文说明 `damage_model_data.damage_factor` 的配置方式，以及 RVP 爆炸与本体 `VehicleExplosion` 的关系。

## 与 MCH 的差异

| 项目 | MCH `DamageFactor` | RVP `damage_factor` |
| --- | --- | --- |
| 载具分类 | 固定枚举（Plane / Tank / Heli …） | 按 Forge **实体类型 ID** 配置，如 `ywzj_vehicle:rotary_wing_vehicle` |
| 配置位置 | 武器 txt 多行 `DamageFactor = ...` | 武器 JSON `damage_model_data.damage_factor` |
| 爆炸 | 在 `onImpact` 中乘系数 | 本体 `VehicleExplosion`（波及伤害**不**乘 `damage_factor`） |
| 未列出载具 | 回退 1.0 | `vehicle_default`（默认 1.0） |

## JSON 结构

写在 `damage_model_data` 内，与 `direct`、`decay` 同级：

```json
"damage_model_data": {
  "direct": 90,
  "damage_factor": {
    "player": 1.0,
    "living": 1.0,
    "vehicle_default": 0.8,
    "vehicles": {
      "ywzj_vehicle:rotary_wing_vehicle": 0.5,
      "ywzj_vehicle:fixed_wing_vehicle": 0.6,
      "ywzj_vehicle:tracked_vehicle": 1.2,
      "ywzj_vehicle:wheeled_vehicle": 1.0
    }
  }
}
```

| 字段 | 说明 |
| --- | --- |
| `player` | 对 `Player` 的倍率，默认 `1` |
| `living` | 对非玩家 `LivingEntity`（步兵、动物等）的倍率，默认 `1` |
| `vehicle_default` | 对 `AbstractVehicle` 且未在 `vehicles` 中单独列出时的倍率，默认 `1` |
| `vehicles` | 键为完整实体类型 ID（`namespace:path`），值为倍率 |

**判定顺序**（互斥）：

1. `Player` → `player`
2. `AbstractVehicle` → 查 `vehicles`，否则 `vehicle_default`
3. 其他 `LivingEntity` → `living`
4. 其余实体（掉落物、抛射物等）→ `1`（不缩放）

倍率 ≤ 0 时按 0 处理；`NaN` / 无穷按 1 处理。

## `damage_factor` 生效范围

下列伤害会在**基础伤害**（含距离 `decay`、爆头等）算出之后，再乘以 `getFactor(target)`：

| 场景 | 代码入口 |
| --- | --- |
| 弹体直击 | `RVP_BaseBullet#handleEntityImpact` |
| 激光 | `RVP_LaserWeapon#shoot` |
| 近炸直伤 | `RVP_BaseBullet#detonateFuseAt`（`proximity_fuse_damage`） |

**不缩放**：`detonate_data.explosion_data` 触发的 `VehicleExplosion` 波及伤害（与本体一致）。

未配置 `damage_factor` 或全部为默认 1 时，直击等行为与未引入 RVP 倍率前相同。

## 爆炸

- 配置：`detonate_data.explosion_data`（`RVP_Explosion`，继承本体 `Explosion` POJO）。
- 运行时：`RVP_BaseBullet#triggerExplosion` → `new VehicleExplosion(...)`，方块破坏、粒子、音效与本体一致。

## 代码结构

```
org.ywzj.rvp.weapon.data.RVP_DamageFactor     # 数据类 + getFactor(Entity)
org.ywzj.rvp.weapon.damage.RVP_DamageApplier  # 直击 / 近炸直伤
org.ywzj.rvp.weapon.data.RVP_Explosion        # 配置 POJO，继承本体 Explosion
org.ywzj.vehicle.util.VehicleExplosion        # 运行时爆炸（本体）
```

## 常见本体载具实体类型 ID

（以 `ywzj_vehicle` 注册名为准，具体以游戏内 `/forge entity list` 或 `AllEntities` 为准。）

| ID | 用途 |
| --- | --- |
| `ywzj_vehicle:rotary_wing_vehicle` | 旋翼机基类 |
| `ywzj_vehicle:fixed_wing_vehicle` | 固定翼基类 |
| `ywzj_vehicle:tracked_vehicle` | 履带车辆基类 |
| `ywzj_vehicle:wheeled_vehicle` | 轮式车辆基类 |
| `ywzj_vehicle:ah64d` | 具体机型（若单独注册） |

具体载具若继承上述基类，则使用**实体类型**对应的 ID，而不是载具数据包里的 `vehicle` 字符串。

## 移植示例（MCH txt）

MCH：

```
DamageFactor = living 1.0
DamageFactor = player 0.5
DamageFactor = heli 0.3
```

RVP（旋翼机用本体实体类型）：

```json
"damage_factor": {
  "player": 0.5,
  "living": 1.0,
  "vehicles": {
    "ywzj_vehicle:rotary_wing_vehicle": 0.3
  }
}
```

## 测试建议

1. 对玩家 / 僵尸 / 旋翼机各打一发，观察直击伤害是否符合倍率。
2. 仅改 `vehicles` 中一项，确认 `vehicle_default` 与单独项优先级。
3. 爆炸弹：确认波及伤害与本体 `VehicleExplosion` 一致（不受 `damage_factor` 影响）。
