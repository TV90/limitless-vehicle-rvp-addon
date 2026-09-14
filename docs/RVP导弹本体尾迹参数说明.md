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
| `missile_native_trail_particle` | 原生尾迹主烟柱使用的粒子。未写时兼容回退到 `trajectory_particle`，再回退到本体默认烟粒。支持 `minecraft:campfire_signal_smoke` / `minecraft:campfire_cosy_smoke` / `minecraft:smoke` / `minecraft:large_smoke` / `minecraft:cloud` / `none`（`rvp_rocket_flame` 风格下可配 `none`）。 | 回退 |
| `missile_native_trail_step` | 尾迹插值步长，单位格。值越大，烟柱越稀。 | `0.5` |
| `missile_native_trail_spawn_interval_tick` | 每隔多少 tick 生成一次主烟柱。`1` 表示每 tick。值越大，烟越淡。 | `1` |
| `missile_native_trail_density_scale` | 主烟柱密度缩放。`1.0` 为当前默认密度；`0.5` 约为减半；`0` 表示关闭主烟柱。 | `1.0` |
| `missile_native_trail_offset` | 尾迹生成点相对弹体沿后向的偏移距离，单位格。 | `3.0` |
| `missile_native_trail_particle_scale` | 尾迹粒子渲染尺寸倍率。与 `density_scale`（加数量）互补，本项放大**单个粒子**的渲染尺寸，是"变粗"的直接手段。 | `1.0` |
| `missile_native_trail_particle_style` | 尾迹粒子风格：空 = 原版粒子；`rvp_smoke` = MCHR 风格翻滚烟团；`rvp_rocket_flame` = HBM 风格火箭尾焰（先火后烟柱状尾迹，详见下节）。 | 空 |
| `missile_native_trail_ground_wash` | 发射段贴地烟浪开关。未写时按风格推导：`rvp_rocket_flame` 开启、其余关闭。 | 按风格 |
| `missile_native_trail_launch_boost` | 发射段烟柱加粗倍率。只影响发射段：在一级燃烧窗口内随飞行进度线性回落到 1.0，发射时全额加粗、一级燃尽恢复常规粗细。最终尺寸 = `particle_scale` × 本倍率（随进度衰减）。 | `1.0` |
| `missile_native_trail_extra_flame` | ⚠️ 当前**无消费者**（历史上曾控制额外 `FLAME` 粒子，2026-09-04 优化头瞄后链路已删），配置无效。 | — |
| `missile_native_trail_extra_smoke` | ⚠️ 同上，当前**无消费者**，配置无效。 | — |

## 运行规则

1. 这些参数只对 `rvp:missile` 生效。
2. 仅在导弹发动机燃烧期内生效。
3. `missile_native_trail_particle` 优先级高于 `trajectory_particle`。
4. 若 `missile_native_trail_enabled=false`，则整条原生尾迹（含烟浪）关闭。
5. 若 `missile_native_trail_density_scale=0`，则只关闭主烟柱，不影响贴地烟浪。
6. **配置了任一非空 `missile_native_trail_particle_style` 的导弹，服务端广播尾迹
   （`trajectory_particle` 烟 + 燃烧期尾焰粒子）自动跳过**，避免客户端本地尾迹与广播粒子
   两路视觉叠加（2026-09-15 起，无风格配置的弹不受影响）。

## HBM 风格火箭尾焰（`rvp_rocket_flame`）

从 HBM（Hbm's Nuclear Tech Mod）`ParticleRocketFlame` / `ParticleSmokePlume` 移植的弹道导弹
尾迹观感：寿命前 25% 亮橙火焰团 → 深灰烟持续膨胀（~0.35 → 2.6 × `particle_scale`）、
α=√(1-age/maxAge)×0.75 缓出淡出、初速沿弹轴反方向喷出（阻尼 0.91/tick）、
3 层抖动 quad 伪体积感、全亮渲染。发射段另有贴地烟浪（燃烧且距地 <20 格时，
弹体地面投影点每 tick 6 粒横向冲刷灰烟，0.25 → 2.25 × `particle_scale` 膨胀带浮升）。
参数对照详见 `docs/plan/RVP弹道导弹尾迹HBM风格移植方案_20260915.md`。

### 9M723（伊斯坎德尔）定稿配置

```json
"effects_data": {
  "trajectory_particle": "none",
  "missile_native_trail_particle_style": "rvp_rocket_flame",
  "missile_native_trail_particle_scale": 1.5,
  "missile_native_trail_step": 1.0,
  "missile_native_trail_offset": 3.0
}
```

调粗/调细只动 `missile_native_trail_particle_scale`（推荐 0.5~2.0）；
烟浪不想要时显式加 `"missile_native_trail_ground_wash": false`。

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
