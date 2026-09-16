注：表格中的字段名称为java类中使用的驼峰命名法，在json中需使用全小写+下划线连接的格式

# RVP_LaserData

激光武器数据模型，JSON 键 `laser_data`，适用于 `type: "rvp:laser"` 武器（注册 id `rvp:laser`，数据类共用 `RVP_WeaponData`，激光专属分组为 `laser_data`）

激光武器是一种**瞬时射线武器**：按下开火键后，服务端沿武器站当前瞄准方向发射即时射线，对首个命中的方块/实体直接结算一次伤害，随后进入 `shoot_interval` 冷却；按住不放即以该间隔连续脉冲。**不发射任何弹体**（无 projectile 实体、无弹道、无散布、不爆炸、不点燃），光束本身是纯客户端表现，每帧跟随武器站实时瞄准重算，任意观察者看到的光束都不会穿墙。

| RVP_LaserData字段 | 解释 | 类型 | 默认值 |
| ----------------- | ---- | ---- | ------ |
| range | 射线最大射程（格），getter 下限钳到 1。另：本字段同时是 `rvp:targetingpod` 目标指示吊舱的方块标记射线长度（吊舱 `targeting_pod_data.block_range` 未配置时的回退值，见 RVP_WeaponData.getTargetingPodRange） | float | 512 |
| blind_hit_count | **激光致盲：触发致盲所需命中次数。默认 0 = 功能关闭**（仅显式配置 >0 的激光生效）。命中玩家/gunner/其骑乘的载具时累计，窗口内攒满触发致盲，触发后计数清零重新累计 | int | 0 |
| blind_hit_window_tick | 激光致盲命中累计窗口（tick，20tick=1秒），窗口内攒满次数才触发，超窗清零重计 | int | 100 |
| blind_duration_tick | 激光致盲时长（tick）；0 = 关闭。玩家=白色闪光滤镜，gunner=失去目标（详见"激光致盲"节） | int | 200 |
| visual_data | 光束外观配置（仅客户端渲染消费），见下表 | RVP_LaserVisualData | 见下表 |

## RVP_LaserVisualData字段（`laser_data.visual_data`）

| RVP_LaserVisualData字段 | 解释 | 类型 | 默认值 |
| ----------------------- | ---- | ---- | ------ |
| color | 光束颜色，RGBA 四通道 0-255 整数列表（少于 3 个元素回退默认灰 `0xFFA0A0A0`）。渲染时按蓄力比例压缩透明度（最低 15%） | List\<Integer\> | null（灰） |
| width | 光束宽度（方块单位）。getter 下限 0.2，渲染时再钳到 [0.05, 0.5] | float | 0.2 |
| duration_tick | 停火后光束残留显示的 tick 数（20tick=1秒），连发时每次开火刷新计时。getter 下限 2 | int | 20 |
| pulsate | 光束宽度是否脉动（宽度乘 `0.7+0.3*sin(t*0.4)`） | boolean | true |
| render_start_distance | 从炮口起跳过多少距离再开始绘制光束（用于出膛裁剪的视觉补偿）；几何裁剪时下限 0.5 | double | 0.0 |
| segment_length | 光束分段渲染的每段长度（格）；过长光束会合并段数至最多 64 段 | float | 0.4 |

## 通用字段在激光武器上的有效性

激光武器共用 `RVP_WeaponData`/`BaseVehicleWeaponData` 的通用字段，但只有下表左列字段有实际消费点：

| 通用字段 | 在激光武器上的语义 | 有效性 |
| -------- | ------------------ | ------ |
| `shoot_interval` | **每次伤害脉冲的间隔**（每间隔打一次射线结算一次伤害），不是弹速/射速 | ✅ |
| `max_capacity` + `reload{time, ammo}` | 真实消耗：每次脉冲 `consumeAmmo`，弹药耗尽/装填中光束立即消失；炮手补给同样适用 | ✅ |
| `damage` + `collision_data.direct_damage` | 每次脉冲的基础伤害（collision override 优先） | ✅ |
| `collision_data.direct_damage_factor` | 按目标类别的伤害倍率（经 RVP_DamageApplier.applyScaled） | ✅ |
| `recoil` | 每次脉冲对载具物理引擎施加后坐力 | ✅ |
| `fire_data.fire_mode` | FULL_AUTO/SEMI_AUTO/BURST/CHARGE/MINIGUN/RAILGUN 全部可用 | ✅ |
| `fire_data.charge_tick`/`charge_power_scale` | 蓄力伤害放大（见下文"开火模式与蓄力"）；**注意 charge_tick 默认 10 会让非蓄力模式的光束变暗，见提示** | ✅ |
| `fire_data.heat_count`/`max_heat_count`/`overheat_extra_heat` | 过热积累与冷却门控 | ✅ |
| `fire_data.max_off_axis_shoot_angle` | 离轴射击角度门控 | ✅ |
| `effects_data.num_particles_flak`/`flak_particles_diff`/`impact_particle` | 方块命中火花粒子的数量/散布/开关（`"none"` 关闭） | ✅ |
| `projectile_data`/`velocity`/`gravity`/拖阻类 | 无弹体，全部不消费 | ❌ |
| `fuse_data`/`detonate_data` | 不爆炸不点燃，不消费 | ❌ |
| `submunition_data`/`dispenser_data` | 无子母/布洒，不消费 | ❌ |
| `guidance_data` | 激光武器自身不制导、不提供照射，不消费 | ❌ |
| `life`/`headshot_multiplier`/`inaccuracy`/`fire_data.spread` | 射线即时判定，无寿命/爆头/散布实现 | ❌ |

## 激光致盲（blind_* 字段，默认关闭）

命中**玩家 / gunner / 其骑乘的载具**时累计受击次数；`blind_hit_count` 次（须在
`blind_hit_window_tick` 窗口内）触发致盲，**触发时命中计数清零、窗口重开、重新累计**
——再次攒满才再次致盲（刷新致盲时长）；窗口超时未达标同样清零重计。

- **玩家致盲**：服务端发 `S2CLaserBlind` 同步包 → 客户端全屏白色闪光滤镜
  （`RVP_LaserBlindOverlay`：前 70% 时长全亮、后 30% 线性渐隐），期间再次触发刷新时长；
- **gunner 致盲**：服务端致盲状态表标记（`RVP_LaserBlindService.isBlinded`）——期间
  视觉索敌失效（武器可选性收紧导致 `hasUsableWeaponForTarget` 失败，自然失去目标）；
- **有雷达载具的 gunner 例外**：致盲期间仍可作战，但只能选用雷达制导/雷达中段的
  **SARH/ARH/AIR** 导弹（发射仍需本车雷达锁定授权——96L6 搜索中继不算，TADS 火控中继算）；
  无雷达载具的 gunner 致盲期间无可用武器=彻底失去目标；
- CIWS 拦截来袭导弹不变（雷达指向，非目视）；射手不会被自己的激光致盲（射线 canHit 已排除）；
- `blind_duration_tick: 0` 或 `blind_hit_count: 0`（默认）= 该激光不致盲。

## 行为说明

### 射线检测

1. 炮口出膛裁剪：从炮口沿视线以 0.08 步进（上限 3.0 格）前进，找到第一个离开载具包围盒（膨胀 0.25）且无方块遮挡的点作为光束起点，最小起绘距离取 `max(render_start_distance, 0.5)`；
2. 方块射线（`ClipContext` Block.COLLIDER、忽略流体）与实体射线（`ProjectileUtil.getEntityHitResult`）同时发射，取**最近**命中；
3. 实体命中过滤（canHit）：死亡/不可选中的实体、发射载具自身、射手本人、载具乘客一律跳过；
4. 未命中任何实体时仅消耗弹药与冷却，无任何效果。

### 伤害结算

命中实体的一次脉冲按以下顺序结算：

1. 伤害源为本体子弹伤害类型、direct=射手（非投射物，因此命中载具时本体走 `hitPos==null → scale=0.2` 分支）；
2. 基础伤害 = `damage（或 collision_data.direct_damage）× 蓄力倍率`；
3. `RVP_DamageApplier.applyScaled`：乘 `collision_data.direct_damage_factor` 的目标类别倍率；
4. **对载具（AbstractVehicle）**：命中箱系数（按 structureModel 骨架/默认系数解析）→ 装甲（`armor_min_damage`/`armor_max_damage`，未配置装甲时等价于命中伤害×系数）→ **core_distance 预补偿**（按 `core_distance_scale_multiplier` 用 `amount' = amount/falloff × (1+(falloff-1)×coreMult)` 抵消本体 0.2 衰减，并跳过全局二次缩放）→ 有机会打掉骨块模块；
5. **对普通实体**：直接 `EntityUtil.hurt` 全额伤害；
6. 不点燃、不爆炸、无实体命中粒子（仅方块命中火花，见渲染节）；
7. **命中载具触发激光照射告警**：向目标乘客发 `S2CMissileTrackAlert(TYPE_LASER)`（客户端播 `laser_alert` 音效 + "被激光照射"提示；友方不告警；同"射手车→目标车"对 10t 节流）。

### 开火模式与蓄力

- 六种 `fire_mode` 均可用；CHARGE/MINIGUN/RAILGUN 显示蓄力条 HUD（`RVP_ChargeBarOverlay`）；
- 蓄力伤害放大：释放时伤害 = 基础 × `1 + (charge_power_scale - 1) × 蓄力比例`（FULL_AUTO/SEMI_AUTO 蓄力比例恒 0，倍率恒 1）；
- **光束亮度提示**：光束 alpha 按蓄力比例压缩（最低 15%），而 `fire_data.charge_tick` **默认值为 10**——即 FULL_AUTO 且未显式写 `charge_tick: 0` 时，常亮光束会以约 15% 透明度渲染。**想要全亮常亮光束请写 `"charge_tick": 0`**；
- 过热：`heat_count`/`max_heat_count`/`overheat_extra_heat` 与弹体武器同路径（`RVP_HeatHudOverlay` 亦支持激光武器）。

### 弹药、过热与射击门控

每次脉冲依次通过：冷却（`shoot_interval`）→ 装填 → 过热 → `canShootOnServer`（含 `max_off_axis_shoot_angle` 离轴门控、发射架展开门控）→ `consumeAmmo` 真实耗弹。任意一门失败该脉冲不结算；客户端在无弹药/装填中不渲染光束。

### 客户端光束渲染

- **驱动**：本端玩家按住开火键每 tick 脉冲；其他玩家/远端经本体广播的 `VehicleFireEvent.Post` 驱动——**没有 RVP 专用激光同步包**，光束同步寄生在本体开火事件上；
- **几何**：客户端每帧插值武器站瞄准后**重新执行与服务器相同的射线检测**，因此光束终点永远贴着真实命中面、不会穿墙；端点做时间混合平滑防止逐 tick 跳变；
- **外观**：以武器显示 JSON（`assets/rvp/display/weapon/<id>.json` 的 model/texture）指定的 BedrockModel 拉伸为光束，`energySwirl` 全亮度渲染；未配置时回退本体 `ywzj_vehicle:entity/basic_bullet` 模型与贴图；
- **命中火花**：方块命中每 2 tick 生成 `num_particles_flak`（默认 3）组 CLOUD+SMOKE+FLAME 粒子，散布 `flak_particles_diff`（默认 0.3）；`effects_data.impact_particle: "none"` 关闭；
- **音效**：开火/蓄力音走武器显示配置的 `sound_events`（如 `"charge"`），shoot 逻辑内无硬编码音效。

## 与其他系统的联动

| 联动系统 | 说明 |
| -------- | ---- |
| 激光告警 LWR | `rvp:laser` **命中敌对载具时向其乘客发 TYPE_LASER 激光照射告警**（同 LWR 提示与 `laser_alert` 音效；友方不告警；同一"射手车→目标车"对 10t 节流）。注意它与操作手照射会话型 LWR（`RVP_LaserWarnService` 扫描 LBR/LH/SALH 照射点）是两条独立触发路径——激光武器不产生照射会话，但命中即告警 |
| 激光致盲 | 命中累计达标（`blind_*` 字段，**默认关闭**）→ 玩家白色闪光滤镜、gunner 失去目标（详见"激光致盲"节） |
| LH/SALH 激光制导 | 激光武器**不提供照射点/designation**，与操作手照射会话、GPS 目标点均相互独立（同吊舱文档"方块标记与激光照射点不互通"的口径） |
| 装甲/命中箱/core_distance | 与弹体武器完全同路径（命中箱系数、armor_min/max、core_distance 预补偿、骨块破坏） |
| 目标指示吊舱 | `laser_data.range` 同时是 `rvp:targetingpod` 的方块标记射线长度（吊舱 `block_range` 未配置时的回退值） |
| 炮手 AI | 炮手可操作激光武器（弹药补给、武器适配走通用路径），射击门控与玩家一致 |

## JSON配置示例

### 反载具高伤脉冲激光

```json
{
  "type": "rvp:laser",
  "name": "Anti-Vehicle Pulse Laser",
  "shoot_interval": 15,
  "max_capacity": 600,
  "reload": { "time": 100, "ammo": "ywzj_vehicle:ammo_creative" },
  "damage": 25,
  "recoil": 0.5,
  "collision_data": {
    "direct_damage": 30,
    "direct_damage_factor": { "vehicle": 1.2 }
  },
  "fire_data": {
    "fire_mode": "FULL_AUTO",
    "charge_tick": 0,
    "heat_count": 4,
    "max_heat_count": 200
  },
  "laser_data": {
    "range": 256,
    "visual_data": {
      "color": [255, 40, 40, 230],
      "width": 0.3,
      "duration_tick": 10,
      "pulsate": true
    }
  },
  "effects_data": {
    "num_particles_flak": 4,
    "impact_particle": "laser_block_impact"
  }
}
```

### 蓄力轨道激光（长距离狙击）

```json
{
  "type": "rvp:laser",
  "name": "Charged Rail Laser",
  "shoot_interval": 40,
  "max_capacity": 120,
  "reload": { "time": 80, "ammo": "ywzj_vehicle:ammo_creative" },
  "damage": 60,
  "recoil": 2.0,
  "fire_data": {
    "fire_mode": "RAILGUN",
    "charge_tick": 40,
    "charge_power_scale": 4.0,
    "max_off_axis_shoot_angle": 5
  },
  "laser_data": {
    "range": 1024,
    "visual_data": {
      "color": [80, 160, 255, 255],
      "width": 0.25,
      "duration_tick": 30,
      "pulsate": false,
      "segment_length": 2.0
    }
  }
}
```

### 指示吊舱对照（laser_data.range 的另一用途）

现役 `suav_targeting_pod_block.json` 中 `laser_data.range: 600` 被目标指示吊舱用作方块标记射线长度：

```json
{
  "type": "rvp:targetingpod",
  "name": "SUAV 坐标指示吊舱",
  "targeting_pod_data": {
    "mode": "block", "block_range": 600, "write_gps_target": true
  },
  "laser_data": { "range": 600 }
}
```

## 注意事项

1. **现网 `limitless_vehicle` 武器包内尚无 `rvp:laser` 实例**——本文示例均按当前代码行为构造，首次实装建议从小伤害+短射程开始验证；
2. 目标指示吊舱的注册 id 是 `rvp:targetingpod`（不是 `rvp:targeting_pod`），与吊舱文档示例中写法一致；
3. `fire_data.charge_tick` 默认 10 的"光束变暗"陷阱见"开火模式与蓄力"节；
4. 激光武器的 `guidance_data` 若误配置为 LH/SALH 等制导类型，会把该武器卷入激光告警/照射会话体系（`isLaserGuidanceType` 判定），与"激光武器不触发 LWR"的默认行为相悖——保持不配置即可。
