注：表格中的字段名称为java类中使用的驼峰命名法，在json中需使用全小写+下划线连接的格式



## 该系统只对固定翼战机和螺旋桨生效！！！！



# RVP_CountermeasureData（干扰物重构数据模型）

干扰物拆分为两类，作为**载具状态（Vehicle State）**实现（对齐 RVP APS），不再是武器类干扰物：

- **热焰弹（FLARE）**：干扰 IR / AIR 制导导弹。
- **箔条（CHAFF）**：干扰 SARH / ARH 制导导弹。

载具 JSON 顶层 `countermeasure` 块下配置两套独立子系统（互不共享弹药、各自独立状态机）。
发射位置借用本体发射装置（`WeaponUnit.worldCurrentBoltPosition` 出膛点 + `ServerVehicleFire` 发射动画），
不硬编码任何武器 ID。

武器侧干扰检测模型见 `docs/plan/RVP武器数据模型/RVP 火炮导弹火箭武器数据模型文档.md` 的
`RVP_InterferenceData`（计划中）；本模型为其**载具侧对应数据**。

```json
"countermeasure": {
  "flare": {
    "launcher_parts": ["cm_flare_left", "cm_flare_right"],
    "total": 32,
    "per_round": 4,
    "burst_rounds": 8,
    "launch_interval_tick": 4,
    "reload_tick": 200,
    "decoy": {
      "lifetime_tick": 160,
      "speed": 1.0,
      "gravity": 0.05,
      "drag": 0.02,
      "spread": 0.6,
      "glow_color": 16711680,
      "halo_scale": 1.6
    }
  },
  "chaff": {
    "launcher_parts": ["cm_chaff_dispenser"],
    "total": 32,
    "per_round": 4,
    "burst_rounds": 8,
    "launch_interval_tick": 4,
    "reload_tick": 200,
    "radar_jam_radius": 8,
    "radar_jam_count": 3,
    "radar_jam_cooldown_tick": 60,
    "decoy": {
      "lifetime_tick": 100,
      "speed": 0.2,
      "gravity": 0.0,
      "drag": 0.3,
      "spread": 2.5,
      "glow_color": 16777215,
      "halo_scale": 0.7
    }
  }
}
```

## RVP_CountermeasureData（载具侧总表）

| RVP_CountermeasureData字段 | 解释 | 类型 | 默认值 |
| :------------------------- | ---- | ---- | ------ |
| flare | 热焰弹子系统配置，null 或 `total=0` 时禁用 | RVP_CountermeasureSystemData | null |
| chaff | 箔条子系统配置，null 或 `total=0` 时禁用 | RVP_CountermeasureSystemData | null |

## RVP_CountermeasureSystemData（单套干扰物子系统）

| RVP_CountermeasureSystemData字段 | 解释 | 类型 | 默认值 |
| :------------------------------- | ---- | ---- | ------ |
| type | 干扰物类型，FLARE/CHAFF，大小写不敏感；缺省由所属键决定（`flare`/`chaff`） | RVP_EnumCountermeasureType | null |
| launcherParts | 发射装置部件 id 列表（本体 WeaponUnit 部件），仅作为出膛点与发射动画锚点；至少一个 | List<String> | null |
| total | 干扰物总数（弹舱容量），0 = 禁用该系统 | int | 32 |
| perRound | 一轮发射数 m | int | 4 |
| burstRounds | 总发射轮数 n（一次按键最多发射的轮数） | int | 8 |
| launchIntervalTick | 轮间发射间隔（tick） | int | 4 |
| reloadTick | 装填时间（tick），从 0 装填到 `total` | int | 200 |
| decoy | 干扰物实体属性 | RVP_CountermeasureDecoyData | null |
| radarJamRadius | 箔条对雷达锁定的干扰判定半径（格）：统计被锁定目标周围该范围内箔条数，**仅对 CHAFF 生效** | float | 25 |
| radarJamCount | 被锁定目标周围箔条数 ≥ 该值时雷达脱锁，**仅对 CHAFF 生效** | int | 16 |
| radarJamCooldownTick | 脱锁后目标短时间内不能被雷达选中/锁定（但仍可被扫描）的时长（tick），**仅对 CHAFF 生效** | int | 60 |

## RVP_CountermeasureDecoyData（干扰物实体属性）

| RVP_CountermeasureDecoyData字段 | 解释 | 类型 | 默认值 |
| :------------------------------ | ---- | ---- | ------ |
| lifetimeTick | 单发干扰物存活 tick（有效干扰窗口） | int | 160 |
| speed | 出膛初速（m/tick，沿发射装置瞄准方向，叠加载具速度） | float | 0.5 |
| gravity | 下落加速度（热焰弹建议 0.05；箔条建议 0 悬浮） | float | 0.05 |
| drag | 空气阻力系数，速度按 `velocity *= (1 - drag)` 衰减（热焰弹建议 0.02，箔条建议 0.3） | float | 0.05 |
| spread | 发射散布半径（格，发射时随机偏移；箔条建议较大以形成云团） | float | 1.0 |
| glowColor | 发光颜色（0xRRGGBB），作用于 billboard 贴图的颜色倍乘 | int | 热焰弹 0xFF0000（红），箔条 0xFFFFFF（白） |
| haloScale | 光晕/billboard 尺寸倍率（光圈大小），作用于 billboard 缩放 | float | 热焰弹 1.6（大光圈），箔条 0.7（小光圈） |

## 干扰物外观（视觉设计）

参考本体 `DecoyFlareEntityRenderer` 的 billboard 样式（半透明贴图 `decoy_flare.png`、自发光
`FULL_BRIGHT`、脉动缩放 + 粒子 + 拖尾音，见 `docs/RVP_干扰物外观调研.md` 或本体源码）：

| 干扰物 | 发光 | 光圈/尺寸 | 运动 | 附加粒子 |
| --- | --- | --- | --- | --- |
| 热焰弹 FLARE | **红色发光**（`glowColor` 红） | **光圈更大**（`haloScale` 1.6） | 缓缓下落、飘散（`gravity` 0.05、`drag` 0.02、`speed` 1.0） | `ParticleTypes.FLASH` 爆闪 + 后段 `CAMPFIRE_COSY_SMOKE` 浓烟 + 拖尾音 |
| 箔条 CHAFF | **白色发光**（`glowColor` 白） | **光圈更小**（`haloScale` 0.7） | 低速铺开、悬浮（`gravity` 0、`drag` 0.3、`speed` 0.2） | 灰色 `SmokeCloudOption` 细粒子按 `spread` 散布成云，不带 FLASH |

实现要点（对齐本体）：
- billboard 用 `RenderType.entityTranslucent` 渲染贴图，`glowColor` 作颜色倍乘，`haloScale` 作 billboard 缩放（可叠加脉动 `sin`）。
- 热焰弹 `FULL_BRIGHT` 自发光；箔条可降低自发光或加轻微灰度以区分。
- 粒子/声音仅在客户端 tick 生成（服务端只做实体运动与存活），对齐本体 `DecoyFlareEntity.tickParticle/tickSound`。

## 发射逻辑（一次按键 = 一次齐射）

状态字段：`remaining`（剩余）、`firing`（齐射中）、`roundsLeft`（剩余轮数）、`roundTimer`（轮间隔计时）、
`reloading`（装填中）、`reloadProgress`（装填进度）。

1. 正常（剩余充足）：发射 **`n` 轮 × 每轮 `m` 发 = `n*m`**，轮间间隔 `launchIntervalTick`；
   剩余 = 原剩余 − `n*m`。
2. 剩余不足（`R < n*m`）：发射 **`ceil(R/m)` 轮** 耗尽全部剩余；**最后一轮**数量不足 `m` 时
   只发射剩余数量，随后 `R = 0` 进入装填。
3. 剩余为 0 / 装填中 / 齐射中：按键忽略。
4. 任意一次发射结束后剩余为 0 → 开始装填，`reloadTick` 后恢复至 `total`。

```
onKeyPress:
  if firing || reloading || remaining <= 0: return
  firing = true
  roundsLeft = (remaining >= n*m) ? n : ceil(remaining / m)

onTick:
  if reloading:
    reloadProgress++
    if reloadProgress >= reloadTick: remaining = total; reloading = false
    return
  if !firing: return
  roundTimer--
  if roundTimer > 0: return
  fireOneRound(min(m, remaining))                 // 从各活跃发射装置发射
  remaining -= min(m, remaining)
  roundsLeft--
  if roundsLeft <= 0:
    firing = false
    if remaining <= 0: reloading = true
  else:
    roundTimer = launchIntervalTick
```

边界情况：

| 情况 | 行为 |
| --- | --- |
| `perRound <= 0` 或 `burstRounds <= 0` | 该系统禁用 |
| `R >= n*m` | 恰好发射 n 轮整 |
| `R == n*m` | 发射完剩余为 0 → 进入装填 |
| `0 < R < n*m` | `ceil(R/m)` 轮，末轮不足 m，耗尽后装填 |
| 齐射中 / 装填中再次按键 | 忽略（不排队） |
| 载具断电 / 损毁 | 禁止发射 |

## 干扰物类型与制导/雷达对应关系

| 干扰物 | 类型 | 制导武器侧干扰 | 雷达侧作用 |
| --- | --- | --- | --- |
| 热焰弹 Flare | `FLARE` | IR、AIR | 无（对雷达无效，且**不可被雷达扫描**） |
| 箔条 Chaff | `CHAFF` | SARH、ARH | 超阈值**脱锁 + 短暂禁锁**（可被扫描）；本身**可被雷达扫描** |

制导武器侧对齐 `RVP_InterferenceData`：跟踪期间以弹体指向为轴、`maxLockAngle * seekerFovShrinkFactor` 为 FOV、
`guidanceTargetDistanceRange` 为距离检测对应类型干扰物；视场内干扰物数量超过 `seekerJamLimit` 时
脱锁并飞向最近的对应类型干扰物；脱锁后按 `seekerShutOffTime` 关闭/重启导引头。

> RVP 弹体侧检测必须**按类型过滤**（IR/AIR 只查 `FLARE`，SARH/ARH 只查 `CHAFF`），
> 不能用本体无类型的 `TargetObstruction` 一概判定。

## 雷达/导引头箔条抗性与锁定行为（补充）

| 行为 | 说明 |
| --- | --- |
| 雷达箔条抗性 | 载具雷达部件 JSON 新增 `chaff_resistance`（默认 `0`）：**箔条可作为雷达锁定目标**（不排除），但在手动/自动锁定候选评分中按抗性施加优先级惩罚（越大越难被选中，非完全不可锁） |
| 导弹箔条抗性 | 武器 `guidance_data.interference_data.chaff_resistance`（默认 `0`）：**ARH / AIR 开启导引头后可锁箔条**，扫描评分按抗性对箔条施加优先级惩罚（越大越难被选为锁定目标，非完全不可锁） |
| IR 可锁热焰弹 | **红外弹开启导引头阶段把热焰弹当作锁定目标**（扫描候选含 FLARE 实体，无抗性） |
| IR 关机后复锁 | IR 弹被干扰失锁、导引头关闭期结束后，**可像 AIR 一样主动扫描索敌复锁** |

- 雷达部件 JSON 写法：`"scan_animation_mode": "phase", "chaff_resistance": 0.5`（0~1，0=无惩罚）。
- 武器 JSON 写法：`"guidance_data": { ..., "interference_data": { "chaff_resistance": 0.5 } }`。
- 抗性只降低**优先级**，不禁止锁定：作为评分罚分（`score + chaff_resistance * 罚分基准`），候选排序靠后但若分数最优仍可锁定。

## 雷达侧干扰联动（箔条 vs 雷达）

雷达只受**箔条（CHAFF）**影响；热焰弹对雷达无效且不可被雷达扫描。设计从简，**不做**探测遮蔽、
连线遮挡、假目标/杂波、抗箔条系数，只做"**锁定目标周围箔条计数 → 超阈值脱锁 → 短暂禁锁**"。
phase 相控阵雷达与机械雷达共用同一锁定模型（`RadarUnit` / `RVP_RadarRoleHelper`），无需按雷达类型区分。

### 5.1 规则

| 规则 | 说明 |
| --- | --- |
| 箔条计数 | 对雷达**当前锁定目标**，统计其周围 `radarJamRadius` 格内 `CHAFF` 实体数量（仅统计，不影响探测表） |
| 超阈值脱锁 | 箔条数 ≥ `radarJamCount` 时雷达脱锁：`radarUnit.setLockedEntity(null)`，同步清 `weaponUnit` / `RVP_WeaponLockStateTable` 锁定与 pending 锁定 |
| 短暂禁锁 | 脱锁目标进入 `radarJamCooldownTick` 禁锁期：期间**可被扫描**（雷达界面仍显示），但**不能被选中 / 锁定**（手动、火控、pending、gunner 锁定均拒绝） |
| 恢复 | 禁锁期结束即可重新被锁定；若目标仍在箔条范围内可再次触发脱锁 |

### 5.2 判定时机与接线

- 判定在**锁定侧**：对每台有锁定目标的雷达（客户端玩家雷达、服务端炮手雷达、外置中继雷达）按扫描节流统计被锁定目标周围箔条数。
- 禁锁期挂在**目标侧**（目标实体 UUID → 禁锁截止 tick），存于侧表（`RVP_WeaponLockStateTable` 扩展或独立 `RVP_ChaffJamStateTable`）。
- 锁定拒绝点：`RVP_RadarRoleHelper`（`applyRequestedLock` / `tickPendingRadarLock` / `collectManualLockCandidates`）与 gunner 锁定——目标处于禁锁期时返回不可锁。

### 5.3 干扰物实体行为

| 行为 | 说明 |
| --- | --- |
| 不触发近炸 | 干扰物实体不触发 `RVP_BaseBullet` 近炸引信 |
| 不与弹药碰撞 | 导弹/机炮等命中判定忽略干扰物实体（不产生命中、不销毁干扰物） |
| 雷达可扫描性 | `CHAFF` 实体由 `RVP_RadarScanHelper` 补入雷达探测表（可被看到、不产生锁定）；`FLARE` 实体不补入 |
| 存活与运动 | 按 `lifetimeTick` 存活；`speed` 出膛、`drag` 减速、`gravity` 下落、`spread` 散布 |

### 5.4 推荐参数

| 参数 | 位置 | 推荐值 | 说明 |
| --- | --- | --- | --- |
| `radarJamRadius` | chaff 子系统 | 8 | 统计被锁定目标周围箔条的半径（格） |
| `radarJamCount` | chaff 子系统 | 3 | 超过该数量雷达脱锁 |
| `radarJamCooldownTick` | chaff 子系统 | 60 | 脱锁后 3 秒不可被锁定（仍可被扫描） |
| `decoy.drag` | decoy 子对象 | 热焰弹 0.02，箔条 0.3 | 空气阻力系数 |

## 载具侧推荐参数

系统级参数（`RVP_CountermeasureSystemData`）：

| 参数 | 热焰弹 Flare | 箔条 Chaff | 说明 |
| --- | --- | --- | --- |
| `total` | 32 | 32 | 仅固定翼和直升机可用 |
| `perRound` | 4 | 4 | 每轮发射数 |
| `burstRounds` | 8 | 8 | 一次按键轮数 |
| `launchIntervalTick` | 4 | 4 | 0.2s/轮，快速铺开干扰云 |
| `reloadTick` | 200 | 200 | 推荐10秒装满            |
| `radarJamRadius` | — | 8 | 仅 CHAFF：统计被锁定目标周围箔条的半径（格） |
| `radarJamCount` | — | 3 | 仅 CHAFF：超过该数量雷达脱锁 |
| `radarJamCooldownTick` | — | 60 | 仅 CHAFF：脱锁后禁锁时长（tick） |

`decoy` 子对象内参数（`RVP_CountermeasureDecoyData`，JSON 中为嵌套对象）：

| 参数 | 热焰弹 Flare | 箔条 Chaff | 说明 |
| --- | --- | --- | --- |
| `lifetimeTick` | 160 | 100 | 8s / 5s 有效干扰窗口 |
| `speed` | 1.0 | 0.2 | 热焰弹向后抛射，箔条低速铺开 |
| `gravity` | 0.05 | 0.0 | 热焰弹下落，箔条悬浮 |
| `drag` | 0.02 | 0.3 | 空气阻力系数，速度按 `(1 - drag)` 衰减 |
| `spread` | 0.6 | 2.5 | 箔条散布成云，更容易填满导引头视场 |
| `glowColor` | `0xFF0000`（红） | `0xFFFFFF`（白） | 热焰弹红光、箔条白光 |
| `haloScale` | 1.6 | 0.7 | 热焰弹大光圈、箔条小光圈 |

JSON 写法示例（`decoy` 是嵌套对象，不是带点的扁平键；雷达联动字段在 chaff 子系统同级）：

```json
"chaff": {
  "launcher_parts": ["cm_chaff_dispenser"],
  "total": 32,
  "per_round": 4,
  "burst_rounds": 8,
  "launch_interval_tick": 4,
  "reload_tick": 200,
  "radar_jam_radius": 8,
  "radar_jam_count": 3,
  "radar_jam_cooldown_tick": 60,
  "decoy": {
    "lifetime_tick": 100,
    "speed": 0.2,
    "gravity": 0.0,
    "drag": 0.3,
    "spread": 2.5,
    "glow_color": 16777215,
    "halo_scale": 0.7
  }
}
```

调参建议：`burstRounds` 增大 / `launchIntervalTick` 减小 → 视场内干扰物快速超过 `seekerJamLimit`
更易脱锁，代价是消耗快、更早装填；单套 `perRound * burstRounds` 建议 ≥ 武器侧 `seekerJamLimit`（默认 8）。

## 待确认项

1. **末轮"不足 n"表述**：需求原文"最后一轮发射数量不足 n"，按上下文推断应为"不足 **m**（一轮发射数）"，本模型按 m 实现，需确认。
2. **装填语义**：推荐"从 0 一次性装填到 `total`"；也可改为"每 `reloadTick` 补 `perRound` 发"（逐轮补给，对齐 APS `reload_one_tick`）。
3. **一次按键是否同时发射热焰弹 + 箔条**：推荐同时齐射（各自独立状态机）；也可循环切换（FLARE→CHAFF→OFF）。
4. **配置位置**：推荐载具 JSON 顶层 `countermeasure`；是否也支持 `bone_modules` 骨块挂载（失效联动）需确认。
5. **干扰物实体形态**：真实实体（可被导引头扫描） vs 假目标记录（现有 `RVP_DecoyTarget`），需权衡。



## 待确认项回复

1，按m实现
2，从0一次装满
3，箔条和热焰弹抛洒分成不同的按键
4，配置为顶层，同时支持bone_modules，对应的bone_modules全部被击毁则失去抛洒干扰物功能
5，采用真实实体，`lifetimeTick`后消失。特别补充：干扰物实体不会触发近炸，也不会和弹药碰撞。然后载具雷达只能扫描到箔条实体，无法扫描到热焰弹实体。干扰物还应该加个阻力参数



## 待确认项回复（雷达侧）

统一回复，雷达侧按**简化模型**实现（§5 已按此改写）：

- 雷达只受**箔条**影响；统计**当前锁定目标**周围 `radarJamRadius` 格内的箔条实体数。
- 箔条数 ≥ `radarJamCount` → 雷达脱锁，目标进入 `radarJamCooldownTick` 禁锁期。
- 禁锁期内目标**可被扫描**（雷达界面仍显示），但**不能被选中 / 锁定**；禁锁期结束恢复。
- 不做探测遮蔽、连线遮挡、假目标/杂波、抗箔条系数。



## 改动点映射（规划）

| 层 | 文件 | 改动 |
| --- | --- | --- |
| 雷达判定 | 新建 `radar/RVP_RadarChaffJamRuntime.java`（或并入 `RVP_RadarRoleHelper`） | 对每台有锁定目标的雷达按节流统计锁定目标周围箔条数；超阈值脱锁 + 设置目标禁锁期 |
| 禁锁状态 | `RVP_WeaponLockStateTable` 扩展或独立 `RVP_ChaffJamStateTable` | 目标 UUID → 禁锁截止 tick |
| 锁定拒绝 | `radar/RVP_RadarRoleHelper.java` | `applyRequestedLock` / `tickPendingRadarLock` / `collectManualLockCandidates` 跳过禁锁期目标 |
| 雷达扫描 | `radar/RVP_RadarScanHelper.java` + `RVP_RadarScanService.java` / `RVP_ClientRadarTickHandler.java` / `RVP_ExternalRadarSyncService.java` | 箔条实体补入探测表（可扫描）；热焰弹实体不补入 |
| 近炸 / 碰撞 | `entity/projectile/RVP_BaseBullet.java`（近炸引信 / 命中判定） | 忽略干扰物实体（不触发近炸、不产生碰撞命中） |
| 干扰物实体 | 新建 `entity/decoy/RVP_FlareDecoy` / `RVP_ChaffDecoy` + `RVP_Decoy` 接口 | 类型化干扰物；`lifetimeTick` 存活、`drag` 减速、`gravity` 下落、`spread` 散布 |

