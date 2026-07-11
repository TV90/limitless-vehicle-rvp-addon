# RVP 导弹本体尾迹参数说明

本文档说明 `rvp:missile` 在 `effects_data` 中可用的“本体尾迹”参数。

这些参数只影响 `RVP` 导弹当前使用的 native missile trail 逻辑，也就是对齐本体 `MissileEntity.tickParticle()` 的那条尾迹链路。

它们不会影响：

- 命中粒子
- 爆炸粒子
- 机枪曳光
- 非导弹弹体的通用尾迹逻辑

## 配置位置

写在武器 JSON 的 `effects_data` 里：

```json
"effects_data": {
  "missile_native_trail_enabled": true,
  "missile_native_trail_particle": "minecraft:campfire_signal_smoke",
  "missile_native_trail_step": 0.5,
  "missile_native_trail_spawn_interval_tick": 1,
  "missile_native_trail_density_scale": 1.0,
  "missile_native_trail_offset": 3.0,
  "missile_native_trail_extra_flame": true,
  "missile_native_trail_extra_smoke": true
}
```

## 字段说明

| 字段 | 说明 | 默认值 |
| --- | --- | --- |
| `missile_native_trail_enabled` | 是否启用导弹原生尾迹。设为 `false` 时，整条 native trail 关闭。 | `true` |
| `missile_native_trail_particle` | 原生尾迹主烟柱使用的粒子。未写时兼容回退到 `trajectory_particle`，再回退到本体默认烟粒。支持 `minecraft:campfire_signal_smoke` / `minecraft:campfire_cosy_smoke` / `minecraft:smoke` / `minecraft:large_smoke` / `minecraft:cloud` / `none`。 | 回退 |
| `missile_native_trail_step` | 尾迹插值步长，单位格。值越大，烟柱越稀。 | `0.5` |
| `missile_native_trail_spawn_interval_tick` | 每隔多少 tick 生成一次主烟柱。`1` 表示每 tick。值越大，烟越淡。 | `1` |
| `missile_native_trail_density_scale` | 主烟柱密度缩放。`1.0` 为当前默认密度；`0.5` 约为减半；`0` 表示关闭主烟柱。 | `1.0` |
| `missile_native_trail_offset` | 尾迹生成点相对弹体沿后向的偏移距离，单位格。 | `3.0` |
| `missile_native_trail_extra_flame` | 是否保留额外 `FLAME` 尾焰粒子。 | `true` |
| `missile_native_trail_extra_smoke` | 是否保留额外 `CAMPFIRE_COSY_SMOKE` 轻烟粒子。 | `true` |

## 运行规则

1. 这些参数只对 `rvp:missile` 生效。
2. 仅在导弹发动机燃烧期内生效。
3. `missile_native_trail_particle` 优先级高于 `trajectory_particle`。
4. 若 `missile_native_trail_enabled=false`，则：
   - 主烟柱关闭
   - 额外尾焰关闭
   - 额外轻烟关闭
5. 若 `missile_native_trail_density_scale=0`，则只关闭主烟柱，不影响额外尾焰/轻烟。

## AIM-9M 低烟推荐配置

```json
"effects_data": {
  "missile_native_trail_enabled": true,
  "missile_native_trail_particle": "minecraft:cloud",
  "missile_native_trail_step": 0.9,
  "missile_native_trail_spawn_interval_tick": 2,
  "missile_native_trail_density_scale": 0.45,
  "missile_native_trail_offset": 3.0,
  "missile_native_trail_extra_flame": true,
  "missile_native_trail_extra_smoke": false
}
```

这套配置的目标不是精确改原版粒子寿命，而是在保留“本体尾迹风格”的前提下，把视觉效果调成更接近低烟发动机。
