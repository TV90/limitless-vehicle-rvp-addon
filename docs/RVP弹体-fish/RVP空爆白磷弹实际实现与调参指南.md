# RVP 空爆白磷弹实际实现与调参指南

> 适用项目：`limitless-vehicle-rvp-addon`
>
> 适用版本：Minecraft 1.20.1 Forge
>
> 实现基线：2026-08-25 当前代码与 M142 实机 JSON
>
> 示例武器：`rvp:m142_m30_wp`、`rvp:m142_m30_wp_pellet`

## 1. 文档目的

本文记录空爆白磷弹在项目中的**实际实现**，用于后续维护、载具包配置和效果调参。内容以当前 Java 代码和下列示例为准，不代表早期调研稿中的设想：

- 母弹：[m142_m30_wp.json](../examples/rvp_json/weapons/m142_m30_wp.json)
- 白磷子体：[m142_m30_wp_pellet.json](../examples/rvp_json/weapons/m142_m30_wp_pellet.json)
- M142 挂载：[m142.json](../examples/rvp_json/vehicles/m142.json)

当前效果由三条相互解耦的链路组成：

1. **服务端空爆与毁伤**：近地引信、子弹药释放、母弹爆炸、子体碰撞、点火和实体着火。
2. **服务端子体运动**：水平/竖直半径各 7 格的权威释放云、世界向下分层圆锥撒布、母弹水平速度继承、重力和阻力；M142 当前关闭可选风漂。
3. **客户端视觉**：隐藏子体模型，在权威子体位置附近生成带可调 X/Z 平滑随机闪动的全亮主体，并把上一主体的实际平滑位置逐 Tick 沉积为渐隐尾迹。

粒子不是毁伤判定，客户端粒子多少不会增加点火次数或伤害；服务器也不逐粒广播白磷粒子。

## 2. 当前基线效果

M30 母弹以 `6 格/Tick` 飞行。武装 40 Tick 后，当下方地面进入 70 格近地引信范围时触发空爆：

```text
近地引信触发
  ├─ on_fuse 释放事件（只执行 1 次）
  │    └─ 38 枚白磷子体
  │         ├─ 首 Tick 均匀分布在以释放点为中心、水平/竖直半径各 7 格的三维椭球体积内
  │         ├─ 沿世界正下轴 48° 半角分层撒布，X/Z 径向速度为 0.7 格/Tick
  │         ├─ launch_speed 为 0，圆锥轴向初速为 0；Y 从首 Tick 起由 -0.002 重力下落
  │         ├─ 额外继承母弹 40% 的 X/Z，不继承母弹 Y
  │         ├─ 初始部署 X/Z 按 35 Tick 半衰期连续衰减
  │         ├─ 当前关闭 wind_data，不产生额外风漂
  │         ├─ 服务端重力、阻力、碰撞、直击和点火
  │         └─ 客户端主体火点和上一主体位置尾迹
  └─ parent_action = continue
       └─ 母弹继续执行 80 伤害、4 格半径的普通爆炸
```

释放云是服务端权威初始位置，不是只显示在客户端的爆炸贴图。子体从云内各自的位置继续积分速度、碰撞和毁伤，因此初始云半径也会真实改变后续覆盖。

## 3. 代码与资源入口

| 职责 | 当前入口 |
| --- | --- |
| 弹体总流程、同步标记、风漂调用、客户端视觉桥接 | `org.ywzj.rvp.entity.projectile.RVP_BaseBullet` |
| 子弹药释放 | `org.ywzj.rvp.weapon.submunition.RVP_SubmunitionRunner`、`RVP_SubmunitionSpawner` |
| release 级均匀椭球体积采样 | `org.ywzj.rvp.weapon.submunition.RVP_SubmunitionReleaseCloudUtil` |
| payload 的 box、canister、分层圆锥与云心水平径向散布分派 | `org.ywzj.rvp.weapon.submunition.RVP_SubmunitionSpreadApplicator` |
| canister 连续分布与方形网格 | `org.ywzj.rvp.weapon.util.RVP_SpreadDistributionUtil`、`RVP_CanisterGridUtil` |
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
  "ground_proximity_fuse_distance": 70.0,
  "ground_proximity_fuse_arm_tick": 40,
  "detonate_on_life_end": false
},
"submunition_data": {
  "releases": [
    {
      "triggers": ["on_fuse"],
      "release_events": 1,
      "parent_action": "continue",
      "release_cloud_enabled": true,
      "release_cloud_radius": {
        "horizontal": 7.0,
        "vertical": 7.0
      }
    }
  ]
}
```

- `ground_proximity_fuse_arm_tick` 防止火箭刚离开发射车便被附近地面触发。
- `ground_proximity_fuse_distance` 决定检测到地面时的理论离地距离，但实际触发点还受弹速、弹道角、地形和 Tick 离散检测影响。
- `release_events: 1` 表示本次引信事件只释放一轮。不要把它误当成子体数量；子体数量由 payload 的 `count` 控制。
- `parent_action: "continue"` 让母弹在释放子体后继续原有引爆流程，所以仍会执行 `detonate_data.explosion_data`。
- `release_cloud_enabled: true` 让本 release 的所有 payload 从权威三维云团内生成；默认 false，未配置的其他子母弹保持同点释放。
- `release_cloud_radius.horizontal` 是 X/Z 共用的水平轴半径，`vertical` 是 Y 轴半径，单位均为格；当前 M142 两轴都为 7.0，因此外形仍是球。释放云本身只改变初始位置；若 payload 改用 `cloud_radial_horizontal`，最终出生位置的 X/Z 投影还会决定外散方向。
- 如果想要“只撒布、不发生母弹爆炸”，应调整母弹 `explosion_data` 或使用项目 schema 支持的父弹动作，而不是在粒子代码中屏蔽爆炸。

### 4.2 权威释放云

启用 release 级云团后，每枚子体先在单位球体积内均匀采样，再分别按水平和竖直轴缩放为椭球：

```text
direction = 均匀球面单位向量
normalizedRadius = cbrt(random[0,1))
unitOffset = direction × normalizedRadius
ellipsoidOffset = (unitOffset.x × horizontal,
                   unitOffset.y × vertical,
                   unitOffset.z × horizontal)
spawnPos = parent.position + ellipsoidOffset + payloadPositionOffset
```

立方根归一化半径使单位体积内密度均匀，逐轴缩放后仍保持椭球单位体积密度均匀；若直接线性采样半径，点会错误地堆在云心。当前 M142 水平和竖直半径都为 7 格，所以释放 Tick 的理论最大宽度、高度均为 14 格。把两轴配置成不同值即可得到扁球或长球云团。该位置先于 payload 自身的 `canister_type: 0` 位置散布计算，两者同时配置时偏移相加；云径向模式使用叠加完成后的最终出生位置计算外散方向。

关闭开关或把两个轴半径都设为 0 时，生成器直接沿用母弹位置且不消耗额外随机数。只把某一轴设为 0 会把云团压扁到另外的有效轴上，仍会执行位置采样。多波释放会以每一波发生时母弹的当前位置为新中心。RVP 武器子体和 `kind: entity` 普通实体使用同一规则。

### 4.3 `payloads[].spread.mode` 当前采样模式

`spread` 不是一个只控制“随机大小”的单一参数。当前实现把它拆成**出生位置散布**与**初始速度散布**两次调用，而且两次调用的分支条件不同：

```text
最终出生位置 = release 级椭球体积云位置
             + canister_type=0 时的 payload 位置偏移

散布后速度 = cloud_radial_horizontal 速度（最高优先级）
          或 stratified_cone 速度（次高优先级）
          或 canister_type=1/2 的角度散布
          或 box 的世界轴速度增量
          或原基础速度
```

之后生成器才追加 `inherit_parent_horizontal_velocity` 产生的母弹 X/Z 继承，以及世界系 `payloads_velocity`。因此 `spread` 决定的不是最终总速度；父弹继承、显式冲量、子体 `gravity`、`drag`、部署半衰期和 `max_speed` 仍会继续改变实际弹道。

#### 4.3.1 分支优先级与默认值陷阱

速度阶段的实际判定顺序等价于：

```text
if mode equalsIgnoreCase "cloud_radial_horizontal":
    使用云心水平径向速度
else if mode equalsIgnoreCase "stratified_cone":
    使用世界正下轴分层圆锥速度
else if (mode equalsIgnoreCase "canister" or canister_diff > 0)
        and canister_type >= 1:
    使用 canister 角度散布
else if box_spread > 0:
    给基础速度追加世界 XYZ 轴随机量
else:
    保持基础速度
```

位置阶段则只判断：

```text
if (mode equalsIgnoreCase "canister" or canister_diff > 0)
        and canister_type == 0:
    应用 canister 位置偏移
```

这带来以下必须明确的当前行为：

- 完整默认对象是 `mode: "box"`、`canister_type: 1`、`canister_diff: 0.3`、`box_spread: 0`。由于 `canister_diff > 0` 本身就启用 canister，**省略整个 `spread`，或只写 `mode: "box"`，实际都会得到 0.3° 量级的 canister 角度散布**。
- 要得到真正的 `box` 分支，必须显式写 `canister_diff: 0`；只改 `box_spread` 而保留默认 `canister_diff`，`box_spread` 不会执行。
- `cloud_radial_horizontal` 和 `stratified_cone` 在速度阶段优先于 canister；但若同时写 `canister_type: 0` 且 `canister_diff > 0`，位置阶段仍会先叠加 canister 偏移，专用模式再使用速度分支。
- `canister_type: 0` 不占用 canister 速度分支，所以还能继续叠加 `box_spread`；这是一种“canister 位置 + box 速度”的可组合配置。
- `mode` 比较不区分大小写，但当前不去除首尾空白。未知值、`null` 或带空白的值不会映射为旧别名，也不会报错；它们最终仍由 `canister_diff` 决定走 canister 还是 box 回退。配置时应只使用本文列出的四个规范小写值。
- `box_spread` 与 `canister_diff` 的读取只把负有限值截到 0，没有像 cone/cloud 字段那样处理 NaN 或 Infinity。载具包不得给这两个字段写非有限值，否则可能生成非有限位置或速度。

排除上述兼容启用与刻意组合后，四种纯模式的核心差异如下：

| `mode` | 改变位置 | 改变速度 | 采样坐标系 | 是否保持基础速度模长 | 典型用途 |
| --- | --- | --- | --- | --- | --- |
| `box` | 否 | 直接给 XYZ 分量加随机量 | 世界坐标轴 | 否 | 低成本、无方向约束的轻微速度扰动 |
| `canister` | `type=0` 时是 | `type=1/2` 时改变 pitch/yaw | 位置为世界轴；角度围绕发射姿态 | 角度模式保持 | 霰弹、规则弹丸阵列、出生点散布 |
| `stratified_cone` | 仅可额外叠加 `type=0` canister | 完全重建速度 | 固定世界正下轴 | 未独立配置径向速度时保持 | 向下覆盖均匀的子母弹撒布 |
| `cloud_radial_horizontal` | 仅可额外叠加 `type=0` canister | 完全重建为水平速度 | 最终出生点相对释放中心的 X/Z | 抖动为 0 时保持 | 白磷云、需要从三维云心自然向外展开的载荷 |

#### 4.3.2 `box`：世界轴速度分量扰动

推荐用完整写法明确关闭默认 canister：

```json
"spread": {
  "mode": "box",
  "canister_diff": 0.0,
  "box_spread": 0.4
}
```

设 `b = max(box_spread, 0)`，基础速度为 `V`，每个随机数均独立均匀采样，则实际输出是：

```text
V'.x = V.x + uniform(-b/2, +b/2)
V'.y = V.y + uniform(-b/4, +b/4)
V'.z = V.z + uniform(-b/2, +b/2)
```

这里的 `box_spread` 是完整区间宽度，不是每轴的正负半径；Y 的范围又只有 X/Z 的一半。X/Z 标准差约为 `0.2887b`，Y 标准差约为 `0.1443b`，随机增量的理论最大长度为 `0.75b`。

该增量使用世界 XYZ 轴，不会随母弹或 `launch_yaw`/`launch_pitch` 旋转，也不重新归一化，所以方向和速度模长都会变化。`b <= 0` 时不改变速度且不消耗 box 随机数。它适合小幅打散完全重合的轨迹，不适合要求固定圆锥边界、均匀覆盖或严格等速的弹丸。

#### 4.3.3 `canister`：位置或角度平面采样

角度撒布的常见配置为：

```json
"spread": {
  "mode": "canister",
  "canister_type": 1,
  "canister_diff": 3.0,
  "canister_shape": "circle",
  "canister_distribution": "uniform"
}
```

`canister_type` 读取时限制为 `0..2`：

| 类型 | 当前实际行为 | `canister_diff` 单位 | 速度结果 |
| ---: | --- | --- | --- |
| `0` | 改变子体出生位置 | 格 | 不由 canister 改速；之后仍可进入 box 或专用速度模式 |
| `1` | 给基础 pitch、yaw 增加二维偏移 | 度 | 重新按新角度定向，保持基础速度长度 |
| `2` | 与类型 1 完全相同 | 度 | 当前子弹药实现没有本体 canister type 2 的沿弹轴延迟位移语义 |

`canister_shape` 在此只接受两种有效形状；其他值统一回退 `circle`。`canister_distribution` 与 shape 都会去除首尾空白并忽略大小写，未知 distribution 回退 `uniform`：

- `circle`：使用随机二维平面采样。角度模式把二维结果分别加到 pitch 与 yaw；位置模式把二维结果写入世界 X/Y，再独立采样世界 Z。位置圆面**不是**面向发射方向的局部平面。
- `square`：不做连续随机平面采样，而是根据 `pelletCount` 建立接近方形的确定性网格。角度模式把网格中心映射到 pitch/yaw；位置模式把网格中心映射到世界 X/Y，且 Z 恒为 0。比如 16 枚为 4×4 网格，单轴归一化中心为 `-0.75、-0.25、0.25、0.75`，再乘 `canister_diff`。

`circle` 的二维分布如下：

| `canister_distribution` | 当前随机算法 | 直观效果 |
| --- | --- | --- |
| `uniform` | `r=sqrt(U)`、方位角均匀 | 圆盘单位面积密度均匀，不堆积在中心 |
| `normal` | X/Y 独立高斯，`sigma=0.65`，钳制并拒绝圆外点 | 中心较密，但仍保留明显外围样本 |
| `cluster_center` | 同类高斯，`sigma=0.45` | 比 `normal` 更集中在中心 |
| `cluster_edge` | 从均匀圆盘候选中抽样；半径小于 0.55 时通常拒绝 | 偏向外圈，但仍允许少量中心点 |
| `ring` | 优先接受归一化半径约 `0.52..0.88` 的候选 | 形成约 0.7 半径的宽环带；64 次失败后回退均匀圆盘 |

对 `square`，上述分布不改变网格坐标公式，而是改变网格单元的选择和 pellet 索引顺序。`uniform` 按行列顺序分配；`normal`/`cluster_center` 优先中心单元，`cluster_edge` 优先边缘，`ring` 优先约 0.7 Chebyshev 半径的方环。网格容量恰好等于子体数时，各分布主要改变索引对应关系；容量大于子体数时，还会决定舍弃哪些空余单元。

`canister_type: 0` 配合 `circle` 时，Z 轴也按所选分布独立采样，但有两个实现细节：`cluster_center` 的 Z 轴当前与 `normal` 一样使用 `sigma=0.65`；`ring` 的 Z 轴绝对值范围约为 `0.61..0.79 × canister_diff`。因此它不是严格球体或圆柱体采样。若需要按体积均匀且水平/竖直轴可控的三维椭球云，应使用 release 级 `release_cloud_enabled`，不要用 canister type 0 模拟。

#### 4.3.4 `stratified_cone`：世界正下轴分层圆锥

M142 实机 payload 当前配置为：

```json
{
  "weapon_id": "rvp:m142_m30_wp_pellet",
  "count": 38,
  "inherit_parent_velocity": false,
  "inherit_vehicle_velocity": false,
  "inherit_parent_horizontal_velocity": true,
  "velocity_scale": 0.40,
  "launch_speed": 0.0,
  "payloads_velocity": [0.0, 0.0, 0.0],
  "allow_submunition": false,
  "spread": {
    "mode": "stratified_cone",
    "cone_half_angle": 48.0,
    "cone_radial_speed": 0.7,
    "cone_axis": "world_down",
    "radial_distribution": "uniform_area",
    "azimuth_jitter": 0.08,
    "radial_jitter": 0.08
  }
}
```

该模式不使用传入的发射 yaw/pitch，也不读取出生位置；圆锥轴固定为世界正下方 `(0,-1,0)`。设子体索引为 `i`、本 payload 数量为 `N`、圆锥半角为 `α`：

```text
u = clamp((i + 0.5) / N + uniform(-1, 1) × radial_jitter / N, 0, 1)
cos(theta) = 1 + (cos(α) - 1) × u
azimuth = i × golden_angle
        + uniform(-1, 1) × (360° / N) × azimuth_jitter

velocity = worldDown × cos(theta) × baseSpeed
         + radialDirection(azimuth) × sin(theta) × radialSpeed
```

`cos(theta)` 线性分层使样本在圆锥内按立体角均匀，而不是把 `theta` 线性均分后错误堆向圆锥轴；黄金角约为 137.508°，让少量子体的方位覆盖也较均衡。`pelletIndex` 会按 `N` 取模，`N <= 0` 时输出零速度。

字段边界与实际含义：

- `cone_half_angle` 默认 60°，有限值限制为 `0..180`，非有限值回退 60°。0° 全部竖直向下；90° 覆盖向下半球；**大于 90° 会允许外层样本出现向上的 Y 分量**；180° 等价于整球立体角覆盖。
- `cone_radial_speed` 为空或非有限时沿用 `baseSpeed`，此时输出长度保持为 `baseSpeed`。有限值取非负值，并只缩放 X/Z 径向分量；输出总长度将随 `theta` 改变。
- `azimuth_jitter` 与 `radial_jitter` 都限制为 `0..1`，非有限值按 0。方位扰动最大为 `±360°/N`；径向扰动最大为 `u` 上的 `±1/N`，会让相邻径向层发生重叠，并非严格限制在本层半宽内。
- `cone_axis` 当前无论配置什么都返回 `world_down`；`radial_distribution` 当前无论配置什么都返回 `uniform_area`。这两个字段是当前 schema 的固定值，不存在其他可用采样选项。
- 当 `baseSpeed` 与解析后的 `radialSpeed` 都接近 0 时输出零速度；可以用很小的 `launch_speed` 配合较大的 `cone_radial_speed`，得到缓慢下落但快速横向展开的弹幕。

如果需要严格等速、斜向下的圆锥覆盖，应省略 `cone_radial_speed`。如果需要“X/Z 快速撒开、Y 缓慢下落”，再单独配置它。该模式与 release 云没有方向关联；从宽云内生成时，各点仍围绕同一个世界向下轴发射，轨迹可能彼此交叉。

M142 的 `launch_speed: 0.0` 不启用发射角度分支；由于完整父弹继承被水平继承模式覆盖，生成器先使用沿母弹方向的 `0.5 格/Tick` 有限兜底基速，再由分层圆锥完全重建方向。因此圆锥的向下分量为 `0.5 × cos(theta)`，X/Z 径向分量为 `0.7 × sin(theta)`，随后再追加母弹 X/Z 的 40%。这组圆锥 X/Z 与母弹水平继承共同进入 35 Tick 部署半衰期，圆锥 Y 则作为基础弹道继续叠加重力。

#### 4.3.5 `cloud_radial_horizontal`：云心水平径向散布

独立使用该模式时的示例配置：

```json
{
  "launch_speed": 1.0,
  "payloads_velocity": [0.0, 0.0, 0.0],
  "spread": {
    "mode": "cloud_radial_horizontal",
    "cloud_direction_jitter": 15.0,
    "cloud_speed_jitter": 0.25
  }
}
```

生成器先取得子体最终权威出生位置，再把它相对释放点的偏移传给散布器：

```text
offsetXZ = (spawnPos.x - releaseCenter.x, 0, spawnPos.z - releaseCenter.z)
outward = normalize(offsetXZ)
azimuth = outward.azimuth + uniform(-15°, +15°)
speed = 1.0 × uniform(0.75, 1.25)
cloudVelocity = (cos(azimuth) × speed, 0, sin(azimuth) × speed)
```

外散方向与云内出生点相关，因此子体总体从云心向外展开，不会像独立圆锥那样从云内各点沿无关方向交叉发射。15° 方向扰动与 25% 速度扰动会打破完全规则的放射线，但最大方向扰动限制为 90°，不会把外散速度反向指回云心。

当出生点恰好位于云心水平轴心、只有 Y 偏移，或 X/Z 偏移非法时，散布器会随机选择有限的水平单位方向作为兜底。该模式输出的 Y 恒为 0；云顶部子体不会先上扬，云底部子体也不会获得额外向下喷射速度。

该模式用输入基础速度的**长度**作为径向基速，但完全丢弃输入方向：`launch_yaw`、`launch_pitch`、母弹姿态和原速度 Y 都不会决定外散方向。显式 `launch_speed > 0` 时通常由它提供基速；未显式配置时，则取生成器在进入散布器前解析出的基础速度长度。若基速非有限或不大于 `1e-10`，直接输出零速度。

`cloud_direction_jitter` 是 `[-value,+value]` 的均匀角度扰动，有限值限制为 0～90°；90° 边界最多变成切向，不会指回云心。`cloud_speed_jitter=f` 使速度乘以 `[1-f,1+f]` 内的均匀倍率，`f` 限制为 0～1；当 `f=1` 时理论范围为 0～2 倍，并可能采到接近零的外散速度。方向或速度 jitter 为 0 时，对应阶段不会额外消耗随机数。

若按示例再启用母弹水平继承，则初始速度分量为：

```text
parentHorizontal = (parentVelocity.x, 0, parentVelocity.z) × velocity_scale
deploymentHorizontal₀ = cloudVelocity + parentHorizontal
baseVelocity₀ = (0, 0, 0)
```

以母弹约 6 格/Tick、水平继承 40% 为例，整体前冲约为 2.4 格/Tick；与 0.75～1.25 格/Tick 的径向速度合成后，朝母弹前进方向散开的部分子体可能触发 `max_speed: 3.0` 的统一限速。该示例散布、父弹继承和显式附加速度都不提供 Y，子体从首 Tick 开始由自身重力加速下落。M142 实机当前使用的是上一节的 `stratified_cone`，不能套用本段的纯重力初速结论。

生成器把云径向 X/Z 与母弹水平继承保存为独立部署分量。配置 `deployment_horizontal_half_life_ticks: 30` 后：

```text
deploymentHorizontal(t) = deploymentHorizontal₀ × 2^(-t / 30)
```

因此 30、60、90 Tick 后分别剩余 50%、25%、12.5%。该阻尼不改变 Y，也不作用于显式 `payloads_velocity` 或之后产生的风偏。

### 4.4 可选风向模式

M142 实机 JSON 当前为 `wind_data.enabled: false`，所以下列方向解析和风漂收敛不会参与当前白磷子体弹道；保留的 `north:+90` 等参数只在以后把开关改为 true 时生效。

#### 4.4.1 空爆时母弹当前朝向的反向

子体生成时，`RVP_WindDirectionUtil` 从母弹当时的旋转取得前向单位向量，然后取反：

```text
capturedWind = normalize(-parentCurrentFacing)
capturedWind.y *= vertical_factor
capturedWind = normalize(capturedWind)
```

使用该模式且启用风漂时，M30 子体的 `vertical_factor: 0` 只保留反向朝向的水平投影，可让烟火团向母弹尾后方漂移，同时避免风场直接改变坠落速度。

边界回退按以下顺序进行：

1. 若母弹几乎垂直朝上或朝下，去掉 Y 后向量可能接近零；此时尝试母弹**当前速度的反向水平分量**。
2. 若速度水平分量也接近零，则使用世界 `+Z` 作为稳定兜底。

速度仅用于极端退化回退，不是正常风向来源。因此，“发射后母弹转向，再空爆”的场景会以空爆时的新朝向为准。

捕获的风向属于子体运行时状态。RVP 弹体本身为 `noSave`，不会跨存档持久化该方向。

#### 4.4.2 固定世界水平风向

`direction_mode` 也接受 `north:<角度>`。北方为世界 `-Z`，角度从北方顺时针为正：

```text
north:0    = 北方 -Z
north:+90  = 东方 +X
north:-50  = 北偏西 50°
north:-90  = 西方 -X
north:180  = 南方 +Z
```

转换公式为 `normalize(sin(angle), 0, -cos(angle))`。固定模式在弹体初始化时固化，对首发弹体和子弹药都有效，不读取母弹姿态，也不使用 `vertical_factor`。角度可超过 ±360 并按周期等价；未知前缀、缺失角度、NaN 或 Infinity 会让风漂保持禁用，不会回退为其他方向。

### 4.5 风漂速度收敛

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

M142 配置保留的幅度为 `0.010 格/Tick`、基础频率为 `0`，但当前因总开关关闭而不参与弹道。若启用，频率为 0 时每枚子体保持由自身种子派生的稳定目标偏移方向；把频率调到正数后，目标偏移才会按配置周期平滑旋转。扰动属于有界风速目标，不再作为每 Tick 无界追加的速度冲量。它由服务端计算，实际碰撞、点火和落点都会随轨迹改变，客户端只显示同步结果。

对白磷子体使用的普通弹道分支，整体顺序可概括为：

```text
吸收碰撞等外部改速 → 部署 X/Z 半衰期阻尼
→ 基础速度重力/阻力 → 独立风偏收敛 → 三分量合成
→ 最大速度限制 → 移动与碰撞
```

基础 `drag` 不再阻尼部署 X/Z 或独立风偏，只处理基础弹道中的水平外力；初始水平收束速度由 `deployment_horizontal_half_life_ticks` 决定。三分量合成速度若超过 `max_speed`，会按同一倍率同步缩放，避免下一 Tick 内部状态反弹。

### 4.6 毁伤边界

当前母弹和子体承担不同职责：

| 来源 | 当前效果 |
| --- | --- |
| 母弹 | 80 爆炸伤害、4 格半径、不破坏方块 |
| 子体直击 | `direct_damage: 11.0` |
| 子体落点 | 3 格半径内，以 0.65 概率按方块/实体命中条件点火 |
| 子体实体着火 | 6 格半径、40 秒、目标为 `non_allied` |
| 子体爆炸 | 无 `explosion_data`，不会产生 38 次小爆炸 |

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

每个存活子体按 `body_sample_interval_ticks` 采样生成主体粒子：

- 颜色按主体年龄从出生时的 `body_color` 线性渐变到 `body_end_color`；
- 尺寸以 `body_scale` 为基准；
- `body_flicker` 只随机改变主体的**尺寸**，不是亮度闪烁；
- `body_start_scale` 为 0 时直接使用本次随机目标尺寸；正值且寿命至少为 2 Tick 时，主体从不超过目标的较小尺寸平滑长到 `body_flicker` 目标尺寸；
- `body_horizontal_flicker` 以格为单位，分别为 X、Z 生成 `[-value, +value]` 的独立均匀随机目标偏移，Y 不偏移；
- `body_flicker_interval_ticks` 决定随机尺寸与 X/Z 目标点多久刷新一次；水平位置会在该周期内使用平滑步进曲线从上一实际偏移移动到新目标，而非瞬间跳转；
- `body_sample_interval_ticks` 独立决定主体实际生成间隔，不改变近距离尾迹的逐 Tick 沉积；
- 每个主体的随机目标尺寸会随上一位置状态保存，作为对应尾迹的出生尺寸；启用 `body_start_scale` 不会让尾迹也从小尺寸起步；
- 单粒子存活 `body_lifetime_ticks`；
- `full_bright: true` 时使用全亮光照。

水平闪动只修改客户端粒子的出生坐标，不修改同步弹体实体的位置或速度，因此不会改变服务端碰撞、点火、伤害和落点。默认值为 `0`，未配置的纯粒子弹体保持原视觉位置。

距离玩家超过 512 格时，主体有效采样间隔至少为 2 Tick；配置更大值时仍使用配置值。实际可见范围通常还会受到实体追踪范围和客户端渲染距离限制。

### 5.3 路径尾迹

尾迹不是根据速度向量向后拉线，也不再沿“上一位置 → 当前位置”的单 Tick 运动段插值补点。发射器每 Tick 先计算权威位置附近的平滑水平闪动主体，再把**上一 Tick 的实际平滑主体位置**沉积为恰好一个尾迹粒子，因此显示的是主体实际呈现过的离散历史位置。

尾迹生成同时要求：

- 上一记录与当前实体 Tick 连续；
- 权威弹体位置确实发生移动；
- `trail_enabled: true`；
- 玩家距离不超过 512 格。

首 Tick、静止 Tick、刚进入追踪范围或中间缺少连续实体 Tick 时不生成尾迹，避免视觉随机偏移在静止处制造假尾迹，也避免跨越远距离的错误连接。当前 schema 已删除 `trail_spacing`，也不再存在 128/256 格间距倍率或单 Tick 12 点补点上限；每枚子体每 Tick 最多生成一个尾迹。

`trail_lifetime_start_on_landing` 默认 false，此时每个尾迹仍从出生 Tick 立即消耗 `trail_lifetime_ticks`。设为 true 后，同一子体的全部尾迹共享客户端寿命门：飞行期间年龄保持 0，检测到子体 `onGround`、碰撞结束、实体移除或客户端停止追踪后，寿命门永久打开，所有已存在尾迹才分别从年龄 0 开始消散。寿命门只弱引用子体，不会为了保存尾迹而延长弹体实体生命周期；该字段不改变尾迹出生率、服务器落点或碰撞判定。

### 5.4 粒子生命周期曲线

主体透明度保持不变，颜色按归一化年龄 `t` 从 `body_color` 线性渐变到 `body_end_color`。当 `body_start_scale > 0` 且寿命至少为 2 Tick 时，尺寸按 `smoothstep(t)=t²(3-2t)` 从 `min(body_start_scale, 本次随机目标尺寸)` 增长到目标，并在最后一个可见 Tick 到达；字段为 0、寿命为 1 Tick 或出生尺寸不小于目标时保持目标尺寸。连续采样出生的主体叠加尺寸生长、随机目标和颜色渐变后，会形成红橙闪烁感。M142 当前每 Tick 抽取随机尺寸和 X/Z 目标点并生成主体，每个主体从 0.18 尺寸平滑长到目标。尾迹按 `t` 变化：

- 尺寸：从对应主体的实际尺寸平滑过渡到 `trail_end_scale`；
- 透明度：从 `trail_start_alpha` 平滑过渡到 `trail_end_alpha`；
- 颜色：从 `trail_start_color` 线性过渡到 `trail_end_color`；
- 粒子自身无重力、无碰撞、无速度积分，出生后停留在采样点。

主体尺寸、尾迹尺寸和透明度使用平滑曲线，不会在线性生命周期末端突然硬切。当前 M142 实机配置的尾迹起止颜色均为 `#998E8A` 暖灰色，主要通过尺寸和透明度表现消散；主体则使用小尺寸出生、橙色到浅橙渐变并长到随机目标尺寸的独立效果。

## 6. 当前可复制基线

建议直接以示例 JSON 为权威完整模板。下面列出最常调的子体参数：

```json
"projectile_data": {
  "velocity": 0.1,
  "gravity": -0.002,
  "drag": 0.0058,
  "deployment_horizontal_half_life_ticks": 35,
  "constant_speed": false,
  "rotate_to_motion": true,
  "max_speed": 3.0,
  "wind_data": {
    "enabled": false,
    "direction_mode": "north:+90",
    "speed": 0.08,
    "response": 0.045,
    "vertical_factor": 0.0,
    "turbulence": 0.010,
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
    "body_scale": 0.52,
    "body_start_scale": 0.18,
    "body_lifetime_ticks": 3,
    "body_color": "#FF8A1F",
    "body_end_color": "#FFAB5C",
    "body_flicker": 0.9,
    "body_horizontal_flicker": 0.5,
    "body_flicker_interval_ticks": 1,
    "body_sample_interval_ticks": 1,
    "trail_enabled": true,
    "trail_lifetime_ticks": 400,
    "trail_lifetime_start_on_landing": false,
    "trail_end_scale": 0.03,
    "trail_start_alpha": 0.90,
    "trail_end_alpha": 0.0,
    "trail_start_color": "#998E8A",
    "trail_end_color": "#998E8A"
  }
}
```

颜色接受 `#RRGGBB` 或 `RRGGBB`。格式非法时回退到字段默认值，不支持 8 位 ARGB 字符串；透明度应使用独立 alpha 字段。

## 7. 字段调参表

### 7.1 空爆与撒布

| 字段 | 当前值 | 增大后的主要效果 | 建议起步范围 | 注意事项 |
| --- | ---: | --- | ---: | --- |
| `ground_proximity_fuse_distance` | 70 | 更早、更高空爆 | 20～80 | 不等于严格固定离地高度；受弹速和地形影响 |
| `ground_proximity_fuse_arm_tick` | 40 | 更晚允许空爆 | 20～60 | 过小可能贴近发射车触发；过大可能错过近目标 |
| `release_cloud_enabled` | true | — | 按需求 | 默认 false；控制本 release 是否应用权威出生位置云团 |
| `release_cloud_radius.horizontal` | 7.0 | 初始云团 X/Z 覆盖更宽 | 2～8 格 | X/Z 共用的半径；7 格对应理论最大水平直径 14 格 |
| `release_cloud_radius.vertical` | 7.0 | 初始云团 Y 覆盖更高 | 1～7 格 | 独立 Y 半径；可小于水平值形成扁球云，0 会压扁到水平面 |
| `count` | 38 | 覆盖更密、服务端实体和客户端粒子更多 | 18～38 | 性能成本近似线性增加 |
| `spread.mode` | `stratified_cone` | — | 按覆盖几何选择 | 当前 M142 使用世界向下分层圆锥；四种规范模式见 4.3 节 |
| `launch_speed` | 0.0 | 正值会显式启用发射角度分支 | 通常 0～1 | 当前 0 会走非角度分支，并在零继承基速时触发 0.5 格/Tick 有限兜底 |
| `cone_half_angle` | 48° | 撒布圆锥更宽 | 30～72° | 限制为 0～180°；超过 90° 会允许外层样本出现向上分量 |
| `cone_radial_speed` | 0.7 | X/Z 径向展开更快 | 0.4～1.0 | 实际径向分量还会乘 `sin(theta)`，不会直接恒等于 0.7 |
| `azimuth_jitter` / `radial_jitter` | 0.08 / 0.08 | 分层边界更不规则 | 0～0.2 | 两者均限制为 0～1，实际扰动会按子体数量缩放 |
| `payloads_velocity[1]` | 0 | 正值会让全部子体上扬，负值会统一向下冲 | 通常保持 0 | 当前圆锥自身已有向下 Y 分量，不是纯重力起步 |
| `inherit_parent_horizontal_velocity` | true | — | 按需求 | 只继承母弹 X/Z；开启后覆盖完整父弹继承方式 |
| `velocity_scale` | 0.40 | 母弹前冲对整片弹幕的影响更强 | 0.10～0.40 | 水平继承模式下只缩放母弹 X/Z；过高会更频繁触发 `max_speed` |

### 7.2 子体弹道与风漂

| 字段 | 当前值 | 增大后的主要效果 | 建议起步范围 | 注意事项 |
| --- | ---: | --- | ---: | --- |
| `gravity` | -0.002 | 数值更接近 0 时后续下落加速更慢 | -0.001～-0.005 | 叠加在分层圆锥已有的向下 Y 初速上，不应按纯重力估算落地时间 |
| `drag` | 0.0058 | 基础弹道中的水平外力衰减更快 | 0～0.05 | 不再阻尼部署 X/Z 或独立风偏贡献 |
| `deployment_horizontal_half_life_ticks` | 35 | 数值越大，初始散布与母弹水平继承保留越久 | 10～45 | 35 Tick 减半；0 禁用分量化部署运动并保持旧链路 |
| `max_speed` | 3.0 | 放宽三分量合成后的总速度上限 | 2.2～3.5 | 当前 2.4 水平继承叠加圆锥径向速度后可能触发统一限速 |
| `wind_data.enabled` | false | true 时才启用以下风向、响应和扰动参数 | 按需求 | 当前 M142 不产生风漂；仅调其他风字段不会生效 |
| `wind_data.speed` | 0.08 | 最终尾后漂移速度更大 | 0.04～0.15 | 必须为正，否则风漂整体视为禁用 |
| `wind_data.response` | 0.045 | 更快转向目标风速 | 0.02～0.08 | 0 禁用风漂；过高会像被瞬间拽走 |
| `wind_data.vertical_factor` | 0.0 | 更多保留母弹反向朝向中的 Y | 通常 0～0.2 | 0 会明确保留下坠 Y 速度；大值会改变升降趋势 |
| `wind_data.turbulence` | 0.010 | 平滑游移幅度更大、轨迹更弯 | 0～0.015 | 过大会盖过云径向散布和主风向，并真实改变落点 |
| `wind_data.turbulence_frequency` | 0.0 | 游移方向转动更快 | 0～0.05 | 单位周期/Tick；0 表示每枚子体保持各自稳定扰动方向，过高会产生高频机械抖动 |

风漂启用需要同时满足：

```text
enabled = true
direction_mode = parent_facing_reverse
或 direction_mode = north:<有限角度>
speed > 0
response > 0
```

未知或非法 `direction_mode` 不会回退为其他模式，而是按未启用处理。

### 7.3 主体粒子

| 字段 | 当前值 | 增大后的主要效果 | 建议起步范围 | 注意事项 |
| --- | ---: | --- | ---: | --- |
| `body_scale` | 0.52 | 随机目标火点更大 | 0.25～0.65 | 非负；0 会使主体不可见 |
| `body_start_scale` | 0.18 | 出生火点更大、尺寸生长幅度更小 | 0～0.20 | 0 关闭；正值不超过本次随机目标，寿命至少 2 Tick 才生效，不增加粒子数 |
| `body_lifetime_ticks` | 3 | 生长和颜色渐变更慢，同时存在的主体更多 | 2～5 | 至少为 1；1 会自动关闭出生尺寸生长，成本约随寿命线性增加 |
| `body_color` | `#FF8A1F` | — | 橙黄至橙红 | 主体出生颜色；仅 RGB，不控制亮度 |
| `body_end_color` | `#FFAB5C` | — | 橙红至浅橙 | 主体寿命末端颜色；空值沿用 `body_color`，即关闭颜色渐变 |
| `body_flicker` | 0.9 | 每组随机样本及其对应尾迹的尺寸差异更大 | 0～1 | 限制在 0～1；与红橙年龄渐变叠加形成闪烁，尾迹继承实际尺寸 |
| `body_horizontal_flicker` | 0.5 | 主体及其对应尾迹在 X/Z 上摆动得更明显 | 0～1.5 格 | 每轴独立生成 `[-value, +value]` 目标并平滑到达；只影响客户端视觉，Y 和权威弹道不变 |
| `body_flicker_interval_ticks` | 1 | 闪烁刷新更快，X/Z 更快到达目标 | 1～8 Tick | 至少为 1；1 为即时到达，不决定主体/尾迹出生率或单个主体的尺寸生长 |
| `body_sample_interval_ticks` | 1 | 主体生成频率更高 | 1～4 Tick | 至少为 1；1 表示约每秒 20 次，只控制主体出生率，近距离尾迹仍逐 Tick 沉积 |
| `full_bright` | true | true 时不受环境光压暗 | 通常 true | false 更融入环境，但夜间不再强亮 |

### 7.4 尾迹粒子

| 字段 | 当前值 | 增大后的主要效果 | 建议起步范围 | 注意事项 |
| --- | ---: | --- | ---: | --- |
| `trail_lifetime_ticks` | 400 | 尾迹停留更久 | 80～400 | 最直接的粒子存量放大器；密度固定为每枚移动子体每 Tick 最多 1 个 |
| `trail_lifetime_start_on_landing` | false | — | 按视觉需求 | 当前从出生 Tick 立即计时；true 会保留完整飞行轨迹并提高峰值存量 |
| `trail_end_scale` | 0.03 | 尾迹消失前仍较粗 | 0～0.08 | 想完全收尖可设 0 |
| `trail_start_alpha` | 0.90 | 新尾迹更实 | 0.6～1.0 | 限制在 0～1 |
| `trail_end_alpha` | 0.0 | 尾迹末端更不透明 | 通常 0～0.1 | 大于 0 时会在寿命结束瞬间消失 |
| `trail_start_color` | `#998E8A` | — | 暖灰、淡黄 | 与主体色共同决定热亮感 |
| `trail_end_color` | `#998E8A` | — | 白色、浅灰 | 想表现冷却可改为更暗的暖灰或棕色 |

### 7.5 毁伤与点火

| 字段 | 当前值 | 增大后的主要效果 | 调参风险 |
| --- | ---: | --- | --- |
| 母弹 `explosion_data.damage` | 80 | 空爆中心伤害提高 | 与 38 枚子体叠加时要检查总伤害 |
| 母弹 `explosion_data.radius` | 4 | 中心爆炸范围扩大 | 不是白磷覆盖半径 |
| 子体 `direct_damage` | 11 | 单枚直击更痛 | 多枚命中同目标可能叠加 |
| `fire_data.radius` | 3 | 单枚落点点火搜索更宽 | 服务器方块搜索成本和纵火范围增加 |
| `fire_data.chance` | 0.65 | 可点燃位置成功率提高 | 仍受方块、空气、水和事件条件影响 |
| `ignite_entity_data.radius` | 6 | 实体着火搜索更宽 | 38 枚区域可能大量重叠 |
| `ignite_entity_data.seconds` | 40 | 着火更久 | 影响生物持续伤害，需与玩法平衡联调 |

## 8. 推荐调参顺序

每轮只改一组相关字段，并固定发射距离、俯仰角、目标地形、天气和观察位置。推荐顺序如下：

### 第一步：先确定空爆高度

只调 `ground_proximity_fuse_distance` 和 `ground_proximity_fuse_arm_tick`。在平地上连续发射至少 5 次，确认不会近车误爆，也不会飞过目标后才武装。

### 第二步：确定覆盖几何

先用 `release_cloud_radius.horizontal` 和 `vertical` 分别确定释放瞬间的水平直径与竖直高度，再保持风参数不变，调分层圆锥与水平半衰期：

- 初始云团仍像从一点喷出：确认 `release_cloud_enabled: true`，再分别增大所需方向的轴半径；
- 水平覆盖太窄：先增大 `release_cloud_radius.horizontal`，再按需增大 `cone_half_angle` 或 `cone_radial_speed`；
- 上下出生跨度过大：只减小 `release_cloud_radius.vertical`，不必牺牲水平覆盖；
- 黄金角分层感太强：小幅增大 `azimuth_jitter` 和 `radial_jitter`；
- 展开距离不足但初速合适：增大 `deployment_horizontal_half_life_ticks`；
- 形状满意但密度不足：最后增加 `count`；
- 圆锥向下过快：降低兜底基速来源或调整生成逻辑前先确认 `launch_speed: 0` 的有限兜底语义，不要误以为 0 就代表圆锥 Y 初速为 0。

### 第三步：确定滞空和落地时间

M142 的 `stratified_cone` 会先产生 `0.5 × cos(theta)` 的向下 Y 初速，之后 `gravity: -0.002` 继续加速下落；因此不能直接套用“从 70 格高度纯重力落地”的估算。需要更慢时优先检查圆锥基速来源，再让重力数值更接近 0；需要更快时才增大负值绝对值。`payloads_velocity[1]` 通常保持 0，避免整片云统一上扬或额外下冲。部署 X/Z 的收束只调 `deployment_horizontal_half_life_ticks`，不要用大 `drag` 急刹总速度。

### 第四步：确定尾后漂移

M142 当前 `wind_data.enabled: false`；确需增加尾后漂移时先显式启用，再选择方向来源。需要随空爆姿态变化时使用 `parent_facing_reverse`；需要固定世界风向时使用 `north:<角度>`。前者应使用能明显改变母弹朝向的测试弹道，确认风向随**空爆时当前朝向**变化；后者应面向不同方向发射，确认漂移仍保持同一世界方位。

- 位移方向正确但不够远：增大 `speed`；
- 开始漂得太慢：增大 `response`；
- 弯曲幅度不足：小幅增加 `turbulence`；
- 摆动太慢或太快：调整 `turbulence_frequency`，优先保持在 `0.01～0.05`；
- 下坠被明显干扰：把 `vertical_factor` 降回 0。

不要用 `turbulence` 扩大覆盖；它只改变独立风偏目标。当前覆盖由权威云的两个轴半径、分层圆锥、母弹水平继承和部署半衰期共同决定。

### 第五步：调整火点辨识度

先在白天和夜间各观察一次。用 `body_color → body_end_color` 决定红橙年龄渐变，用 `body_scale` 决定随机目标尺寸、用 `body_start_scale` 决定出生尺寸、用 `body_lifetime_ticks` 决定生长时长，再用 `body_flicker` 调目标尺寸差异。之后用 `body_horizontal_flicker` 调 X/Z 目标范围、用 `body_flicker_interval_ticks` 调目标刷新及平滑移动速度，最后用 `body_sample_interval_ticks` 独立调整主体出生频率。当前水平目标间隔与主体生成间隔均为 1 Tick；只想减少主体数量时仅增大后者。

### 第六步：调整尾迹长度与外观

尾迹密度固定为每枚移动子体每 Tick 最多一个。M142 当前 `trail_lifetime_start_on_landing: false`，每个尾迹从出生 Tick 立即消耗 400 Tick 寿命。若改为 true，`trail_lifetime_ticks` 才表示落地后的继续停留时间。随后再调尺寸、透明度和颜色；若超出预算，应优先保持关闭落地后计时、缩短寿命、减少子体数或关闭尾迹，而不是添加已删除的 `trail_spacing`。

### 第七步：最后平衡毁伤

先确认覆盖区域，再调母弹中心爆炸、子体直击、方块点火和实体着火。视觉覆盖很大并不意味着每个位置都必须具备同等毁伤。

## 9. 三组建议预设

下列值只给出相对当前基线的关键改动，其余字段沿用示例。

### 9.1 稳健性能型

适合多人服务器或需要同时发射多枚火箭的场景：

```json
"count": 18,
"launch_speed": 0.75,
"cloud_direction_jitter": 10.0,
"cloud_speed_jitter": 0.15
```

```json
"body_lifetime_ticks": 2,
"trail_lifetime_ticks": 80,
"trail_lifetime_start_on_landing": false
```

### 9.2 当前均衡型

直接使用 M142 示例：水平/竖直半径各 7 格的权威释放云、38 枚子体、48° 世界向下 `stratified_cone`、0.7 径向速度、两个 0.08 分层扰动、40% 母弹 X/Z 继承、35 Tick 水平半衰期、`-0.002` 重力、关闭风漂、0.52 目标基准、0.18 出生尺寸、3 Tick 生长寿命、0.9 尺寸闪烁、0.5 格主体水平闪动、1 Tick 随机目标间隔与主体采样、400 Tick 尾迹寿命且出生即计时。它应作为实机对照基线。

### 9.3 宽幅展示型

适合单发演示，不建议未经压测直接用于高射速齐射：

```json
"count": 28,
"launch_speed": 1.35,
"cloud_direction_jitter": 22.0,
"cloud_speed_jitter": 0.35,
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

近距离下，每枚子体按 `body_sample_interval_ticks` 生成主体。稳定阶段的粗略存量为：

```text
主体存量 ≈ 子体数 × body_lifetime_ticks ÷ body_sample_interval_ticks
当前基线 ≈ 38 × 3 ÷ 1 = 114
```

### 10.2 尾迹粒子存量

单枚移动子体每 Tick 最多沉积 1 个尾迹点。当前 38 枚子体的理论出生峰值为：

```text
38 × 1 = 38 个尾迹粒子/Tick
```

当前 M142 关闭落地后计时，若持续达到上限，`400 Tick` 寿命对应约 `38 × 400 = 15,200` 个存活尾迹粒子。若改为开启，飞行期间的粗略峰值改为“38 × 每枚子体已沉积的可见飞行 Tick”；落地后这些历史点再各自保留最多 400 Tick，因此总存量上界近似：

```text
尾迹峰值 ≈ 子体数 ×（可见飞行沉积 Tick + trail_lifetime_ticks）
```

实际数量还会受到各子体落地时间差、静止 Tick、512 格距离限制和客户端粒子总量限制影响。长滞空时，开启本开关可能比当前 400 Tick 滑动窗口保留更多粒子。

调优优先级：

1. 关闭 `trail_lifetime_start_on_landing`；
2. 减少 `trail_lifetime_ticks`；
3. 减少 `count`；
4. 必要时关闭 `trail_enabled`；
5. 最后才缩短主体寿命，因为主体对识别弹体位置很重要。

`full_bright`、颜色和尺寸通常不是数量级性能因素；数量、寿命和同时存在的弹体数才是主要因素。

## 11. 常见症状与排查

| 症状 | 优先检查 | 说明或处理 |
| --- | --- | --- |
| 空爆后没有子体 | `on_fuse`、`weapon_id`、`release_events`、武装 Tick | 确认子体武器已被载具包索引加载 |
| 子体数量像平方增长 | `release_events` 是否误设为子体数 | 一轮释放使用 `1`，数量只放在 payload `count` |
| 空爆太低或撞地才爆 | 引信距离、武装 Tick、母弹速度 | 高速弹每 Tick 跨越距离大，触发高度会离散 |
| 子体仍从同一点喷出 | `release_cloud_enabled`、`release_cloud_radius`、服务端 addon 版本 | M142 应启用水平/竖直各 7 格的权威释放云；客户端视觉闪动不能替代服务端生成位置 |
| 云团宽高与配置不符 | 把半径误认为直径或混淆两个轴 | `horizontal: 7` 对应最大水平直径 14 格，`vertical: 7` 对应最大高度 14 格；每个点都应满足椭球边界方程 |
| 子体整体继续向前冲 | `inherit_parent_horizontal_velocity`、`velocity_scale` | 当前明确保留 40% 母弹 X/Z 继承；这是整体平移，不是云径向采样错误 |
| 展开满意但下落仍太快 | `gravity` | 让负值更接近 0；不要添加正 Y 补偿，否则整片云会先上扬 |
| 子体悬停或上飘 | `payloads_velocity[1]`、圆锥参数、客户端/服务端版本 | 当前附加 Y 为 0，48° 向下圆锥自身应提供负 Y；正附加值可能抵消下落 |
| 完全没有风漂 | `wind_data.enabled` | 当前 M142 明确设为 false，这是预期行为；开启后其他风参数才生效 |
| 风向仍像发射方向 | 检查实际运行包版本和 `direction_mode` | 仅启用 `parent_facing_reverse` 时应捕获空爆 Tick 的母弹旋转 |
| 垂直发射时风向异常固定 | 水平投影退化 | 会回退到当前速度反向水平分量，再退化则世界 `+Z` |
| `north:+90` 漂向西方 | 客户端/服务端代码版本或角度约定 | 当前约定正角从北方顺时针，+90 必须指向世界 +X；确认双方均为当前版本 |
| 固定风向完全不生效 | 前缀、角度、enabled/speed/response | 只接受 `north:<有限角度>`；NaN、Infinity、空角度和其他前缀会禁用风漂 |
| 下坠速度被风明显拉慢 | `vertical_factor` 非零 | 水平尾后漂移应设为 0 |
| 子体没有形成向下分层圆锥 | `spread.mode`、`cone_half_angle`、服务端版本 | 当前必须为 `stratified_cone`、48°；该模式不依据云内出生点决定方向 |
| 子体先横飞再急刹 | 过高圆锥径向速度、母弹水平继承或过短水平半衰期 | 当前使用 0.7 径向速度、40% 前冲、35 Tick 半衰期和 3.0 最大速度 |
| 散布出现大片空洞 | 两个 cone jitter 过大或数量太少 | 先降低 `azimuth_jitter` / `radial_jitter`，再适度提高 `count` |
| 子体模型可见 | `particle_projectile_data.enabled`、客户端/服务端版本、类型化 Renderer | 不要在 Renderer 中按武器 ID 特判 |
| 模型消失但完全无粒子 | `particle_type`、非 JSON Provider 注册、权威纹理、客户端数据包版本 | 当前发射器只识别已注册的 `rvp:white_phosphorus`；不要按贴图目录改成 `ywzj_rvp:white_phosphorus`，并确认客户端已注册 Provider 且权威 PNG 已打包 |
| 尾迹出现断点 | 主体静止、超过 512 格、客户端 Tick 不连续 | 这些场景不会沉积尾迹；刚进入追踪范围的首 Tick 不留旧位置是预期行为 |
| 尾迹飞行中完全不消散 | `trail_lifetime_start_on_landing: true` | 这是预期行为；子体落地或结束后才从年龄 0 开始按 `trail_lifetime_ticks` 消散 |
| 子体消失后尾迹仍不开始消散 | 客户端版本或弹体未真正结束 | 当前寿命门会在 onGround、实体非 alive、弱引用释放时打开；确认客户端使用当前版本 |
| 主体和尾迹横向摆动过大 | `body_horizontal_flicker` 过大 | 降低该值；它是每个 X/Z 轴的最大随机目标偏移，不是总半径 |
| 主体只有单色、没有红橙渐变 | `body_end_color`、主体寿命、客户端版本 | 末端颜色为空时会沿用出生颜色；确认客户端已加载当前 schema |
| 主体没有从小变大 | `body_start_scale`、`body_lifetime_ticks` | 出生尺寸须大于 0 且寿命至少为 2 Tick；M142 当前使用 0.18 与 3 Tick |
| 尺寸生长幅度不明显 | 出生尺寸接近随机目标 | 降低 `body_start_scale` 或提高 `body_scale`；若目标偶尔小于出生配置，会自动钳到目标而不反向缩小 |
| 有渐变但闪烁不明显 | `body_flicker`、`body_lifetime_ticks` | 增大尺寸随机比例或调整主体寿命；M142 当前使用 0.9 与 3 Tick |
| 主体闪烁和水平移动过快 | `body_flicker_interval_ticks` | 增大过渡 Tick；4 表示每秒约到达 5 个随机目标，且不会直接改变粒子出生数 |
| 主体水平移动过慢 | `body_flicker_interval_ticks` 过大 | 降低过渡 Tick；通常先在 2～8 内调节，1 会恢复即时跳转 |
| 主体出现得过密或采样太快 | `body_sample_interval_ticks` | 增大主体采样间隔；2 表示每秒约生成 10 次，近距离尾迹密度不变 |
| 主体出现明显空窗 | 主体采样间隔大于主体寿命 | 提高 `body_lifetime_ticks` 或降低 `body_sample_interval_ticks` |
| 静止子体仍出现连续假尾迹 | 客户端/服务端版本或旧实现 | 当前实现必须用权威弹体位置判断移动，视觉闪动本身不应触发尾迹 |
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

- `RVP_WindDirectionUtilTest`：当前朝向反向、垂直投影、退化回退，以及固定北向角 0/+90/-50/超周角转换；
- `RVP_WindDriftUtilTest`：旧总速度风漂兼容、独立风偏收敛、Y 分量隔离及有界扰动；
- `RVP_DeploymentMotionUtilTest`：20/40/60 Tick 半衰期精度、方向稳定和 Y 隔离；
- `RVP_SubmunitionStratifiedConeTest`：旧分层圆锥的 72° 等模长采样、独立径向速度及非法值回退；
- `RVP_SubmunitionCloudRadialSpreadTest`：水平外向关系、Y 为 0、方向/速度扰动边界、退化偏移有限兜底，以及椭球出生位置与 40% X/Z 继承的组合；
- `RVP_SubmunitionReleaseDataTest`：释放云默认关闭、两个轴默认 4 格、显式独立配置、空对象、负值/非有限值回退，以及旧标量形状不再被当前 schema 接受；
- `RVP_SubmunitionReleaseCloudUtilTest`：关闭/双零半径不消耗随机数，水平 4 格、竖直 2 格采样严格位于椭球内并符合均匀体积统计；
- `RVP_ParticleProjectileDataTest`：字段默认、范围限制、`body_start_scale` 默认关闭与非有限值回退、闪动/主体采样间隔最小值、水平闪动非有限值回退、主体起止颜色解析及末端颜色回退、已删除 `trail_spacing`/`trail_start_scale` 不再属于数据模型，以及 `rvp:white_phosphorus` 运行时 ID 稳定性；
- `RVP_ParticleProjectileEmitterTest`：水平偏移平滑采样、1 Tick 即时兼容、出生尺寸默认关闭、正值启用以及大于随机目标时不反向缩小；
- `RVP_WhitePhosphorusResourceTest`：权威纹理位于 `assets/ywzj_rvp` 且资源有效，同时不存在旧粒子 JSON 和图集追加文件。

### 12.2 游戏内功能测试

| 编号 | 场景 | 通过标准 |
| --- | --- | --- |
| A1 | 平地水平射击 | 约 70 格近地条件触发，40 Tick 前不在发射车旁误爆 |
| A1-1 | 检查释放 Tick 的服务端位置 | 38 枚子体不再共用母弹坐标，满足水平/竖直各 7 格的椭球边界并形成三维云团 |
| A2 | 临时启用 `parent_facing_reverse` 后改变母弹朝向 | 子体风漂使用空爆时朝向的反向，不沿最初发射方向 |
| A3 | 临时启用风漂后朝东、南、西、北各射击 | 漂移方向符合所选模式；恢复实机 `enabled: false` 后无额外风漂 |
| A3-1 | 使用 `north:+90` 朝四个方向发射 | 所有弹体均向世界东方 +X 漂移，不随发射方向旋转 |
| A3-2 | 使用 `north:-50` 发射 | 漂移稳定指向北偏西 50°，无 Y 分量 |
| A4 | 大俯角和近垂直弹道 | 无 NaN、停滞或失控速度；退化回退稳定 |
| A5 | 观察撒布俯视形状 | 38 枚从 7×7 格轴半径云内按黄金角分层圆锥撒开；35/70 Tick 后部署 X/Z 约剩 50%/25% |
| A5-1 | 观察云上半部子体 | 48° 世界向下圆锥不产生上扬样本；子体从首 Tick 起同时受圆锥向下初速和重力影响 |
| A6 | 单人客户端逐帧观察 | 子体无模型闪现；尾迹只在上一主体位置出现，无线段补点和静止堆积 |
| A6-1 | 切换 `body_horizontal_flicker` 为 0 与 1.5 | 0 时主体位于权威轨迹；1.5 时仅沿 X/Z 平滑移动到随机目标，Y、命中点和点火区域不变 |
| A6-2 | 观察主体颜色与尺寸 | 单个主体从 0.18 平滑长到本次 `body_flicker` 目标，并从 `#FF8A1F` 渐变至 `#FFAB5C`；最后一个可见 Tick 到达尺寸和颜色末端 |
| A6-3 | 比较闪动间隔 1、2、4 Tick | 随机尺寸与 X/Z 目标分别约每秒刷新 20、10、5 次；1 Tick 即时到达，2/4 Tick 平滑到达，且不改变主体和尾迹出生率 |
| A6-4 | 比较主体采样间隔 1、2、4 Tick | 主体分别约每秒生成 20、10、5 次；近距离尾迹仍逐 Tick 沉积且无 512 格边界长连接 |
| A6-5 | 切换 `trail_lifetime_start_on_landing` | 当前 false 时尾迹出生即衰减；true 时飞行期间年龄保持 0，子体落地/结束后才开始 400 Tick 消散 |
| A7 | 专用服务端 + 客户端 | 服务端不加载客户端类，双方弹道与毁伤一致 |
| A8 | 512 格边界及重新进入追踪范围 | 超过 512 格不生尾迹，重新进入时不连接旧位置或产生粒子尖峰 |
| A9 | 子体落在可燃与不可燃环境 | 点火概率和环境限制符合预期，无子体小爆炸 |
| A10 | 多发齐射 | TPS、客户端 FPS 和粒子存量可接受 |

### 12.3 回归检查

- 未配置 `wind_data` 的旧武器弹道不应改变。
- 未配置 `deployment_horizontal_half_life_ticks` 的旧子弹药继续使用原总速度积分。
- 未配置 `inherit_parent_horizontal_velocity` 时保持原完整父弹继承规则。
- 未配置 `cone_radial_speed` 的分层圆锥仍应使用原有等模长速度，不改变其他子母弹。
- 未配置 `cloud_radial_horizontal` 的旧武器不读取出生位置来改变速度；两个 cloud jitter 默认均为 0。
- 未配置 `release_cloud_enabled` 时默认 false，既有 release 继续从母弹位置生成且不额外消耗随机数。
- `wind_data.enabled: false` 时不应产生风漂。
- 未配置 `particle_projectile_data` 的弹体仍使用原有模型和效果。
- 未配置 `body_start_scale` 时按 0 处理，主体直接使用随机目标尺寸，保持旧尺寸行为。
- 未配置 `body_horizontal_flicker` 时按 0 处理，主体和尾迹位置应保持旧行为。
- 未配置 `body_flicker_interval_ticks` 时按 1 处理，保持原每 Tick 重抽并立即到达随机偏移的行为。
- 未配置 `body_sample_interval_ticks` 时按 1 处理，近距离主体保持原每 Tick 生成行为。
- 未配置 `trail_lifetime_start_on_landing` 时按 false 处理，所有尾迹继续从各自出生 Tick 立即计时。
- 非白磷粒子 ID 不应错误调用白磷专用发射器。
- 服务端判定不应依赖客户端是否开启粒子或粒子设置高低。

## 13. 当前限制

1. `direction_mode` 支持 `parent_facing_reverse` 与 `north:<有限角度>`；未知格式不会自动兼容或回退。
2. 分层圆锥仍面向 `world_down + uniform_area` 组合；云心水平径向模式是独立采样路径，不解释任何 `cone_*` 字段。
3. 专用粒子发射器当前识别 `rvp:white_phosphorus`，不能只改字符串就获得任意新粒子弹体。
4. 白磷尾迹是客户端视觉，不碰撞、不照明方块、不参与伤害；主体和尾迹可在权威轨迹附近进行可调 X/Z 闪动，出生后均停留在各自采样坐标。
5. 子体不提供独立白磷附着、持续区域灼烧或车辆通用 DOT 系统。
6. 相对风向在释放瞬间固化，固定风向在弹体初始化时固化；之后均不会继续跟随母弹旋转，也不会随世界天气动态改变。
7. RVP 弹体不保存到磁盘；服务器重启后不会恢复半空中的母弹、子体或其捕获风向。

## 14. 发布前检查清单

- [ ] 母弹 `release_events` 为 1，payload `count` 才是实际子体数。
- [ ] `release_cloud_enabled=true`，且 `release_cloud_radius.horizontal/vertical` 已分别配置；当前 M142 均为 7 格，对应约 14 格最大宽高。
- [ ] 子体 `weapon_id` 与载具包中的文件名、命名空间一致。
- [ ] M142 使用 `stratified_cone`、48° 半角、0.7 径向速度与两个 0.08 分层扰动，不保留无效 `cloud_*` 字段。
- [ ] 圆锥轴为 `world_down`、`payloads_velocity` 的 Y 为 0，实测所有子体从首 Tick 起向下运动、无悬停或上飘。
- [ ] `inherit_parent_horizontal_velocity=true` 且 `velocity_scale` 已限制母弹水平继承。
- [ ] `deployment_horizontal_half_life_ticks` 已按覆盖距离验证，0 表示完全关闭新分量链路。
- [ ] 当前 M142 `wind_data.enabled=false`；若启用风漂，已验证所选方向模式和不同发射朝向。
- [ ] `turbulence_frequency` 已按周期/Tick 配置，并确认不同子体不会同步摆动。
- [ ] 水平漂移需求下 `vertical_factor` 为 0。
- [ ] 子体未意外配置 `explosion_data`。
- [ ] `particle_type` 为 `rvp:white_phosphorus`，并发布 `assets/ywzj_rvp/textures/nuclear/particle_base.png`；数据 ID 不随资产目录改变，包内不存在旧粒子 JSON 或图集追加文件。
- [ ] `body_color`、`body_end_color` 已在白天和夜间验证红橙渐变；未配置末端颜色的旧纯粒子弹体仍保持单色。
- [ ] `body_start_scale` 已验证 0 与目标值；启用时 `body_lifetime_ticks >= 2`，主体只从小变大且尾迹仍继承随机目标尺寸。
- [ ] `body_flicker_interval_ticks` 已按尺寸刷新与 X/Z 平滑移动速度调节，且未误当成粒子生成间隔。
- [ ] `body_sample_interval_ticks` 已按主体出生频率调节，并确认近距离尾迹密度不随之降低。
- [ ] `body_horizontal_flicker` 已验证 0 与目标值；主体和尾迹沿 X/Z 平滑移动，静止子体不会因视觉随机量生成假尾迹。
- [ ] `trail_lifetime_start_on_landing` 已验证 false/true；开启时落地前不衰减、弹体结束后会开始计时，长滞空齐射的粒子峰值可接受。
- [ ] 客户端与服务端使用同版本 addon 和载具包。
- [ ] 多发齐射完成 FPS/TPS 压测。
- [ ] 配置中不存在已删除的 `trail_spacing`、`trail_start_scale` 或未实现的粒子漂移实验字段。
- [ ] 运行完整 `./gradlew build`。
- [ ] 本地 `run/server` 调参后执行 `/ywzj_vehicle reload`；正式发布载具包时再按发布流程递增版本。

## 15. 维护原则

- 行为差异继续通过 `release_cloud_*`、`wind_data`、`particle_projectile_data`、`spread`、`fire_data` 等 JSON 字段表达，不在实体或 Renderer 中硬编码武器 ID。
- 弹体继续使用 RVP 类型化 Renderer 和 `VehicleProjectileRenderLogic` 既有规则，不直接注册本体 `BulletEntityRenderer`。
- 客户端粒子逻辑继续经桥隔离，公共/服务端代码不得直接引用 `Minecraft` 等客户端类型。
- 新增粒子类型时，应注册独立资源和客户端实现，并补齐资源、数据归一化、专用服务端和性能测试。
- 调整结构模型不属于本效果调参范围；本功能不需要修改任何载具结构模型。
