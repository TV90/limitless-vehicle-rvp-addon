# RVP 导弹本体尾迹参数说明

本文档说明 `rvp:missile` 与 `rvp:rocket` 在 `effects_data` 中可用的“本体尾迹”参数。

> **2026-10-30 更新**：`rvp_rocket_flame` 已切换为“世界轨迹 + 逐像素体积烟”，旧贴图尾迹在代码中关闭并保留。原尺寸、密度、偏移和保持/淡出字段继续使用，但密度控制烟的浓度，step 控制烟段细分；旧面片层数/抖动与橙焰时长不影响新烟。贴地烟浪也改为程序体积烟。完整说明见 [世界轨迹体积尾烟](RVP视觉工厂架构/RVP火箭世界轨迹体积尾烟实现_20261030.md)。下文旧粒子细节仅对应保留的原实现。

> 2026-10-29 起 `rvp:rocket`（无制导火箭）也可使用同一套尾迹配置（提交 `1378a8d1`
> “火箭实体的配置驱动尾迹分支”）。火箭没有制导/发动机燃烧数据，因此 `holdTicks` 恒为 0，
> 凝结云保持期完全由 `missile_native_trail_hold_min_ticks` 决定。

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
  "missile_native_trail_hold_min_ticks": 108,
  "missile_native_trail_fade_ticks": 120,
  "missile_native_trail_fade_random_ticks": 60,
  "missile_native_trail_alpha_hold_ratio": 1.0,
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
| `missile_native_trail_particle_style` | 尾迹粒子风格：空 = 原版粒子；`rvp_smoke` = MCHR 风格翻滚烟团；`rvp_rocket_flame` = HBM 固体发动机橙焰与灰白凝结云；`rvp_kerosene_black_smoke` = HBM 液氧煤油黑烟技术储备款。 | 空 |
| `missile_native_trail_ground_wash` | 发射段贴地烟浪开关。未写时按风格推导：`rvp_rocket_flame` / `rvp_kerosene_black_smoke` 开启、其余关闭。 | 按风格 |
| `missile_native_trail_launch_boost` | 发射段烟柱加粗倍率。只影响发射段：在一级燃烧窗口内随飞行进度线性回落到 1.0，发射时全额加粗、一级燃尽恢复常规粗细。最终尺寸 = `particle_scale` × 本倍率（随进度衰减）。 | `1.0` |
| `missile_native_trail_hold_min_ticks` | 凝结云**保持期保底**，单位 Tick。仅 `rvp_rocket_flame` 生效。保持期 = `max(距发动机燃尽 tick + 20, 本值)`，保持期结束才开始淡出。默认 108t（5.4 秒）。**这是缩短尾迹存留时间最直接的手段**：调小它等于让云提早开始散。 | `108` |
| `missile_native_trail_fade_ticks` | 凝结云**淡出基础时长**，单位 Tick。仅 `rvp_rocket_flame` 生效。与 `fade_random_ticks` 相加构成淡出期（smoothstep 散开）。 | `120` |
| `missile_native_trail_fade_random_ticks` | 凝结云**淡出随机增量上限**，单位 Tick。仅 `rvp_rocket_flame` 生效。每个粒子在 `[0, 本值)` 内随机延寿，让同段尾迹散开不齐；写 `0` 则完全同步消失。 | `60` |
| `missile_native_trail_alpha_hold_ratio` | 凝结云**alpha 保持期占寿命的比例**，`0~1`。仅 `rvp_rocket_flame` 生效。保持期内 alpha 恒为 `0.75`（完全不透明），保持期结束才淡出——所以**只缩短总寿命时，浓烟仍会挂满整个保持期，看着依旧很久**。本项把「浓多久」独立出来：`alpha 保持 = min(保持期, 总寿命 × 本比例)`。`1.0` = 原始行为；`0.18` = 只在前 18% 寿命浓着，其余一路淡出。 | `1.0` |
| `missile_native_trail_extra_flame` | ⚠️ 当前**无消费者**（历史上曾控制额外 `FLAME` 粒子，2026-09-04 优化头瞄后链路已删），配置无效。 | — |
| `missile_native_trail_extra_smoke` | ⚠️ 同上，当前**无消费者**，配置无效。 | — |

## 运行规则

1. 这些参数对 `rvp:missile` 与 `rvp:rocket` 生效；其余弹种（`rvp:machinegun` / `rvp:bomb` 等）走各自的尾迹逻辑。
2. 仅在导弹发动机燃烧期内生效。
3. `missile_native_trail_particle` 优先级高于 `trajectory_particle`。
4. 若 `missile_native_trail_enabled=false`，则整条原生尾迹（含烟浪）关闭。
5. 若 `missile_native_trail_density_scale=0`，则只关闭主烟柱，不影响贴地烟浪。
6. **配置了任一非空 `missile_native_trail_particle_style` 的导弹，服务端广播尾迹
   （`trajectory_particle` 烟 + 燃烧期尾焰粒子）自动跳过**，避免客户端本地尾迹与广播粒子
   两路视觉叠加（2026-09-15 起，无风格配置的弹不受影响）。

## 超视距尾迹

超出原生实体追踪距离后，专用弹药视觉广播每 5 Tick 同步远程弹体克隆和发动机剩余燃烧时间；
客户端通过克隆携带的 `weaponId` 读取同一份 `effects_data`，因此空/原版、`rvp_smoke`、
`rvp_rocket_flame`、`rvp_kerosene_black_smoke` 均保持各自风格，不再统一退化为
`minecraft:campfire_signal_smoke`。客户端缺少对应武器配置时仍安全回退到信号烟。

远距链路遵守 `missile_native_trail_enabled`、`particle`、`particle_scale`、`offset`、`step`、
`spawn_interval_tick` 与 `density_scale`，但会叠加性能下限：512～768 格至少 2 格间距，
768～1152 格至少 4 格间距，1152～4096 格至少 8 格间距，单次补线最多 8 粒。
`rvp_rocket_flame` 在该链路放宽至 4096 格消亡边界并始终命中既有单层 LOD；凝结云保持期
使用服务端同步的剩余燃烧 Tick。远距候选最低离地 100 格，因此不生成贴地烟浪，也不应用
发射段 `launch_boost`。

## HBM 风格火箭尾焰（`rvp_rocket_flame`）

从 HBM（Hbm's Nuclear Tech Mod）`ParticleRocketFlame` / `ParticleSmokePlume` 移植的弹道导弹
尾迹观感：喷口附近约 24～35 tick 亮橙火焰团 → 灰白凝结云持续膨胀，凝结云保持期
α≈0.75，保持期结束后 smoothstep 缓慢淡出，初速沿弹轴反方向喷出（阻尼 0.91/tick）、
3 层抖动 quad 伪体积感、全亮渲染。发射段另有贴地烟浪（燃烧且距地 <20 格时，
弹体地面投影点每 tick 8 粒横向冲刷灰烟，0.3 → 3.0 × `particle_scale` 膨胀带浮升）。
**单个凝结云粒子的寿命**（`RVP_RocketFlameParticle` 构造内）：

```text
保持期   holdEnd      = max(holdTicks + 20, hold_min_ticks)
总寿命   lifetime     = holdEnd + fade_ticks + rand(0 ~ fade_random_ticks)
浓烟期   alphaHold    = min(holdEnd, round(lifetime × alpha_hold_ratio))
         alpha(age)  = 0.75 × (1 − smoothstep((age − alphaHold) / (lifetime − alphaHold)))
```

注意 `alphaHold` 与 `holdEnd` 是两件事：前者管「浓多久」，后者管「活多久」。

- `holdTicks` = 距发动机燃尽的剩余 Tick（`ticksUntilMotorStopsBurning()`）；无发动机燃烧数据的火箭恒为 0。
- 默认值（108 / 120 / 60）下，无燃烧数据的火箭单粒子寿命 **228～287 Tick ≈ 11.4～14.4 秒**，
  这是“火箭烟一拖十几秒”的来源。
- 三项都可在武器 JSON 覆盖，未配置则回落上面的代码默认；**会话调参（`/rvprocketflame`）优先级最高**。

参数对照详见 `docs/plan/RVP弹道导弹尾迹HBM风格移植方案_20260915.md`。

## 游戏内临时调参

客户端进入世界后，可使用本地客户端指令 `/rvprocketflame` 调整当前会话的 `rvp_rocket_flame`；
在本项目调试根命令下也提供等价入口 `/rvpdebug rocketFlame`：

```text
/rvprocketflame status
/rvprocketflame set particleScale 1.5
/rvprocketflame set densityScale 0.6
/rvprocketflame set groundWash false
/rvprocketflame set holdMinTicks 180
/rvprocketflame reset particleScale
/rvprocketflame reset
```

该覆盖不写回武器 JSON，重启客户端或执行 `reset` 后恢复。
取值优先级为：**会话覆盖（本指令） > 武器 JSON 配置 > 代码默认值**——例如 JSON 里写了
`missile_native_trail_hold_min_ticks: 30`，本指令 `set holdMinTicks 180` 会临时把它顶成 180。
尺寸、采样、贴地烟浪和尾迹生命周期参数
只影响之后生成的粒子；尺寸、采样、生成间隔、密度、偏移和生命周期覆盖同时作用于近距尾迹和
超视距远程尾迹，贴地烟浪与发射段加粗只作用于本地发射链路。可用参数包括：
`particleScale`、`step`、`spawnInterval`、`densityScale`、`offset`、`launchBoost`、`enabled`、
`groundWash`、`holdMinTicks`、`holdBufferTicks`、`fadeTicks`、`fadeRandomTicks`、`flameTicks`、
`flameRandomTicks`、`smokeGreyMin`、`smokeGreySpread`、`trailLayers`、`jitterMinTicks`、
`jitterMaxTicks`、`turbulenceDecay`、`jitterHorizontalScale`、`jitterVerticalScale`、
`jitterSpreadScale`、`jitterInterpolation`、`rotationRandomDegrees`、`jitterDurationRatio`。

抖动调试示例：

```text
/rvprocketflame set jitterHorizontalScale 0.35
/rvprocketflame set jitterVerticalScale 0.8
/rvprocketflame set jitterSpreadScale 0.5
/rvprocketflame set jitterMinTicks 4
/rvprocketflame set jitterMaxTicks 20
/rvprocketflame set turbulenceDecay 0.9
/rvprocketflame set jitterInterpolation 0
/rvprocketflame set rotationRandomDegrees 180
/rvprocketflame set jitterDurationRatio 0.5
```

其中 `jitterHorizontalScale` 控制 X/Z 方向，`jitterVerticalScale` 控制 Y 方向，
`jitterSpreadScale` 控制粒子变老后的扩散增长；设为 `0` 可关闭对应方向或年龄扩散。
`jitterInterpolation` 控制抖动重掷之间的位置插值，`0` 为线性插值，`1` 为当前默认的
smoothstep 平滑插值；设为 `0` 便于观察重掷间隔和折角。
`rotationRandomDegrees` 控制每个粒子的稳定随机旋转范围，实际角度为
`[-rotationRandomDegrees, +rotationRandomDegrees]`；默认 `0`，设为 `180` 可获得完整随机朝向。
`jitterDurationRatio` 控制抖动持续时间占粒子生命周期的比例；默认 `0` 表示不启用额外截止，
设置为 `0.5` 时抖动平滑衰减，并在粒子生命周期一半时归零，之后不再抖动。

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

## M142 海玛斯（`rvp:rocket`）低烟配置

2026-10-30 用户反馈火箭烟过浓、存留过久，按此配置削弱（`m142_rocket` / `m142_m30` / `m142_m30_wp` 三发同款）：

```json
"effects_data": {
  "trajectory_particle": "none",
  "missile_native_trail_particle_style": "rvp_rocket_flame",
  "missile_native_trail_particle_scale": 0.85,
  "missile_native_trail_step": 1.0,
  "missile_native_trail_density_scale": 0.45,
  "missile_native_trail_offset": 3.0,
  "missile_native_trail_hold_min_ticks": 5,
  "missile_native_trail_fade_ticks": 6,
  "missile_native_trail_fade_random_ticks": 3,
  "missile_native_trail_alpha_hold_ratio": 0.18
}
```

| 项 | 原默认 | 现在 | 说明 |
| --- | --- | --- | --- |
| `hold_min_ticks` | 108 | **5** | 实际保持期被 `+20` 缓冲兜住 = 20 Tick |
| `fade_ticks` | 120 | **6** | 淡出尾巴砍到 0.3 秒 |
| `fade_random_ticks` | 60 | **3** | 散开几乎同步 |
| `alpha_hold_ratio` | 1.0 | **0.18** | **浓烟只挂 0.25 秒**，之后全程渐隐 |
| `density_scale` | 1.0 | **0.45** | 粒子数约 45% |
| `step` | 0.7 | **1.0** | 采样间距加大，段数再降约 30% |
| `particle_scale` | 1.0 | **0.85** | 单粒子略缩 |

实际观感：

| | 最初 | 现在 |
| --- | --- | --- |
| 单粒子总寿命 | 228～287 Tick（11.4～14.4 秒） | **26～28 Tick（1.30～1.40 秒）** |
| 其中全浓（alpha 0.75） | 108 Tick（5.4 秒） | **5 Tick（0.25 秒）** |
| 同屏粒子数 | 100% | 约 31% |

> 只压 `fade_ticks` 是没用的：它只砍「尾巴」，浓烟段仍由保持期决定。真正让烟「看着短」的是
> `alpha_hold_ratio`。

### 同文件改动注意

`m142_m30` / `m142_m30_wp` 原本**缺少 `assets/rvp/display/weapon/<同名>.json`**，
表现为发射无音效、发射物无模型；2026-10-30 已按 `m142_rocket` 补齐（`missile_pl_12` 模型 +
`ywzj_vehicle:missile_launch`）。`bukm3_9m317m` 同类问题也已补（照 `9m317ma`）。
**新增武器 JSON 时必须同时创建 display，否则无发射音效。**
