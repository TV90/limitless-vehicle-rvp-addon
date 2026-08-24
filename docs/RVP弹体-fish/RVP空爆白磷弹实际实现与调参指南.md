# RVP 空爆白磷弹实际实现与调参指南

> 适用项目：`limitless-vehicle-rvp-addon`
>
> 适用版本：Minecraft 1.20.1 Forge
>
> 实现基线：2026-08-24 当前代码与 M142 示例 JSON
>
> 示例武器：`rvp:m142_m30_wp`、`rvp:m142_m30_wp_pellet`

## 1. 文档目的

本文记录空爆白磷弹在项目中的**实际实现**，用于后续维护、载具包配置和效果调参。内容以当前 Java 代码和下列示例为准，不代表早期调研稿中的设想：

- 母弹：[m142_m30_wp.json](../examples/rvp_json/weapons/m142_m30_wp.json)
- 白磷子体：[m142_m30_wp_pellet.json](../examples/rvp_json/weapons/m142_m30_wp_pellet.json)
- M142 挂载：[m142.json](../examples/rvp_json/vehicles/m142.json)

当前效果由三条相互解耦的链路组成：

1. **服务端空爆与毁伤**：近地引信、子弹药释放、母弹爆炸、子体碰撞、点火和实体着火。
2. **服务端子体运动**：向下分层圆锥散布、重力、阻力、反向风漂和实体种子驱动的平滑低频游移。
3. **客户端视觉**：隐藏子体模型，在权威子体位置生成全亮主体，并把上一主体位置逐 Tick 沉积为渐隐尾迹。

粒子不是毁伤判定，客户端粒子多少不会增加点火次数或伤害；服务器也不逐粒广播白磷粒子。

## 2. 当前基线效果

M30 母弹以 `6 格/Tick` 飞行。武装 40 Tick 后，当下方地面进入 50 格近地引信范围时触发空爆：

```text
近地引信触发
  ├─ on_fuse 释放事件（只执行 1 次）
  │    └─ 24 枚白磷子体
  │         ├─ 世界向下、72° 半角、模长 1.5 格/Tick 的分层圆锥
  │         ├─ 额外继承母弹 10% 的 X/Z，不继承母弹 Y
  │         ├─ 初始部署 X/Z 按 20 Tick 半衰期连续衰减
  │         ├─ 捕获母弹此刻朝向的反向作为风向
  │         ├─ 服务端平滑游移、重力、阻力、碰撞、直击和点火
  │         └─ 客户端主体火点和上一主体位置尾迹
  └─ parent_action = continue
       └─ 母弹继续执行 80 伤害、4 格半径的普通爆炸
```

“母弹此刻朝向”指释放子体的那个 Tick 中母弹的旋转朝向。它不是发射架方向、不是母弹最初发射方向，也不是简单取母弹当前速度的反向。

## 3. 代码与资源入口

| 职责 | 当前入口 |
| --- | --- |
| 弹体总流程、同步标记、风漂调用、客户端视觉桥接 | `org.ywzj.rvp.entity.projectile.RVP_BaseBullet` |
| 子弹药释放 | `org.ywzj.rvp.weapon.submunition.RVP_SubmunitionRunner`、`RVP_SubmunitionSpawner` |
| 分层圆锥算法 | `org.ywzj.rvp.weapon.submunition.RVP_SubmunitionSpreadApplicator` |
| 部署水平半衰期 | `org.ywzj.rvp.weapon.physics.RVP_DeploymentMotionUtil` |
| 风向配置 | `org.ywzj.rvp.weapon.data.RVP_WindData` |
| 捕获空爆时反向朝向 | `org.ywzj.rvp.weapon.physics.RVP_WindDirectionUtil` |
| 每 Tick 风漂速度收敛 | `org.ywzj.rvp.weapon.physics.RVP_WindDriftUtil` |
| 粒子弹体配置 | `org.ywzj.rvp.weapon.data.RVP_ParticleProjectileData` |
| 客户端粒子发射 | `org.ywzj.rvp.client.particle.RVP_ParticleProjectileEmitter` |
| 粒子生命周期、颜色、尺寸与透明度 | `org.ywzj.rvp.client.particle.RVP_WhitePhosphorusParticle` |
| 服务端安全的客户端调用桥 | `org.ywzj.rvp.client.bridge.RVP_ClientActionsAccess`、`RVP_IClientActions`、`RVP_ClientActions` |
| RVP 类型化弹体 Renderer 注册 | `org.ywzj.rvp.client.RVP_ClientEntityRenderers` |
| 非 JSON 粒子 Provider 注册 | `org.ywzj.rvp.client.RVP_ClientBootstrap` |
| 粒子纹理 | `assets/ywzj_rvp/textures/nuclear/particle_base.png` |

该实现没有按 `m142_m30_wp_pellet` 之类的武器 ID 在实体或渲染器中分支，也没有新增 Mixin。是否启用风漂和纯粒子弹体完全由当前 schema 的 JSON 字段决定。

## 4. 服务端实现

### 4.1 空爆触发与母弹行为

母弹的关键配置如下：

```json
"fuse_data": {
  "ground_proximity_fuse_distance": 50.0,
  "ground_proximity_fuse_arm_tick": 40,
  "detonate_on_life_end": false
},
"submunition_data": {
  "releases": [
    {
      "triggers": ["on_fuse"],
      "release_events": 1,
      "parent_action": "continue"
    }
  ]
}
```

- `ground_proximity_fuse_arm_tick` 防止火箭刚离开发射车便被附近地面触发。
- `ground_proximity_fuse_distance` 决定检测到地面时的理论离地距离，但实际触发点还受弹速、弹道角、地形和 Tick 离散检测影响。
- `release_events: 1` 表示本次引信事件只释放一轮。不要把它误当成子体数量；子体数量由 payload 的 `count` 控制。
- `parent_action: "continue"` 让母弹在释放子体后继续原有引爆流程，所以仍会执行 `detonate_data.explosion_data`。
- 如果想要“只撒布、不发生母弹爆炸”，应调整母弹 `explosion_data` 或使用项目 schema 支持的父弹动作，而不是在粒子代码中屏蔽爆炸。

### 4.2 分层圆锥散布

当前 payload 配置：

```json
{
  "weapon_id": "rvp:m142_m30_wp_pellet",
  "count": 24,
  "inherit_parent_velocity": false,
  "inherit_parent_horizontal_velocity": true,
  "inherit_vehicle_velocity": false,
  "velocity_scale": 0.10,
  "launch_speed": 1.5,
  "payloads_velocity": [0.0, 0, 0.0],
  "allow_submunition": false,
  "spread": {
    "mode": "stratified_cone",
    "cone_half_angle": 72.0,
    "cone_axis": "world_down",
    "radial_distribution": "uniform_area",
    "azimuth_jitter": 0.08,
    "radial_jitter": 0.08
  }
}
```

`stratified_cone` 使用黄金角推进方位角，并按子体序号把样本分层。极角通过圆锥内的均匀立体角采样得到：

```text
u ≈ (index + 0.5) / count + 分层内径向扰动
cos(theta) = lerp(1, cos(cone_half_angle), u)
```

与每枚子体独立随机方向相比，这种分布更不容易在中心堆成一团，也不容易在某一侧留下大空洞。当前实现支持的有效组合是：

- `mode: "stratified_cone"`
- `cone_axis: "world_down"`
- `radial_distribution: "uniform_area"`

M142 不配置 `cone_radial_speed`，因此圆锥速度只用同一个 `launch_speed` 缩放，输出模长恒为 1.5：

```text
coneVelocity = (worldDown × cos(theta) + radialDirection × sin(theta)) × launch_speed
parentHorizontal = (parentVelocity.x, 0, parentVelocity.z) × velocity_scale
deploymentHorizontal₀ = coneVelocityXZ + parentHorizontal
baseVelocity₀ = (0, coneVelocity.y, 0) + payloads_velocity
```

`72° < 90°` 保证所有样本从生成开始都斜向下；圆锥边缘的向下分量仍约为 `1.5 × cos(72°) ≈ 0.46 格/Tick`，不会再出现原先 `launch_speed=0.2` 与 `cone_radial_speed=1.5` 造成的近水平高速撒布。母弹约 6 格/Tick 时，10% 水平继承约为 0.6 格/Tick，只让弹幕保留合理前冲，不继承母弹俯冲或爬升的 Y 速度。

生成器把圆锥 X/Z 与母弹水平继承保存为独立部署分量。配置 `deployment_horizontal_half_life_ticks: 20` 后：

```text
deploymentHorizontal(t) = deploymentHorizontal₀ × 2^(-t / 20)
```

因此 20、40、60 Tick 后分别剩余 50%、25%、12.5%。该阻尼不改变 Y，也不作用于显式 `payloads_velocity` 或之后产生的风偏。

### 4.3 风向：空爆时母弹当前朝向的反向

子体生成时，`RVP_WindDirectionUtil` 从母弹当时的旋转取得前向单位向量，然后取反：

```text
capturedWind = normalize(-parentCurrentFacing)
capturedWind.y *= vertical_factor
capturedWind = normalize(capturedWind)
```

默认 M30 子体的 `vertical_factor` 为 `0`，因此正常情况下只保留反向朝向的水平投影。这样可让烟火团向母弹尾后方漂移，同时避免风场直接改变坠落速度。

边界回退按以下顺序进行：

1. 若母弹几乎垂直朝上或朝下，去掉 Y 后向量可能接近零；此时尝试母弹**当前速度的反向水平分量**。
2. 若速度水平分量也接近零，则使用世界 `+Z` 作为稳定兜底。

速度仅用于极端退化回退，不是正常风向来源。因此，“发射后母弹转向，再空爆”的场景会以空爆时的新朝向为准。

捕获的风向属于子体运行时状态。RVP 弹体本身为 `noSave`，不会跨存档持久化该方向。

### 4.4 风漂速度收敛

启用部署半衰期的子体会维护独立风偏速度，并从零向目标风速收敛：

```text
targetWindVelocity = capturedWind × speed
```

再让风偏贡献按 `response` 收敛，而不是让子体总速度直接转向目标：

```text
windNext = windCurrent + (windTarget - windCurrent) × response
totalVelocity = baseVelocity + deploymentHorizontal + windNext
```

当 `vertical_factor = 0` 时，独立风偏贡献的 Y 恒为零，完全不参与基础下落速度；只有该值大于 0 且目标风向包含 Y 分量时，风偏贡献才含 Y。

无扰动时，经过 `n` Tick 后单轴误差约为：

```text
error(n) = error(0) × (1 - response)^n
```

当前 `response = 0.045`：

- 误差减半约需 15.1 Tick，约 0.76 秒；
- 误差缩小到 10% 约需 50.0 Tick，约 2.50 秒。

`turbulence` 与 `turbulence_frequency` 共同产生平滑游移。实体 UUID 派生出稳定初始相位和 `0.85..1.15` 的频率倍率：

```text
phase = seedPhase + 2π × turbulence_frequency × seedRateScale × flightTick
windTarget = capturedWind × speed
           + turbulence × (cos(phase), 0, sin(phase))
```

当前幅度为 `0.012 格/Tick`，基础频率为 `0`，因此每枚子体保持由自身种子派生的稳定目标偏移方向；把频率调到正数后，目标偏移才会按配置周期平滑旋转。扰动属于有界风速目标，不再作为每 Tick 无界追加的速度冲量。它仍由服务端计算，实际碰撞、点火和落点都会随轨迹改变，客户端只显示同步结果。

对白磷子体使用的普通弹道分支，整体顺序可概括为：

```text
吸收碰撞等外部改速 → 部署 X/Z 半衰期阻尼
→ 基础速度重力/阻力 → 独立风偏收敛 → 三分量合成
→ 最大速度限制 → 移动与碰撞
```

基础 `drag` 不再阻尼部署 X/Z 或独立风偏，只处理基础弹道中的水平外力；初始水平收束速度由 `deployment_horizontal_half_life_ticks` 决定。三分量合成速度若超过 `max_speed`，会按同一倍率同步缩放，避免下一 Tick 内部状态反弹。

### 4.5 毁伤边界

当前母弹和子体承担不同职责：

| 来源 | 当前效果 |
| --- | --- |
| 母弹 | 80 爆炸伤害、4 格半径、不破坏方块 |
| 子体直击 | `direct_damage: 3.0` |
| 子体落点 | 3 格半径内，以 0.65 概率按方块/实体命中条件点火 |
| 子体实体着火 | 2 格半径、10 秒、目标为 `non_allied` |
| 子体爆炸 | 无 `explosion_data`，不会产生 24 次小爆炸 |

`effects_before_explosion: true` 让子体的点火和实体着火效果在结束流程中优先执行。视觉粒子不参与上述任何判定。

当前效果不是独立的“白磷持续灼烧状态系统”：实体持续效果主要来自 Minecraft 的着火机制；车辆等不完全遵循 `LivingEntity` 着火逻辑的目标，主要仍受母弹爆炸和子体直击约束。

## 5. 客户端视觉实现

### 5.1 纯粒子弹体与模型隐藏

子体设置：

```json
"particle_projectile_data": {
  "enabled": true,
  "particle_type": "rvp:white_phosphorus"
}
```

启用后，服务端把“这是纯粒子弹体”的布尔状态同步到客户端。RVP 类型化 Renderer 根据该同步状态跳过模型绘制，避免首次出现时短暂闪出默认炸弹模型。

具体粒子配置仍由客户端按武器资源索引解析。资源包和数据包必须保持同一版本，否则可能出现“模型已隐藏，但客户端找不到详细粒子参数”的情况。

粒子生成通过客户端桥调用：服务端桥为 NOOP，客户端实现才访问 `Minecraft` 和粒子引擎。粒子不会让专用服务端加载客户端类。

白磷粒子运行时数据 ID 保持为 `rvp:white_phosphorus`，与载具包当前 schema 和既有配置一致。该 ID 的 `rvp` 命名空间不决定贴图目录；客户端使用 Forge 非 JSON 粒子 Provider，并由独立半透明 `ParticleRenderType` 直接绑定 `assets/ywzj_rvp/textures/nuclear/particle_base.png`，完整 UV 为 `0..1`。因此不需要 `assets/<namespace>/particles/white_phosphorus.json` 或 `assets/minecraft/atlases/particles.json`，白磷相关资产仍统一保留在 `assets/ywzj_rvp`，也没有复制或维护第二份白磷专用 PNG。更换该权威文件会同时影响所有直接复用它的视觉效果，调图后应一并回归核爆、普通爆炸、温压和白磷弹视觉。

不要因贴图移动到 `assets/ywzj_rvp` 而把 `particle_type` 改为 `ywzj_rvp:white_phosphorus`。发射器会对该字段与已注册 ID 做精确比较；ID 不匹配时模型仍会按纯粒子弹体隐藏，但客户端不会创建主体或尾迹粒子。

### 5.2 主体粒子

每个存活子体通常每 Tick 生成一个主体粒子：

- 固定使用配置的 `body_color`；
- 尺寸以 `body_scale` 为基准；
- `body_flicker` 只随机改变每次出生时的**尺寸**，不是亮度闪烁；
- 每个主体的实际出生尺寸会随上一位置状态保存，作为对应尾迹的出生尺寸；
- 单粒子存活 `body_lifetime_ticks`；
- `full_bright: true` 时使用全亮光照。

距离玩家超过 512 格时，主体改为每 2 Tick 生成一次。实际可见范围通常还会受到实体追踪范围和客户端渲染距离限制。

### 5.3 路径尾迹

尾迹不是根据速度向量向后拉线，也不再沿“上一位置 → 当前位置”的单 Tick 运动段插值补点。发射器每 Tick 先在当前位置生成主体，再把**上一 Tick 的主体位置**沉积为恰好一个尾迹粒子，因此显示的是主体实际走过的离散历史位置。

尾迹生成同时要求：

- 上一记录与当前实体 Tick 连续；
- 主体位置确实发生移动；
- `trail_enabled: true`；
- 玩家距离不超过 512 格。

首 Tick、静止 Tick、刚进入追踪范围或中间缺少连续实体 Tick 时不生成尾迹，避免同点堆积和跨越远距离的错误连接。当前 schema 已删除 `trail_spacing`，也不再存在 128/256 格间距倍率或单 Tick 12 点补点上限；每枚子体每 Tick 最多生成一个尾迹。

### 5.4 粒子生命周期曲线

主体在生命周期内保持给定颜色、透明度和实际随机尺寸。尾迹则按归一化年龄 `t` 变化：

- 尺寸：从对应主体的实际尺寸平滑过渡到 `trail_end_scale`；
- 透明度：从 `trail_start_alpha` 平滑过渡到 `trail_end_alpha`；
- 颜色：从 `trail_start_color` 线性过渡到 `trail_end_color`；
- 粒子自身无重力、无碰撞、无速度积分，出生后停留在采样点。

尺寸和透明度使用平滑曲线，不会在线性生命周期末端突然硬切。当前 M142 实机配置的尾迹起止颜色均为白色，主要通过尺寸和透明度表现消散；通用示例仍可配置暖色渐变。

## 6. 当前可复制基线

建议直接以示例 JSON 为权威完整模板。下面列出最常调的子体参数：

```json
"projectile_data": {
  "velocity": 0.9,
  "gravity": -0.0045,
  "drag": 0.058,
  "deployment_horizontal_half_life_ticks": 20,
  "constant_speed": false,
  "rotate_to_motion": true,
  "max_speed": 3.0,
  "wind_data": {
    "enabled": true,
    "direction_mode": "parent_facing_reverse",
    "speed": 0.11,
    "response": 0.045,
    "vertical_factor": 0.0,
    "turbulence": 0.012,
    "turbulence_frequency": 0.0
  }
},
"effects_data": {
  "trajectory_particle": "none",
  "impact_particle": "none",
  "explosion_particle": "none",
  "impact_trail_particles": false,
  "particle_projectile_data": {
    "enabled": true,
    "particle_type": "rvp:white_phosphorus",
    "full_bright": true,
    "body_scale": 0.42,
    "body_lifetime_ticks": 1,
    "body_color": "#FFC247",
    "body_flicker": 0.9,
    "trail_enabled": true,
    "trail_lifetime_ticks": 200,
    "trail_end_scale": 0.03,
    "trail_start_alpha": 0.90,
    "trail_end_alpha": 0.0,
    "trail_start_color": "#FFFFFF",
    "trail_end_color": "#998E8A"
  }
}
```

颜色接受 `#RRGGBB` 或 `RRGGBB`。格式非法时回退到字段默认值，不支持 8 位 ARGB 字符串；透明度应使用独立 alpha 字段。

## 7. 字段调参表

### 7.1 空爆与撒布

| 字段 | 当前值 | 增大后的主要效果 | 建议起步范围 | 注意事项 |
| --- | ---: | --- | ---: | --- |
| `ground_proximity_fuse_distance` | 50 | 更早、更高空爆 | 20～60 | 不等于严格固定离地高度；受弹速和地形影响 |
| `ground_proximity_fuse_arm_tick` | 40 | 更晚允许空爆 | 20～60 | 过小可能贴近发射车触发；过大可能错过近目标 |
| `count` | 24 | 覆盖更密、服务端实体和客户端粒子更多 | 16～32 | 性能成本近似线性增加 |
| `launch_speed` | 1.5 | 整个圆锥初速模长更大，径向与向下分量同步提高 | 0.8～1.8 | M142 不配置独立径向速度，避免 X/Z 与 Y 尺度失配 |
| `cone_half_angle` | 72° | 覆盖更宽、边缘下坠分量更小 | 55°～80° | 是半角；72° 边缘向下分量约为初速的 30.9% |
| `inherit_parent_horizontal_velocity` | true | — | 按需求 | 只继承母弹 X/Z；开启后覆盖完整父弹继承方式 |
| `velocity_scale` | 0.10 | 母弹前冲对整片弹幕的影响更强 | 0.05～0.20 | 水平继承模式下只缩放母弹 X/Z，不缩放 `launch_speed` |
| `azimuth_jitter` | 0.08 | 方位更不规则 | 0～0.15 | 过大会削弱分层均匀性 |
| `radial_jitter` | 0.08 | 径向层次更不规则 | 0～0.15 | 过大会出现局部疏密不均 |

### 7.2 子体弹道与风漂

| 字段 | 当前值 | 增大后的主要效果 | 建议起步范围 | 注意事项 |
| --- | ---: | --- | ---: | --- |
| `gravity` | -0.0045 | 数值更接近 0 时下落加速更慢 | -0.001～-0.01 | 只作用于基础弹道 Y，不改变部署水平阻尼 |
| `drag` | 0.058 | 基础弹道中的水平外力衰减更快 | 0～0.10 | 不再阻尼部署 X/Z 或独立风偏贡献 |
| `deployment_horizontal_half_life_ticks` | 20 | 数值越大，初始散布与母弹水平继承保留越久 | 10～40 | 20 Tick 减半；0 禁用分量化部署运动并保持旧链路 |
| `max_speed` | 3.0 | 放宽三分量合成后的总速度上限 | 2.2～3.5 | 应高于圆锥 1.5 与约 0.6 水平继承的理论合成峰值 |
| `wind_data.speed` | 0.11 | 最终尾后漂移速度更大 | 0.06～0.18 | 必须为正，否则风漂整体视为禁用 |
| `wind_data.response` | 0.045 | 更快转向目标风速 | 0.02～0.08 | 0 禁用风漂；过高会像被瞬间拽走 |
| `wind_data.vertical_factor` | 0.0 | 更多保留母弹反向朝向中的 Y | 通常 0～0.2 | 0 会明确保留下坠 Y 速度；大值会改变升降趋势 |
| `wind_data.turbulence` | 0.012 | 平滑游移幅度更大、轨迹更弯 | 0～0.015 | 过大会盖过分层散布和主风向，并真实改变落点 |
| `wind_data.turbulence_frequency` | 0.0 | 游移方向转动更快 | 0～0.05 | 单位周期/Tick；0 表示每枚子体保持各自稳定扰动方向，过高会产生高频机械抖动 |

风漂启用需要同时满足：

```text
enabled = true
direction_mode = parent_facing_reverse
speed > 0
response > 0
```

未知 `direction_mode` 不会回退为其他模式，而是按未启用处理。

### 7.3 主体粒子

| 字段 | 当前值 | 增大后的主要效果 | 建议起步范围 | 注意事项 |
| --- | ---: | --- | ---: | --- |
| `body_scale` | 0.42 | 火点更大 | 0.25～0.65 | 非负；0 会使主体不可见 |
| `body_lifetime_ticks` | 2 | 同时存在的主体更多、更连续 | 1～5 | 至少为 1，成本约随寿命线性增加 |
| `body_color` | `#FFC247` | — | 暖白至橙黄 | 仅 RGB；不是发光强度 |
| `body_flicker` | 0.9 | 每个新主体及其对应尾迹的尺寸差异更大 | 0～1 | 限制在 0～1；不是亮度闪烁，尾迹会继承随机后的实际尺寸 |
| `full_bright` | true | true 时不受环境光压暗 | 通常 true | false 更融入环境，但夜间不再强亮 |

### 7.4 尾迹粒子

| 字段 | 当前值 | 增大后的主要效果 | 建议起步范围 | 注意事项 |
| --- | ---: | --- | ---: | --- |
| `trail_lifetime_ticks` | 200 | 尾迹停留更久 | 80～240 | 最直接的粒子存量放大器；密度固定为每枚移动子体每 Tick 最多 1 个 |
| `trail_end_scale` | 0.03 | 尾迹消失前仍较粗 | 0～0.08 | 想完全收尖可设 0 |
| `trail_start_alpha` | 0.90 | 新尾迹更实 | 0.6～1.0 | 限制在 0～1 |
| `trail_end_alpha` | 0.0 | 尾迹末端更不透明 | 通常 0～0.1 | 大于 0 时会在寿命结束瞬间消失 |
| `trail_start_color` | `#FFFFFF` | — | 白色、淡黄 | 与主体色共同决定热亮感 |
| `trail_end_color` | `#998E8A` | — | 白色、浅灰 | 想表现冷却可改为更暗的暖灰或棕色 |

### 7.5 毁伤与点火

| 字段 | 当前值 | 增大后的主要效果 | 调参风险 |
| --- | ---: | --- | --- |
| 母弹 `explosion_data.damage` | 80 | 空爆中心伤害提高 | 与 24 枚子体叠加时要检查总伤害 |
| 母弹 `explosion_data.radius` | 4 | 中心爆炸范围扩大 | 不是白磷覆盖半径 |
| 子体 `direct_damage` | 3 | 单枚直击更痛 | 多枚命中同目标可能叠加 |
| `fire_data.radius` | 3 | 单枚落点点火搜索更宽 | 服务器方块搜索成本和纵火范围增加 |
| `fire_data.chance` | 0.65 | 可点燃位置成功率提高 | 仍受方块、空气、水和事件条件影响 |
| `ignite_entity_data.radius` | 2 | 实体着火搜索更宽 | 24 枚区域可能大量重叠 |
| `ignite_entity_data.seconds` | 10 | 着火更久 | 影响生物持续伤害，需与玩法平衡联调 |

## 8. 推荐调参顺序

每轮只改一组相关字段，并固定发射距离、俯仰角、目标地形、天气和观察位置。推荐顺序如下：

### 第一步：先确定空爆高度

只调 `ground_proximity_fuse_distance` 和 `ground_proximity_fuse_arm_tick`。在平地上连续发射至少 5 次，确认不会近车误爆，也不会飞过目标后才武装。

### 第二步：确定覆盖几何

暂时保持风参数不变，调 `count`、`launch_speed`、`cone_half_angle` 和水平半衰期：

- 覆盖太窄：先增大 `cone_half_angle`；
- 整体展开和下落都太慢：增大 `launch_speed`；
- 展开距离不足但初速合适：增大 `deployment_horizontal_half_life_ticks`；
- 形状满意但密度不足：最后增加 `count`；
- 局部过于规则：小幅增加两个 jitter，不要先用大扰动破坏分层。

### 第三步：确定滞空和落地时间

先用 `launch_speed + cone_half_angle` 决定初始斜向下速度，再用 `gravity` 决定持续下坠加速度。部署 X/Z 的收束只调 `deployment_horizontal_half_life_ticks`，不要再用大 `drag` 急刹总速度。

### 第四步：确定尾后漂移

使用能明显改变母弹朝向的测试弹道，确认风向随**空爆时当前朝向**变化：

- 位移方向正确但不够远：增大 `speed`；
- 开始漂得太慢：增大 `response`；
- 弯曲幅度不足：小幅增加 `turbulence`；
- 摆动太慢或太快：调整 `turbulence_frequency`，优先保持在 `0.01～0.05`；
- 下坠被明显干扰：把 `vertical_factor` 降回 0。

不要用 `turbulence` 扩大覆盖；它只改变独立风偏目标。覆盖由圆锥初速、半角、母弹水平继承和部署半衰期共同决定。

### 第五步：调整火点辨识度

先在白天和夜间各观察一次。用 `body_scale` 决定单枚子体是否容易识别，用 `body_lifetime_ticks` 决定主体是否连贯，再用 `body_flicker` 消除完全一致的机械感。

### 第六步：调整尾迹长度与外观

尾迹密度固定为每枚移动子体每 Tick 最多一个。先调 `trail_lifetime_ticks` 控制长度和总量，再调尺寸、透明度和颜色；若仍超出预算，应减少子体数或关闭尾迹，而不是添加已删除的 `trail_spacing`。

### 第七步：最后平衡毁伤

先确认覆盖区域，再调母弹中心爆炸、子体直击、方块点火和实体着火。视觉覆盖很大并不意味着每个位置都必须具备同等毁伤。

## 9. 三组建议预设

下列值只给出相对当前基线的关键改动，其余字段沿用示例。

### 9.1 稳健性能型

适合多人服务器或需要同时发射多枚火箭的场景：

```json
"count": 18,
"launch_speed": 1.0,
"cone_half_angle": 65.0
```

```json
"body_lifetime_ticks": 2,
"trail_lifetime_ticks": 80
```

### 9.2 当前均衡型

直接使用 M142 示例：24 枚、72°、1.5 圆锥初速、10% 母弹 X/Z 继承、20 Tick 水平半衰期、0.11 风速、0.012 游移幅度、200 Tick 尾迹寿命。它应作为实机对照基线。

### 9.3 宽幅展示型

适合单发演示，不建议未经压测直接用于高射速齐射：

```json
"count": 28,
"launch_speed": 1.6,
"cone_half_angle": 78.0,
"deployment_horizontal_half_life_ticks": 30
```

```json
"speed": 0.14,
"response": 0.045,
"turbulence": 0.014,
"turbulence_frequency": 0.018
```

```json
"body_scale": 0.48,
"trail_lifetime_ticks": 240
```

宽幅型增加了子体数量和尾迹尺寸，多发齐射前必须单独压测；尾迹出生率仍由“每枚移动子体每 Tick 一个”固定规则控制。

## 10. 性能预算

### 10.1 主体粒子存量

近距离下，每枚子体每 Tick 生成 1 个主体。稳定阶段的粗略存量为：

```text
主体存量 ≈ 子体数 × body_lifetime_ticks
当前基线 ≈ 24 × 1 = 24
```

### 10.2 尾迹粒子存量

单枚移动子体每 Tick 最多沉积 1 个尾迹点。当前 24 枚子体的理论出生峰值为：

```text
24 × 1 = 24 个尾迹粒子/Tick
```

若持续达到上限，实机 `200 Tick` 寿命对应约 `4,800` 个存活尾迹粒子。实际数量还会受到子体存活时间、静止 Tick、512 格距离限制和客户端粒子总量限制影响。

调优优先级：

1. 减少 `trail_lifetime_ticks`；
2. 减少 `count`；
3. 必要时关闭 `trail_enabled`；
4. 最后才缩短主体寿命，因为主体对识别弹体位置很重要。

`full_bright`、颜色和尺寸通常不是数量级性能因素；数量、寿命和同时存在的弹体数才是主要因素。

## 11. 常见症状与排查

| 症状 | 优先检查 | 说明或处理 |
| --- | --- | --- |
| 空爆后没有子体 | `on_fuse`、`weapon_id`、`release_events`、武装 Tick | 确认子体武器已被载具包索引加载 |
| 子体数量像平方增长 | `release_events` 是否误设为子体数 | 一轮释放使用 `1`，数量只放在 payload `count` |
| 空爆太低或撞地才爆 | 引信距离、武装 Tick、母弹速度 | 高速弹每 Tick 跨越距离大，触发高度会离散 |
| 子体整体继续向前冲 | 两个 `inherit_*_velocity` 或 `payloads_velocity` 非零 | 当前基线三者均不提供前冲速度 |
| 风向仍像发射方向 | 检查实际运行包版本和 `direction_mode` | 当前代码应捕获空爆 Tick 的母弹旋转；用转向弹道复测 |
| 垂直发射时风向异常固定 | 水平投影退化 | 会回退到当前速度反向水平分量，再退化则世界 `+Z` |
| 下坠速度被风明显拉慢 | `vertical_factor` 非零 | 水平尾后漂移应设为 0 |
| 子体都挤在中心 | `spread.mode`、圆锥初速、半角、部署半衰期 | 必须为 `stratified_cone`；先确认统一初速，再延长水平半衰期 |
| 子体先横飞再急刹 | 独立径向速度或旧总速度风漂仍在使用 | M142 删除 `cone_radial_speed`，配置 20 Tick 部署半衰期，并确认新水平继承字段生效 |
| 散布出现大片空洞 | jitter 过大或数量太少 | 降低两个 jitter 或适度提高 `count` |
| 子体模型可见 | `particle_projectile_data.enabled`、客户端/服务端版本、类型化 Renderer | 不要在 Renderer 中按武器 ID 特判 |
| 模型消失但完全无粒子 | `particle_type`、非 JSON Provider 注册、权威纹理、客户端数据包版本 | 当前发射器只识别已注册的 `rvp:white_phosphorus`；不要按贴图目录改成 `ywzj_rvp:white_phosphorus`，并确认客户端已注册 Provider 且权威 PNG 已打包 |
| 尾迹出现断点 | 主体静止、超过 512 格、客户端 Tick 不连续 | 这些场景不会沉积尾迹；刚进入追踪范围的首 Tick 不留旧位置是预期行为 |
| 尾迹太短 | 寿命小、颜色/alpha 过早变暗 | 优先增寿命，再调整末端颜色和 alpha |
| 尾迹太粗像实体管线 | 主体尺寸/闪烁大、尾迹寿命长 | 降 `body_scale` 或 `body_flicker`，也可缩短 `trail_lifetime_ticks`；尾迹起始尺寸不能独立调整 |
| FPS 明显下降 | 子体数、尾迹寿命、齐射数量 | 按性能预算顺序削减；不要重新添加已删除的 `trail_spacing` |
| 子体落地发生大量爆炸 | 子体误加 `explosion_data` | 当前白磷子体只应点火、着火和直击 |
| 没有点燃方块 | chance、on_block、落点环境 | 水、雨、不可放置火焰的位置或事件拦截都会影响结果 |
| 敌方没有持续灼烧 | 目标类型、阵营、半径 | `targets: non_allied` 且主要面向可着火实体，不是通用车辆 DOT |

## 12. 验证矩阵

每次修改 Java 实现或这些 JSON 字段后，至少覆盖以下验证：

### 12.1 自动测试与编译

在项目根目录执行：

```powershell
$env:JAVA_HOME='C:\Users\FishKing0721\.jdks\ms-17.0.16'
./gradlew build
```

重点测试包括：

- `RVP_WindDirectionUtilTest`：当前朝向反向、垂直投影和退化回退；
- `RVP_WindDriftUtilTest`：旧总速度风漂兼容、独立风偏收敛、Y 分量隔离及有界扰动；
- `RVP_DeploymentMotionUtilTest`：20/40/60 Tick 半衰期精度、方向稳定和 Y 隔离；
- `RVP_SubmunitionStratifiedConeTest`：72°、1.5 模长圆锥全部向下，边缘向下分量不低于约 0.46；
- `RVP_ParticleProjectileDataTest`：字段默认、范围限制、颜色解析、已删除 `trail_spacing`/`trail_start_scale` 不再属于数据模型，以及 `rvp:white_phosphorus` 运行时 ID 稳定性；
- `RVP_WhitePhosphorusResourceTest`：权威纹理位于 `assets/ywzj_rvp` 且资源有效，同时不存在旧粒子 JSON 和图集追加文件。

### 12.2 游戏内功能测试

| 编号 | 场景 | 通过标准 |
| --- | --- | --- |
| A1 | 平地水平射击 | 约 50 格近地条件触发，40 Tick 前不在发射车旁误爆 |
| A2 | 母弹飞行中改变朝向后空爆 | 子体风漂使用空爆时朝向的反向，不沿最初发射方向 |
| A3 | 朝东、南、西、北各射击 | 漂移方向随当前朝向连续变化，无固定世界轴偏差 |
| A4 | 大俯角和近垂直弹道 | 无 NaN、停滞或失控速度；退化回退稳定 |
| A5 | 观察撒布俯视形状 | 24 枚立即斜向下铺开；20/40 Tick 后初始 X/Z 约剩 50%/25%，无近水平悬停或急刹 |
| A6 | 单人客户端逐帧观察 | 子体无模型闪现；尾迹只在上一主体位置出现，无线段补点和静止堆积 |
| A7 | 专用服务端 + 客户端 | 服务端不加载客户端类，双方弹道与毁伤一致 |
| A8 | 512 格边界及重新进入追踪范围 | 超过 512 格不生尾迹，重新进入时不连接旧位置或产生粒子尖峰 |
| A9 | 子体落在可燃与不可燃环境 | 点火概率和环境限制符合预期，无子体小爆炸 |
| A10 | 多发齐射 | TPS、客户端 FPS 和粒子存量可接受 |

### 12.3 回归检查

- 未配置 `wind_data` 的旧武器弹道不应改变。
- 未配置 `deployment_horizontal_half_life_ticks` 的旧子弹药继续使用原总速度积分。
- 未配置 `inherit_parent_horizontal_velocity` 时保持原完整父弹继承规则。
- 未配置 `cone_radial_speed` 的分层圆锥仍应使用原有等模长速度，不改变其他子母弹。
- `wind_data.enabled: false` 时不应产生风漂。
- 未配置 `particle_projectile_data` 的弹体仍使用原有模型和效果。
- 非白磷粒子 ID 不应错误调用白磷专用发射器。
- 服务端判定不应依赖客户端是否开启粒子或粒子设置高低。

## 13. 当前限制

1. `direction_mode` 当前实际支持 `parent_facing_reverse`；不存在其他风向模式的自动兼容。
2. 分层圆锥当前实际面向 `world_down + uniform_area` 组合。
3. 专用粒子发射器当前识别 `rvp:white_phosphorus`，不能只改字符串就获得任意新粒子弹体。
4. 白磷尾迹是客户端视觉，不碰撞、不照明方块、不参与伤害；主体与尾迹出生后均停留在各自采样坐标。
5. 子体不提供独立白磷附着、持续区域灼烧或车辆通用 DOT 系统。
6. 风向在释放瞬间固化，之后不会继续跟随母弹旋转，也不会随世界天气动态改变。
7. RVP 弹体不保存到磁盘；服务器重启后不会恢复半空中的母弹、子体或其捕获风向。

## 14. 发布前检查清单

- [ ] 母弹 `release_events` 为 1，payload `count` 才是实际子体数。
- [ ] 子体 `weapon_id` 与载具包中的文件名、命名空间一致。
- [ ] 斜向下撒布使用统一 `launch_speed`，M142 不配置独立 `cone_radial_speed`。
- [ ] `inherit_parent_horizontal_velocity=true` 且 `velocity_scale` 已限制母弹水平继承。
- [ ] `deployment_horizontal_half_life_ticks` 已按覆盖距离验证，0 表示完全关闭新分量链路。
- [ ] 风向模式为 `parent_facing_reverse`，并验证过空爆时转向场景。
- [ ] `turbulence_frequency` 已按周期/Tick 配置，并确认不同子体不会同步摆动。
- [ ] 水平漂移需求下 `vertical_factor` 为 0。
- [ ] 子体未意外配置 `explosion_data`。
- [ ] `particle_type` 为 `rvp:white_phosphorus`，并发布 `assets/ywzj_rvp/textures/nuclear/particle_base.png`；数据 ID 不随资产目录改变，包内不存在旧粒子 JSON 或图集追加文件。
- [ ] 客户端与服务端使用同版本 addon 和载具包。
- [ ] 多发齐射完成 FPS/TPS 压测。
- [ ] 配置中不存在已删除的 `trail_spacing`、`trail_start_scale` 或未实现的粒子漂移实验字段。
- [ ] 运行完整 `./gradlew build`。
- [ ] 修改运行载具包后递增 `vehicle_pack.meta.json` 的 `version`。

## 15. 维护原则

- 行为差异继续通过 `wind_data`、`particle_projectile_data`、`spread`、`fire_data` 等 JSON 字段表达，不在实体或 Renderer 中硬编码武器 ID。
- 弹体继续使用 RVP 类型化 Renderer 和 `VehicleProjectileRenderLogic` 既有规则，不直接注册本体 `BulletEntityRenderer`。
- 客户端粒子逻辑继续经桥隔离，公共/服务端代码不得直接引用 `Minecraft` 等客户端类型。
- 新增粒子类型时，应注册独立资源和客户端实现，并补齐资源、数据归一化、专用服务端和性能测试。
- 调整结构模型不属于本效果调参范围；本功能不需要修改任何载具结构模型。
