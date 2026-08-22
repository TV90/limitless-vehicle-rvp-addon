# TOW-2B 攻顶战斗部（自锻破片）实现方案

> 需求：实现类似 TOW-2B 的攻顶战斗部——导弹**非攻顶弹道**（平飞），依靠弹上传感器在飞越目标**正上方**时触发引信，向下喷射大量自锻破片/霰弹攻击目标顶装甲。
>
> 关联总文档：[RVP包新增参数字段说明.md](./RVP武器数据模型/RVP包新增参数字段说明.md)。

## 设计目标

1. **引信侧**：`fuse_data` 新增"攻顶引信"。检测**世界系正下方**（绝对 `0,-1,0`，不随弹体姿态变化）半锥角区域内的实体，探测到后触发引信（可带延时起爆）。
2. **布撒侧**：`submunition_data` 的 payload 新增"发射角度"参数。让子弹丸不再沿母弹弹轴，而是按**相对弹体姿态**或**世界系绝对**角度（yaw/pitch）方向发射，配合现有 canister 霰弹散布实现"头顶向下喷一坨破片"。

## 参数设计

### 1. `fuse_data` 攻顶引信

| 字段 | 类型 | 默认 | 含义 |
| --- | --- | --- | --- |
| `top_attack_fuse_enabled` | bool | `false` | 是否启用攻顶引信；不写=关闭，向后兼容。 |
| `top_attack_fuse_distance` | float(米) | `6.0` | 从弹体向正下方的最大检测距离（球半径）。 |
| `top_attack_fuse_fov` | float(度) | `25.0` | 检测**半锥角**：实体中心与正下方方向的偏移角 ≤ 该值才命中。 |
| `top_attack_fuse_delay_tick` | int | `0` | 探测到目标后延时起爆的 tick 数；用于让导弹飞过头顶一定距离再炸。延时期间目标离开锥内也会按时起爆。 |
| `top_attack_fuse_arm_tick` | int | `0` | 解保 tick：发射后经过该 tick 才启用检测；防贴地/近地发射误触发。 |

判定几何：锥顶 = 弹体位置，锥轴 = 世界系 `(0,-1,0)`。实体中心偏移 `v` 满足：

- `v.y < 0`（确实在下方）
- `v.lengthSqr() <= distance²`
- `v.normalize().dot(0,-1,0) >= cos(fov)`（半锥角内）

目标过滤复用 `canDamageEntity`（排除己方载具/同载具弹药）。命中后调 `detonateFuseAt(position(), PROXIMITY, target)`：复用近炸全额伤害，并自动触发 `submunition_data` 的 `on_fuse` 子母弹链路。

### 2. `submunition_data` payload 发射角度

| 字段 | 类型 | 默认 | 含义 |
| --- | --- | --- | --- |
| `launch_yaw` | float(度) | `0` | 发射方向 yaw；与弹体 yRot 同约定（0=南 +Z，顺时针为正，-90=东、90=西）。 |
| `launch_pitch` | float(度) | `0` | 发射方向 pitch；与弹体 xRot 同约定（**90=正下**、-90=正上）。 |
| `launch_angle_mode` | string | `relative` | `relative`=以母弹当前姿态为基准叠加（随弹体俯仰/偏航变化）；`absolute`=世界系固定角度。 |
| `launch_speed` | float | `0` | 发射初速（格/tick）；`> 0` 直接使用该速率，`0` 使用“母弹当前速率 × `velocity_scale`”。 |

启用条件：`launch_yaw` / `launch_pitch` / `launch_speed` 至少一个非默认值。全部默认时行为与旧版完全一致（沿弹轴 + 原 spread）。

启用后不再合成 `inherit_parent_velocity` / `inherit_vehicle_velocity`；方向完全由解析后的发射角决定。canister / box spread 仍以该方向为基准叠加，之后才追加世界系 `payloads_velocity`。

### 3. JSON 示例（TOW-2B 攻顶导弹）

```json
{
  "fuse_data": {
    "top_attack_fuse_enabled": true,
    "top_attack_fuse_distance": 8,
    "top_attack_fuse_fov": 20,
    "top_attack_fuse_delay_tick": 4
  },
  "submunition_data": {
    "releases": [
      {
        "triggers": ["on_fuse"],
        "release_events": 1,
        "payloads": [
          {
            "kind": "rvp_weapon",
            "weapon_id": "efp_pellet",
            "count": 40,
            "launch_yaw": 0,
            "launch_pitch": 90,
            "launch_angle_mode": "absolute",
            "launch_speed": 6,
            "spread": {
              "mode": "canister",
              "canister_type": 1,
              "canister_diff": 0.8,
              "canister_distribution": "uniform",
              "canister_shape": "circle"
            },
            "inherit_parent_velocity": false,
            "suppress_explosion": true
          }
        ]
      }
    ]
  }
}
```

链路：导弹平飞 → 正下方锥内探测到实体 → 延时 N tick → `detonateFuseAt` → `ON_FUSE` 子母弹按绝对方向向下喷 40 颗霰弹。`parent_action: continue` 会让当前引信链继续结算爆炸；要纯破片则使用 `discard_after_release`，或不为母弹配置有效爆炸。

## 实现位置

| 文件 | 改动 |
| --- | --- |
| `org.ywzj.rvp.weapon.data.RVP_FuseData` | 新增 5 字段 + getter |
| `org.ywzj.rvp.entity.projectile.RVP_BaseBullet` | 新增 `tickTopAttackFuse()`（服务端），tick() 中 `tickProximityFuse()` 之后调用；新增延时状态字段 `topAttackTriggerTick` |
| `org.ywzj.rvp.weapon.data.RVP_SubmunitionPayloadData` | 定义 `launch_yaw`、`launch_pitch`、`launch_angle_mode`、`launch_speed` 及 getter |
| `org.ywzj.rvp.weapon.submunition.RVP_SubmunitionSpawner` | `resolveLaunchAim()` 解析发射基准角；`buildVelocity()` / spawn 朝向 / 位置偏移改用发射角 |

## 测试建议

1. 水平发射导弹飞越固定目标（生物/载具）正上方：应在锥内触发、按延时起爆、向下喷霰弹。
2. `launch_angle_mode: relative` + `launch_pitch: 90`：导弹俯冲/爬升时布撒方向应随弹体变化。
3. 不配置新参数的老武器行为应与旧版完全一致（回归）。
