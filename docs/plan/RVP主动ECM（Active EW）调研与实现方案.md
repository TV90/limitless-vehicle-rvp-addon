# RVP 主动ECM（Active EW）调研与实现方案

> 状态：**调研与设计，未实施**
> 日期：2026-08-26
> 依据：RVP 现有 干扰物（箔条/热焰弹）、被动ECM（ECM_PASSIVE）、DIRCM、导弹制导体系、RWR 告警链路、雷达锁定 的代码调研
> 关联文档：`RVP被动电子战防御措施方案_20260825.md`（明确"主动电子战后续独立方案"）、`RVP_DIRCM定向红外对抗方案_20260823.md`、`RVP干扰物重构数据模型/`

---

## 一、需求拆解

| # | 需求 | 说明 |
| --- | --- | --- |
| R1 | **主动释放、状态级** | 与箔条/热焰弹同类：按键触发 → 进入持续状态 → 冷却 |
| R2 | **按键=箔条键** | 复用 `FIRE_CHAFF`（LEFT_ALT）触发链路，同键触发 |
| R3 | **音效 ecm_jammer.ogg** | 资源已存在（`assets/ywzj_rvp/sounds/misc/ecm_jammer.ogg`），未注册 |
| R4 | **假目标 ×6** | 立即生成 6 个与被动ECM相同机制的假目标（`RVP_EcmDecoyEntity`），生命周期可配 |
| R5 | **导弹干扰（范围内）** | ARH / SARH / radio 两种 HITL / GPS 导弹与炸弹 / 中继期 AIR 与 ARH |
| R6 | **载具干扰（范围内）** | 敌对载具雷达脱锁 + RWR 伪造 5-10 锁定源（radartype 可配、默认池 10 种、源不可全同） |
| R7 | **范围分隔** | 弹药干扰半径 与 载具干扰半径 独立配置 |
| R8 | **ARM 吸引与降准**（批示新增） | 释放后 5 秒内成为反辐射导弹视场最高优先级目标（**优先级高于任何预选**），但 ECM 干扰使 ARM **每次记忆落点随机 ±7m 偏移**——"易被锁定但打不准" |

**导弹干扰细则（R5 展开）**：

| 导弹类型 | 干扰效果 | 细节 |
| --- | --- | --- |
| ARH（中继期，导引头未开机） | 阻断雷达中继制导，**无法重新建立跟踪** | 中继数据丢失后不得靠自身雷达扫描重获 → 按"丢锁 **200 tick** 自毁"逻辑坠落（批示：60→200） |
| AIR（中继期，导引头未开机） | 阻断雷达中继制导，**可惯性飞行并启动自身 AIR 导引头** | 中继被断后 coast + 开启自身导引头搜索，搜到可重新跟踪 |
| SARH | 阻断照射源制导 | 失去照射即脱锁/失效 |
| HITL_TV / HITL_CLOS_TV（`hitl_signal_source=RADIO`） | 切断 radio 链路 | 复用现有 `hitlLinkBlocked/hitlLinkSevered` 状态机 |
| GPS 导弹 / 炸弹 | GPS 落点随机偏移 | 半径 ±20m（可配），**一次性偏移**（批示确认） |
| ARM（反辐射，R8） | 见 §7.5 | 被吸引为最高优先级 + 记忆落点 ±7m 抖动 |

> **共通约束（批示补充）**：ECM **不能干扰自身或同阵营发射的导弹**。判定：`shooterVehicle == ecmVehicle` 或 `RVP_EcmIff.areVehiclesFriendly(ecmVehicle, shooterVehicle)` 时跳过（§七 干扰循环前置过滤）。

**载具干扰细则（R6 展开）**：
- 阵营判定 = GunnerBrain/CIWS 语义（`RVP_EcmIff.areVehiclesFriendly`、`GunnerTargeting.isFriendlyAmmoOwner`、`GunnerBrain.isHostileTo` 同源）
- 同阵营不干扰
- **雷达脱锁范围（批示明确）**：干扰范围内敌方**所有**载具的雷达锁定，**无论它锁的是谁**（锁 ECM 载具也好、锁别的目标也好）一律强制脱锁
- RWR 伪造锁定：向目标客户端 `WarningReceiver.targets` 注入 5-10 个 `RADAR_LOCK` 告警源，radartype 从配置池随机、每源独立随机且不可全同，持续可配时长，自动触发本体"锁定循环告警音"

---

## 二、现状调研摘要（关键机制与复用点）

### 2.1 状态级干扰物（R2/R3 参考）
| 项 | 位置 | 要点 |
| --- | --- | --- |
| 按键注册 | `client/RVP_Keys.java:61` | `FIRE_CHAFF = key("fire_chaff", KEYSYM, GLFW_KEY_LEFT_ALT)` |
| 按键消费 | `client/RVP_ClientEvents.java:129` | `while(FIRE_CHAFF.consumeClick()) ywzj_rvp$fireCountermeasure(CHAFF)` |
| C2S 触发 | `countermeasure/network/C2SFireCountermeasure.java:41` | 校验玩家/载具 → `RVP_CountermeasureRuntimeManager.onFire` |
| 服务端状态机 | `countermeasure/server/RVP_CountermeasureRuntimeManager.java` | 按载具 UUID 存三套状态机（remaining/firing/reloadProgress），`onTick()` 逐 tick 推进 |
| 音效注册 | `all/RVP_Sounds.java:25-27` + `assets/ywzj_rvp/sounds.json` | `register("countermeasure_flare"/"countermeasure_chaff")`，sound 指向 `ywzj_rvp:misc/*.ogg` |
| 音效播放 | `RVP_CountermeasureRuntimeManager.spawnRound:244-257` | `vehicle.level().playSound(null, x,y,z, sound, SoundSource.NEUTRAL, 1.0F, 1.0F)` 服务端广播 |
| 骨块失效 | `vehicle/BoneModuleType.java:25-32` + `RVP_BoneModuleStateTable.isModuleActive` | 已有 `ECM_PASSIVE/DIRCM/COUNTERMEASURE/JAMMER` 等枚举，**主动ECM需新增 `ECM_ACTIVE`** |

### 2.2 被动ECM 假目标（R4 参考）
| 项 | 位置 | 要点 |
| --- | --- | --- |
| 假目标实体 | `entity/ecm/RVP_EcmDecoyEntity.java` | 隐形雷达幻影（`shouldRender=false`），`ownerVehicleId`+`nctrName`+漂移+寿命销毁 |
| 生成逻辑 | `ecm/RVP_EcmPassiveManager.java:232-285` `spawnDecoys/spawnOneDecoy` | 数量=距离档位、随机半径、只落已加载区块；寿命=`max(activeDurationTicks, cooldownTicks)` |
| 去重/登记 | `RVP_EcmPassiveManager.DECOY_IDS` | 载具id→假目标id列表，`tryDivertSeeker` 据此做 ARH/SARH 导引头偏转 |
| 敌我 | `ecm/RVP_EcmIff.java` | `areVehiclesFriendly/isHostileIllumination/isDecoyHostileTo`，含 gunner faction + 放置者链 + Team |
| 配置 | `vehicle/BoneEcmPassiveConfig.java` | `record` + `static parse(JsonElement)`，`ecm_passive` 子对象；含 `bands[]`（距离档→数量/半径） |

> 主动ECM 假目标：**复用 `RVP_EcmDecoyEntity` 实体与生成方式**；建议把 `DECOY_IDS` 登记与 `spawnOneDecoy` 抽成共享（被动/主动都往同一载具假目标注册表登记，使 `tryDivertSeeker` 同时吃到主动假目标——与"和被动ECM一样的假目标"一致）。

### 2.3 导弹制导体系（R5 核心）
| 项 | 位置 | 要点 |
| --- | --- | --- |
| 制导类型枚举 | `guidance/RVP_EnumGuidanceType.java:6-24` | `NONE,IOG,MCLOS,SALH,SACLOS,LBR,LH,TV,HITL_TV,HITL_CLOS_TV,ATV,GPS,IR,AIR,ARH,SARH,ARM` |
| 阶段模型 | `guidance/RVP_GuidancePhase.java` | 仅 `MAIN/TERMINAL` 两阶段（旧 `stages[]` 文档已过时） |
| 中继期判定 | `entity/projectile/RVP_MissileEntity.java:240-286` `tickActiveSeekerTargetManagement` | `isAutonomousSeekerOn()==false`（`activeRadarOn`）= 中继期；`距目标≤activeRadarActivationRange 或 !hasDesignation → setAutonomousSeekerOn(true)` |
| 中继数据来源 | `RVP_MissileEntity.java:478-518` `rvp$hasActiveSeekerSupportForDesignatedTarget` | 载具根雷达锁定/外置中继雷达锁定，**非 S2C 包** |
| ARH 执行 | `guidance/runtime/RVP_RuntimeActiveSeekerGuidance.java:14-92` | 中继期用 `rvp$getActiveSeekerDesignatedTargetEntity()`；开机后用扫描（ARH 扫描雷达目标 / AIR 扫描 IR） |
| SARH 执行 | `guidance/runtime/RVP_RuntimeSarhGuidanceSource.java:17-31` | 靠 `RVP_RadarRoleHelper.getEffectiveRfLockedEntity()` 照射源，无照射即 clearTarget |
| HITL radio 链路 | `RVP_MissileEntity.java:569-618` `tickHitlRadioLink` | 仅 `hitl_signal_source==RADIO` 生效；视距/遮挡→`hitlLinkBlocked`，累计 40 tick→`hitlLinkSevered`+`hitlEnabled=false`+`clearTarget` |
| GPS 落点 | `guidance/runtime/RVP_RuntimeGpsGuidanceSource.java:18-28` + `entity/projectile/RVP_BaseBullet.java:1243-1289` | 已有 `gpsSpreadRadius` 高斯散布（`gps_spread_radius`，默认 0，仅 BOMB 生效）；`applyGpsTargetDispersion`/`ensureGpsTargetOffset` |
| 干扰影响点 | `RVP_BaseBullet.java:377-431` | 已有 `jammingExpireTick`、`dircmJammed/dircmJamRemainTick`、光电干扰机 `jamming*` 字段（`jammingStrength/jammingOffsetAngleDeg/...`） |
| 被动欺骗接入 | `guidance/runtime/RVP_RuntimeSeekerSupport.java:66-73` | `validateEntity` 调 `RVP_EcmPassiveManager.tryDivertSeeker()`（仅 ARH/SARH） |
| DIRCM 干扰范式 | `dircm/RVP_DircmRuntimeManager.java:372-421,503-537` | 置 `dircmJammed`+倒计时字段 → 制导丢弃+强制偏转；`tickJammedProjectile` 逐 tick 递减，归零恢复 |

> **关键结论**：中继期 = `isAutonomousSeekerOn()==false`。阻断中继只需让 `rvp$hasActiveSeekerSupportForDesignatedTarget()` 在干扰期间返回 false。ARH 与 AIR 的差异在"开机后能否重新扫描获取"：
> - AIR（IR）：`RVP_RuntimeActiveSeekerGuidance` 的 AIR 分支走 IR 扫描，**天然可在 coast 后重新捕获** → 满足需求；
> - ARH（雷达）：开机后走雷达扫描 → 需要**主动禁止重扫**（加 `ecmActiveNoReacquire` 标志在 ARH 扫描分支短路）。全部在 RVP 类内加字段+判断，**零 Mixin**。

### 2.4 RWR 与雷达锁定（R6 核心）
| 项 | 位置 | 要点 |
| --- | --- | --- |
| RWR 锁定源表 | 本体 `org.ywzj.vehicle.vehicle.passenger.WarningReceiver.targets`（`ConcurrentHashMap<Integer, WarnTarget>`） | `WarnTarget(warnType, info, receivedTime)`；`info`=radartype 字符串 |
| 告警窗口 | `WarningReceiver.tick:66` | `receivedTime+500<now` 清条目（500ms 窗口）→ **伪造锁定需周期性补发维持** |
| 告警音自动触发 | `WarningReceiver.tick` | targets 含 `RADAR_LOCK`→自动启停循环 `RADAR_LOCK_WARN`（高频锁定告警 ✓） |
| radartype 来源 | 本体 `RadarUnit.getRadarType()`（JSON `radar_type`） | 逐字显示，无映射表 → 伪造只需伪造字符串 |
| 直接注入范式 | RVP `client/.../RVP_ClientWarnRelay.java:45-47` | 服务端 S2C → 客户端 **直接 `warningReceiver.targets.put(id, new WarnTarget(...))`**，绕过 ±45° 钳制 |
| 显示 | `client/gui/RVP_RadarOverlay.java:184-219` | 遍历 targets → 取源实体算方位画线+`info`；**源实体取不到则跳过** → 伪造源需有实体/坐标 |
| 雷达脱锁 API | `radar/RVP_RadarRoleHelper.java:160` `clearAllRadarLocks`；本体 `RadarUnit.setLockedEntity(null)` | 参考 `RVP_ChaffJamHelper.tryJamLock/breakLock`（`:105-153`，含清 WeaponUnit 锁定+清 pending+`RVP_ChaffJamState` 禁锁期） |

> **伪造锁定源显示问题**：`RVP_RadarOverlay` 用源实体 id 取位置画连线。伪造 5-10 个锁定源若要显示方位线，需有实体锚点 → 方案：服务端生成 5-10 个**短命隐形雷达源实体**（仿 `RVP_EcmDecoyEntity`，新 `RVP_EcmFakeLockEntity`，非碰撞、不参与 CIWS）分布在被干扰者周围，S2C 注入 `WarnTarget` 引用这些实体 id + 随机 radartype。

### 2.5 配置/按键/音效/状态机范式（参考）
- 载具级设备配置：`BoneEcmPassiveConfig/BoneDircmConfig/BoneJammerConfig`（`record` + `static parse`，`bone_modules.<bone>.<device>` 子对象）；解析链 `RVP_VehicleHitboxFactorManager.resolveEcmDevices()/resolveDircmDevices()/resolveJammerDevices()`；存活 `RVP_BoneModuleStateTable.isModuleActive(vehicleId, boneName, type)`
- 按键/消费/C2S：见 §2.1（`RVP_Keys` + `RVP_ClientEvents` + `RVP_Network` 注册）
- 音效：`RVP_Sounds` DeferredRegister + `sounds.json` + 服务端 `level.playSound` 广播
- 状态机+持久化：`RVP_DircmRuntimeManager.tick()`（通道态 tick 递减 + `S2C` 广播给乘客+TRACKING）+ `RVP_DircmStateSavedData`（跨世界持久化）
- 禁锁期：`RVP_ChaffJamState.setCooldown/isInCooldown`（按 UUID + gameTime）

---

## 三、总体架构

**服务端权威**：所有干扰判定、假目标生成、导弹干扰、雷达脱锁、RWR 伪造均在服务端；客户端只做 按键→C2S、播放/渲染、HUD。

```
玩家按 ECM 键（默认 LEFT_ALT，与箔条同键位独立键）
  └─ RVP_ClientEvents (FIRE_ECM.consumeClick) → C2SFireEcm(vehicleId)
      └─ RVP_EcmActiveManager.onFire(server)
          ├─ 校验骨块存活 + 冷却 → 播放 ecm_jammer 音效(服务端广播)
          ├─ 进入 active 状态(activeRemainTicks = 配置)
          ├─ 生成 6 个假目标(共享假目标注册表)
          ├─ 登记 ARM 高优先级窗口(arm_priority_ticks)
          ├─ 弹药干扰循环(tick)：范围内敌对导弹(ARH/SARH/HITL-radio/GPS/AIR·ARH 中继期) → 逐类施加
          │   └─ 排除自身/同阵营发射的导弹
          ├─ 载具干扰循环(tick)：范围内敌对载具(无论锁谁) → 雷达脱锁+禁锁
          └─ RWR 伪造(tick 周期补发)：向被干扰者客户端注入 5-10 个 RADAR_LOCK(伪 radartype，无实体)
状态结束 → 冷却 → S2C 同步 HUD
```

**新增/修改类清单**（全部 RVP 自有类，零 Mixin，不碰本体黑名单类）：

| 类别 | 类 | 说明 |
| --- | --- | --- |
| 配置 | `vehicle/BoneEcmActiveConfig.java`（新） | record + parse；含主动状态/假目标/双半径/GPS偏移/RWR伪造/ARM 全部可配项 |
| 枚举 | `vehicle/BoneModuleType.java`（改） | 新增 `ECM_ACTIVE` |
| 解析链 | `RVP_VehicleHitboxFactorManager`（改） | 新增 `resolveEcmActiveDevices()` |
| 按键 | `client/RVP_Keys.java`（改） | 新增独立 `FIRE_ECM` 键，默认键值与 `FIRE_CHAFF` 相同（LEFT_ALT） |
| 客户端 | `client/RVP_ClientEvents.java`（改） | `FIRE_ECM.consumeClick` 分支 → C2SFireEcm |
| 网络 | `network/C2SFireEcm.java`（新）、`RVP_Network`（改）、`S2CEcmActiveHudSync.java`（新）、`S2CEcmFakeLock.java`（新） | |
| 服务端状态 | `ecm/RVP_EcmActiveState.java`（新） | activeRemainTicks / cooldownRemainTicks / armPriorityRemainTicks |
| 服务端管理器 | `ecm/RVP_EcmActiveManager.java`（新） | 核心：onFire / tick 干扰循环 / 假目标 / RWR 伪造 / ARM 优先级登记 / HUD 同步 |
| 假目标共享 | `ecm/RVP_EcmPassiveManager.java`（改，最小） | 抽出 `spawnOneDecoy`/`DECOY_IDS` 供主动复用（或新 `RVP_EcmDecoyRegistry`） |
| 导弹干扰标记 | `entity/projectile/RVP_BaseBullet.java`（改） | 新增 `ecmActiveJamRemainTick`、`ecmActiveNoReacquire`、`ecmActiveMemoryJitterPending` 字段 |
| 导弹判定 | `entity/projectile/RVP_MissileEntity.java`（改） | 中继支持/radio 链路/ARH 重扫 三处短路 |
| 制导源 | `guidance/runtime/RVP_RuntimeActiveSeekerGuidance.java`（改）、`RVP_RuntimeSarhGuidanceSource.java`（改） | ARH 禁重扫、SARH 断照射 |
| ARM 制导源 | `guidance/runtime/RVP_RuntimeArmGuidanceSource.java`（改） | ECM 优先级覆盖 + 记忆落点 ±7m 抖动 + memory null 兜底 |
| ARM 扫描器 | `weapon/AntiRadiationSeekerHelper.java`（改） | 伪脉冲注入 + `radarIndex=-1` 哨兵放行 + `getDefaultMemoryTick` null 保护 |
| 音效 | `all/RVP_Sounds.java`（改）、`assets/ywzj_rvp/sounds.json`（改）、lang（改） | `ECM_JAMMER` 注册 + `ecm_jammer` 条目 |
| HUD | `client/gui/RVP_EcmHudOverlay.java`（改） | 增加主动ECM状态行 |
| 持久化 | `ecm/RVP_EcmActiveStateSavedData.java`（新） | **必须**（批示）：跨世界持久化冷却/剩余 |

---

## 四、数据模型（`BoneEcmActiveConfig`）

载具 JSON 顶层 `bone_modules` 内新增（仿 `ecm_passive`）：

```json
"bone_modules": {
  "ecm_jammer": {
    "modules": ["ecm_active"],
    "ecm_active": {
      "active_duration_ticks": 200,
      "cooldown_ticks": 600,
      "decoy_count": 6,
      "decoy_lifetime_ticks": 200,
      "ammo_jam_radius": 300,
      "vehicle_jam_radius": 400,
      "gps_offset_meters": 20,
      "fake_lock_min": 5,
      "fake_lock_max": 10,
      "fake_lock_duration_ticks": 120,
      "fake_lock_sources": ["S400","J16","F18","J20","SLM","F15","S57","S35","F22","ITO"],
      "radar_unlock": true,
      "arm_priority_ticks": 100,
      "arm_memory_jitter_meters": 7
    }
  }
}
```

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `active_duration_ticks` | 200 | 主动ECM释放后持续 tick（状态级），此间持续干扰 |
| `cooldown_ticks` | 600 | 冷却 tick |
| `decoy_count` | 6 | 释放立即生成的假目标数 |
| `decoy_lifetime_ticks` | 200 | 假目标存活 tick（独立于被动ECM的 `max(active,cooldown)`） |
| `ammo_jam_radius` | 300 | **弹药干扰半径**（与载具半径分离 R7） |
| `vehicle_jam_radius` | 400 | **载具干扰半径** |
| `gps_offset_meters` | 20 | GPS 导弹/炸弹落点随机偏移半径（±米），一次性（批示确认） |
| `fake_lock_min/max` | 5/10 | RWR 伪造锁定源数量区间 |
| `fake_lock_duration_ticks` | 120 | 伪造锁定持续 tick（期间周期性补发，因 RWR 条目 500ms 过期） |
| `fake_lock_sources` | 默认池 | radartype 文案池；每源独立随机且**不可全同**（count>1 时保证至少两种）；**可配置，不配则用默认池**（批示确认，同被动 NCTR 模式） |
| `radar_unlock` | true | 是否对被干扰载具执行雷达脱锁 |
| `arm_priority_ticks` | 100 | ARM 高优先级窗口（5 秒=100 tick） |
| `arm_memory_jitter_meters` | 7 | ARM 记忆落点随机抖动半径（±7m） |

> **decoy_radius（假目标散布范围）**：批示"硬编码即可"——不设配置字段，固定取合理值（如车辆半径×随机系数，参考被动 `bands[].radius` 量级）。
> **假目标 NCTR**：批示确认——同被动ECM，**有默认池，也可在主动配置里单独覆盖**（若未配置则回落到 `BoneEcmPassiveConfig.nctrNames` 或内置默认）。

**解析**：`BoneEcmActiveConfig.parse(JsonElement)`（GsonHelper，带钳制）；`BoneModuleType` 增 `ECM_ACTIVE`；`resolveEcmActiveDevices()` 返回 `Map<骨块名, BoneEcmActiveConfig>`；存活走 `RVP_BoneModuleStateTable.isModuleActive`。

---

## 五、触发链路与音效

1. 新增独立 `RVP_Keys.FIRE_ECM` 键（批示确认），**默认键值与 `FIRE_CHAFF` 相同（LEFT_ALT）**：`RVP_ClientEvents.onClientTick` 加 `while(RVP_Keys.FIRE_ECM.consumeClick()) ywzj_rvp$fireEcm();`；若载具存在**存活**的 ECM_ACTIVE 骨块 → `sendToServer(new C2SFireEcm(vehicleId))`。
   - 与箔条互不干扰：同键时两键都按下/都消费，各自走各自 C2S 包。
2. `C2SFireEcm.handle`：服务端校验玩家/载具/骨块存活/冷却 → `RVP_EcmActiveManager.onFire(player, vehicle)`。
3. `onFire`：置 `activeRemainTicks=active_duration_ticks`、`armPriorityRemainTicks=arm_priority_ticks`、`cooldownRemainTicks=cooldown_ticks`（或释放结束才进冷却），播 `level().playSound(null, ..., ECM_JAMMER, SoundSource.NEUTRAL, 1.0F, 1.0F)`，生成 6 假目标。
4. 音效注册：`RVP_Sounds` 加 `ECM_JAMMER = register("ecm_jammer")`；`sounds.json` 加 `"ecm_jammer": {"subtitle":"subtitles.ywzj_rvp.ecm_jammer","sounds":["ywzj_rvp:misc/ecm_jammer"]}`（`.ogg` 已存在）；lang 加 subtitle 双语文案。

---

## 六、假目标生成（R4）

- 复用 `RVP_EcmDecoyEntity` 实体（`initDecoy(ownerVehicleId, nctrName, lifetimeTicks, driftVelocity)`）。
- 建议将 `RVP_EcmPassiveManager` 的 `spawnOneDecoy` 与 `DECOY_IDS` 登记抽为共享工具（新 `RVP_EcmDecoyRegistry` 或直接在主动管理器里调用同样的 spawn 代码并登记到同一 `DECOY_IDS`），保证 `tryDivertSeeker` 的 ARH/SARH 欺骗也能把主动假目标纳入候选（"和被动ECM一样的假目标"）。
- 主动释放：一次性按 `decoy_count=6` 个，随机半径内、只落已加载区块；寿命=`decoy_lifetime_ticks`。
- **散布范围 `decoy_radius`：硬编码**（批示确认，不设配置字段），固定取合理值。
- **NCTR 假标识（批示确认）**：同被动ECM——有默认池（回落 `BoneEcmPassiveConfig.nctrNames` 或内置默认池），也可在主动配置单独提供（`fake_decoy_nctr` 可选字段）。

---

## 七、导弹干扰矩阵（R5）——核心设计

统一前提：`RVP_EcmActiveManager` 每 `TICK_INTERVAL`（如 4 tick）遍历该 tick 活动中的 ECM 载具，对 `ammo_jam_radius` 内的 `RVP_BaseBullet` 子类导弹逐发判定 `guidanceType` 与阶段，施加下表效果。

> **前置过滤（批示补充）**：跳过 `shooterVehicle == ecmVehicle` 或 `RVP_EcmIff.areVehiclesFriendly(ecmVehicle, shooterVehicle)` 的导弹（**不干扰自身/同阵营**）。

| 导弹 | 判定 | 干扰注入 | 行为预期 |
| --- | --- | --- | --- |
| ARH（中继期） | `guidanceType==ARH && !isAutonomousSeekerOn() && hasDesignation` | 设 `ecmActiveJamRemainTick>0` + `ecmActiveNoReacquire=true`；`rvp$hasActiveSeekerSupportForDesignatedTarget()` 干扰期返回 false；`RVP_RuntimeActiveSeekerGuidance` ARH 开机扫描分支在 `ecmActiveNoReacquire` 时短路（clearTarget） | 中继断 → 失锁累计 → **200 tick** 自毁（批示：60→200），**无法重获** |
| AIR（中继期） | `guidanceType==AIR && !isAutonomousSeekerOn() && hasDesignation` | 仅 `ecmActiveJamRemainTick>0`（断中继），**不设 NoReacquire** | 中继断 → 现有逻辑自动 `setAutonomousSeekerOn(true)` → coast + 自身 AIR(IR) 搜索，搜到重跟踪 |
| SARH | `guidanceType==SARH` | 干扰期内 `RVP_RuntimeSarhGuidanceSource` 视作无照射（clearTarget） | 失照射失效 |
| HITL_TV / HITL_CLOS_TV（radio） | `hitl_signal_source==RADIO` | 干扰期内强制 `hitlLinkBlocked=true`（在 `tickHitlRadioLink` 结果处短路） | 累计 40 tick → `hitlLinkSevered` + 失控 coast |
| GPS 导弹/炸弹 | `guidanceType==GPS` | 首次干扰对 `targetPos` 施加一次随机 2D 偏移（半径 `gps_offset_meters`），复用/扩展 `ensureGpsTargetOffset` | 落点偏 ±20m，一次性 |
| ARM | 见 §7.5 | 优先级覆盖 + 记忆抖动 | 被吸引但打不准 |

**落地最小改动**（全部 RVP 类内）：
1. `RVP_BaseBullet` 加字段（含中文注释）：
   - `ecmActiveJamRemainTick`（int，>0 表示正被主动ECM干扰）
   - `ecmActiveNoReacquire`（boolean，ARH 专用）
2. `RVP_MissileEntity.rvp$hasActiveSeekerSupportForDesignatedTarget()` 开头加 `if (ecmActiveJamRemainTick>0) return false;`（断 ARH/AIR 中继）
3. `RVP_RuntimeActiveSeekerGuidance.evaluate()`：ARH 分支 `if (ecmActiveNoReacquire) { clearTarget(); }`（开机扫描前短路）；AIR 分支不动
4. `RVP_MissileEntity.tickHitlRadioLink()` 判定处加 `if (ecmActiveJamRemainTick>0) 视作 blocked`
5. `RVP_RuntimeSarhGuidanceSource.evaluate()` 开头加 `if (ecmActiveJamRemainTick>0) { clearTarget(); failed; }`
6. GPS：干扰首次对 `targetPos` 做一次偏移（`Random` 2D，半径配置）
7. 干扰到期（`ecmActiveJamRemainTick--` 归零）：NoReacquire 的 ARH 已自毁，其余恢复（如需）

> 干扰时长建议 ≥ 导弹自毁窗口（**200 tick**），默认 `ecmActiveJamRemainTick` 用一次性的固定值（如 220）即可，不必随 active 状态续期——导弹一旦失去中继基本即死。

### 7.5 ARM（反辐射导弹）交互（R8）

**目标**：ECM 释放后 5 秒内成为 ARM 视场**最高优先级目标**（优先级 > 任何预选目标）；但 ECM 干扰使 ARM **每次记忆落点随机 ±7m 偏移**——"易被锁定但打不准"。

**现状机制**（`RVP_RuntimeArmGuidanceSource`）：
- `copyPreselectedEmitter`（tick 0）：从 `RVP_WeaponLockStateTable` 复制预选辐射源 → 设 `targetPos` + `rememberGuidancePos`。
- `selectEmitter`（`:104-113`）：**先匹配预选**（vehicleId/radarIndex），再按 `AntiRadiationSeekerHelper.score`（距离/夹角/PDW/`lockedBonus`）选最优。
- 记忆制导（`:76-81`）：`antiRadiationMemoryLeftTick>0` 时每 tick 用 `lastGuidancePos` 作为 `targetPos`。

**实现设计**：
1. **优先级覆盖**：`RVP_EcmActiveManager` 维护 `ARM_PRIORITY = Map<vehicleId, untilTick>`（释放时登记 `arm_priority_ticks`）。`selectEmitter` 开头增加 ECM 覆盖段：
   - 遍历 `emitters`，若任一 emitter 的 `vehicle` 在 `ARM_PRIORITY` 有效期内 → **直接返回该 emitter**（越过预选匹配与 score 排序）。
   - 多个 ECM 载具同时在优先级窗口 → 取 score 最优者。
2. **前置条件（关键约束）**：`collectPulseDescriptors` 只收录 `radarUnit.isOn()` 的雷达辐射源。ECM 载具要进入 ARM 视场，**必须自身有开机的雷达**（否则 ARM 根本"看不见"它）。
   - 设计上建议：ECM 释放期间在管理器登记时同时校验载具有无开机雷达；若无则优先级覆盖自然失效（找不到该 emitter）。文档记录此约束；若产品上要求"纯干扰机无雷达也能被 ARM 锁定"，需另行在 `collectPulseDescriptors` 增加 ECM 伪脉冲通道（列入 §十三 备注）。
3. **记忆落点抖动**：干扰生效期间（`arm_memory_jitter`），在记忆制导分支：
   ```java
   Vec3 jitter = new Vec3((rnd*2-1), 0, (rnd*2-1)).scale(arm_memory_jitter_meters);
   Vec3 memory = projectile.getLastGuidancePos().add(jitter);
   projectile.setTargetPos(memory);
   ```
   每 tick 重新随机（"每次记忆落点产生随机偏移"），不污染 `lastGuidancePos` 本体（只用局部变量），避免累积漂移。
4. **敌我过滤**：ARM 归属（`getShooterVehicle`）与 ECM 同阵营时不做优先级覆盖（与 §七 前置过滤一致）。

### 7.6 ECM 伪脉冲通道（ARM-only，解决"纯干扰机也能被 ARM 锁定"）

**需求来源**：§7.5 备注——`collectPulseDescriptors` 只收录 `radarUnit.isOn()` 的真实雷达源，无雷达的干扰机进不了 ARM 视场。调研结论：**可以在 ARM 扫描管线内注入"伪脉冲"，且天然只有 ARM 可见**。

#### 7.6.1 为什么天然 ARM-only（机制确认）

ARM 目标的**唯一**信号来源是 `AntiRadiationSeekerHelper.collectPulseDescriptors`（O(实体) 扫描载具雷达），其调用链：

```
collectPulseDescriptors
  └─ scanVisibleEmitters
       ├─ RVP_RuntimeArmGuidanceSource.evaluate        （飞行中 ARM 制导）
       └─ GunnerWeaponSuitability.findBestTargetEmitter （gunner AI 判断 ARM 能否打某目标）
```

- **RWR 告警**走 `WeaponUnit.tick`（RADAR_SEARCH/RADAR_LOCK）+ `RVP_WarnRelayService`，**不经过** `collectPulseDescriptors`；
- **普通雷达探测**走 `RadarUnit.detectedObjects`，也不经过；
- 因此**在 `collectPulseDescriptors` 里追加的伪脉冲，不会被 RWR、不会被任何非 ARM 系统看到**——满足"只能被反辐射导弹识别"。

#### 7.6.2 注入设计

**注入点**：`collectPulseDescriptors` 遍历载具循环结束后，追加"活动中的主动ECM 载具"伪脉冲段：

```java
// 伪代码：在 out 循环之后追加
for (ActiveEcmInfo ecm : RVP_EcmActiveManager.getActiveVehiclesIn(level, seekerPos, seekRange)) {
    Vec3 jamPos = ecm.vehicle().position();          // 以干扰机位置为辐射源
    double dist = jamPos.distanceTo(seekerPos);
    if (dist > seekRange) continue;                   // 复用导引头扫描距离
    double angle = Math.toDegrees(VectorUtil.angleBetween(seekerLook, jamPos.subtract(seekerPos)));
    if (angle > seekerFov) continue;                  // 复用视场
    out.add(new RVP_RadarPulseDescriptor(
        tickCount,                       // timeOfArrivalTick
        4.0,                             // pulseWidthMicroseconds（锁定级宽脉冲）
        angle,                           // angleOfArrivalDegrees
        9000.0 + floorMod(ecm.vehicleId(), 1000),  // carrierFrequencyMhz（伪/干扰频段，与真实 8-12GHz 有区分度）
        4.0 * rcsFactor / (dist*dist),   // amplitude（强辐射，诱饵级）
        ecm.vehicleId(),                 // emitterVehicleId
        -1,                              // emitterRadarIndex = -1（伪脉冲哨兵）
        jamPos,                          // emitterPosition
        true                             // lockedEmission（始终视为"锁定辐射"，高吸引力+记忆无需扫描节拍）
    ));
}
```

**哨兵索引 `emitterRadarIndex=-1`**：真实雷达 index ≥0。`scanVisibleEmitters` 现有代码会因 `radarUnit == null` 跳过该脉冲 → 需改两处（见下）。

**可见窗口**：伪脉冲只在 ECM `active_duration_ticks` 内注入（ECM 干扰机辐射中）。`arm_priority_ticks`（5s）是子窗口，只管"最高优先级"覆盖；整个 active 期间伪脉冲都在。

**PDW 参数设计**：`lockedEmission=true`（宽脉冲 4.0µs、amplitude×2、score 里吃 `lockedBonus`）、高 amplitude → 天然是诱饵级强辐射；同时无需依赖 `pulseTickMap` 扫描节拍（始终可见）。

#### 7.6.3 需要改动的点

| 位置 | 改动 |
| --- | --- |
| `weapon/AntiRadiationSeekerHelper.collectPulseDescriptors` | 末尾追加活动 ECM 伪脉冲段（§7.6.2） |
| `weapon/AntiRadiationSeekerHelper.scanVisibleEmitters` | 放行 `emitterRadarIndex < 0`：只解析 `level.getEntity(emitterVehicleId) instanceof AbstractVehicle`，**跳过 radarUnit 解析**，构造 `radarUnit=null` 的 emitter |
| `weapon/AntiRadiationSeekerHelper.getDefaultMemoryTick` | **null 保护**：`radarUnit == null` 时返回默认记忆 tick（如 20） |
| `guidance/runtime/RVP_RuntimeArmGuidanceSource` | `getDefaultMemoryTick(best.radarUnit())` 处加 null 兜底（或改用 helper 内兜底）；`selectEmitter` 加 ECM 优先级覆盖（§7.5 已规划） |
| `ecm/RVP_EcmActiveManager`（新） | 提供 `getActiveVehiclesIn(level, pos, range)`（active 状态中且敌对的载具集合） |

> `RVP_RadarPulseDescriptor` 是 record，字段全 public，直接构造即可，无需改 record 本身（用 `radarIndex=-1` 识别伪脉冲，不加新字段，保持最小改动）。

#### 7.6.4 副作用与边界

1. **gunner AI ARM 也会"看见"伪脉冲**：`GunnerWeaponSuitability.findBestTargetEmitter` 过滤 `emitter.vehicleId() == targetVehicle.getId()` → gunner 判断"ARM 能否打某目标"时会把活动中的 ECM 载具视为可锁目标，并可将其写入预选（`setArmPreselected(root, ecmId, -1, pos)`）。这与"成为反辐射导弹视场优先级最高目标"一致。
2. **无 IFF 过滤现状**：ARM 路径（`selectEmitter`）对真实雷达源本就无友我过滤（谁辐射锁谁）——伪脉冲沿用同一行为。友方 ARM 锁友方 ECM 是现状雷达逻辑的延伸，一致。如需对伪脉冲单独加 IFF，在注入循环按 `RVP_EcmIff` 判断 seeker 归属与 ECM 阵营，列入备注（默认不做，保持一致）。
3. **pulseTickMap 不受影响**：伪脉冲直接可见，不写 `pulseTickMap`（避免污染真实雷达的记忆节拍表）。
4. **性能**：活动 ECM 载具数量级（1-3），O(1) 追加，无压力。
5. **`findBestRadiationSource`**：已无调用方（旧 ARM 路径死代码），其内部 `AntiRadiationTarget(...).getDefaultMemoryTick(best.radarUnit())` 若不删需一并 null 保护（建议顺带清理）。
6. **前置条件解除**：有了伪脉冲通道，§7.5 备注"ECM 载具须有开机雷达"的约束**不再需要**——纯干扰机（无雷达/雷达关闭）也能被 ARM 锁定。

---

## 八、载具干扰（R6）

### 8.1 敌对判定
复用 `RVP_EcmIff`（与 GunnerBrain/CIWS 同源）：
- `!RVP_EcmIff.isNeutralRadarVehicle(target)`（排除无主中立）
- `!RVP_EcmIff.areVehiclesFriendly(ecmVehicle, targetVehicle)` → 视为敌对
（等价于 GunnerTargeting/GunnerBrain 的 faction+Team+放置者链语义）

### 8.2 雷达脱锁 + 禁锁
仿 `RVP_ChaffJamHelper.breakLock`（`:153`）：对目标载具全部 `RadarUnit`/`WeaponUnit`：
- `radar.setLockedEntity(null)`（本体 public ✓，黑名单类只走 public 方法）
- 清 `WeaponUnit.setLockedEntity(null)` + pending
- `RVP_ChaffJamState.setCooldown(vehicleUuid, gameTime, 禁锁tick)` 防止立刻重锁（复用现有禁锁期，`applyRequestedLock`/`ClientRadarActionMixin` 自动拒绝）
- 周期性执行（载具在 vehicle_jam_radius 内且 ECM active 期间每 N tick 复检，因目标可能重新锁定）

### 8.3 RWR 伪造锁定（批示：**无实体纯干扰方案**）

批示：假源**不可交互隐身，且不要生成实体**——"直接纯干扰，让对方 RWR 看起来像坏了一样"。

**原实体方案的问题**：`RVP_RadarOverlay` 用源实体 id 取位置画方位线，源实体取不到则跳过该条。要显示方向线本需实体锚点。按批示改**无实体方案**——放弃方位线，只伪造 RWR 的"锁定告警+锁定源文案列表"：

1. 服务端 `RVP_EcmActiveManager` 每 10 tick 对范围内敌对载具：
   - 随机 `n = rand(fake_lock_min, fake_lock_max)` 个锁定源
   - 从 `fake_lock_sources` 池随机选文案，**count>1 时保证 ≥2 种不同信号**
   - **不生成实体**；直接发 `S2CEcmFakeLock(vehicleId, fakeRadarTypes[])` 给目标载具乘客
2. 客户端 handler（新增 `RVP_ClientEcmFakeLockHandler`）：
   - 对每个伪造源：`warningReceiver.targets.put(fakeId, new WarnTarget(RADAR_LOCK, fakeRadarType, now))`，其中 `fakeId` 用**递增负数**（不可能是真实实体 id）→ `RVP_RadarOverlay` 源实体查不到 → 该条**只触发告警与中心红字"被锁定"，不画线**，符合"RWR 像坏了一样"（告警音持续响 + 被锁定文字，但来源乱七八糟/显示不出）
   - 本体 `WarningReceiver.tick` 自动播循环锁定告警（高频 ✓）
3. 持续 `fake_lock_duration_ticks`，客户端 handler 按剩余时间停止补发；因 `targets` 500ms 自动过期，停止后自然消退。

> 若希望 RWR 上"看到多个不同方向锁定源"的观感，可后续再引入实体方案（待确认）；按当前批示先做无实体纯干扰。

---

## 九、范围分隔（R7）

- `ammo_jam_radius`：导弹干扰（§七）判定范围，以 ECM 载具位置为中心
- `vehicle_jam_radius`：载具干扰（§八）判定范围
- 两个半径独立配置、独立生效；假目标（R4）散布范围 `decoy_radius` **硬编码**（批示确认）

---

## 十、网络包与 HUD

| 包 | 方向 | 内容 |
| --- | --- | --- |
| `C2SFireEcm` | C2S | `vehicleId` |
| `S2CEcmActiveHudSync` | S2C | `vehicleEntityId` + `activeRemainTick/cooldownRemainTick/maxActiveTick/maxCooldownTick` |
| `S2CEcmFakeLock` | S2C | `vehicleId` + `fakeRadarTypes[]` + `durationRemainTick`（无实体伪造锁定） |

HUD：扩展 `RVP_EcmHudOverlay` 增一行主动ECM状态（`ECM(主动):反制中 Xs / 充能 Ys / 就绪`），沿用 `RVP_EcmHudState` 模式（可扩 `Snapshot`）。

---

## 十一、实施计划（里程碑）

| 阶段 | 内容 |
| --- | --- |
| P1 骨架 | `BoneEcmActiveConfig` + `BoneModuleType.ECM_ACTIVE` + `resolveEcmActiveDevices` + `C2SFireEcm` + `RVP_EcmActiveState` + 独立 `FIRE_ECM` 键 + 音效注册/播放 + HUD 状态行 |
| P2 假目标 | 共享假目标注册表抽取 + 主动生成 6 个假目标 + 生命周期配置 + NCTR 默认池/可配 |
| P3 导弹干扰 | 弹体字段 + 中继短路（ARH/AIR）+ SARH 断照射 + HITL-radio 断链 + GPS 偏移 + 敌我过滤（不干扰自身/同阵营） |
| P4 载具干扰 | 敌对判定 + 雷达脱锁（无论锁谁）+ 禁锁 + **RWR 无实体伪造锁定** |
| P5 ARM 交互 | `ARM_PRIORITY` 登记 + `selectEmitter` 优先级覆盖 + 记忆落点 ±7m 抖动 + **ECM 伪脉冲通道**（`collectPulseDescriptors` 注入 + `scanVisibleEmitters` 哨兵放行 + memory null 保护） |
| P6 收尾 | 双半径联调、与被动ECM共存验证、**SavedData 持久化**、配置文档 |

---

## 十二、风险与约束

1. **Mixin 纪律**：`RadarUnit/WeaponUnit/AbstractVehicle/WarningReceiver` 为本体黑名单类，**零新增 Mixin**。所有触及仅用 public 方法（`setLockedEntity/getLockedEntity/getDetectedEntities/targets.put`）或 RVP 自有类加字段。
2. **公共代码防 `@OnlyIn`**：RWR 客户端注入沿用现有 `RVP_ClientWarnRelay`（DistExecutor 分端）；新增客户端逻辑走 `client` 包，经 `RVP_ClientActionsAccess` 桥接。
3. **性能**：导弹/载具干扰循环只在 active 状态期间运行，`TICK_INTERVAL=4` 节流；范围内实体数有限。
4. **被动ECM 共存**：主动假目标与被动假目标可能同屏；共享注册表需处理去重与寿命管理，避免 CIWS/Seeker 重复计算。
5. **RWR 伪造可持续性**：500ms 条目窗口要求补发循环；无实体方案用递增负数 id，不与真实源 id 冲突；注意 `RVP_RadarOverlay` 对查不到源实体的条目只显示告警、不画线——符合"RWR 像坏了一样"。
6. **GPS 偏移只应一次**：避免每 tick 累积抖动；`ensureGpsTargetOffset` 已是一次性模式，主动干扰沿用它加一次性随机偏移。
7. **ARM 伪脉冲通道**：注入点仅在 ARM 专用管线 `collectPulseDescriptors`，天然只被 ARM 识别（RWR/雷达探测不经过）。`scanVisibleEmitters` 需放行 `emitterRadarIndex=-1` 哨兵 + `getDefaultMemoryTick` null 保护；gunner AI 的 ARM 判定会自动"看见"活动 ECM（符合预期）。
8. **不干扰自身/同阵营**：所有导弹/载具干扰循环前置 `RVP_EcmIff.areVehiclesFriendly` 过滤，避免友伤误触发。

---

## 十三、批示确认与新增（2026-08-26）

| # | 原待确认项 | 批示结论 |
| --- | --- | --- |
| 1 | 按键 | **新增独立 `FIRE_ECM` 键，默认键值=箔条键（LEFT_ALT）** |
| 2 | 假目标 NCTR | 同被动ECM：**有默认池，可自己配**（未配回落默认） |
| 3 | GPS 偏移时机 | **一次性** |
| 4 | ARH 自毁时长 | **60→200 tick**；补充：**ECM 不干扰自身或同阵营导弹** |
| 5 | 脱锁范围 | **干扰范围内敌方所有载具雷达锁定，无论锁谁都强制脱锁** |
| 6 | RWR 假源 | **不可交互隐身；不生成实体**——纯干扰让对 RWR 像坏了一样（§8.3 已改无实体方案） |
| 7 | decoy_radius | **硬编码**，不设配置 |
| 8 | SavedData 持久化 | **必须要** |
| 9 | （新增）ARM 交互 | 释放后 **5 秒内成为 ARM 视场最高优先级目标**（优先级高于任何预选）；但 **ARM 每次记忆落点随机 ±7m 偏移** → "易被锁定但打不准" |

**新增配置字段**：`arm_priority_ticks`(默认 100=5s)、`arm_memory_jitter_meters`(默认 7)。已并入 §四 数据模型。

**备注（已通过伪脉冲通道解决）**：原设计"ECM 载具须有开机雷达才会被 ARM 视为辐射源"的约束已解除——§7.6 的 **ECM 伪脉冲通道**在 ARM 专用扫描管线（`collectPulseDescriptors`）内注入伪脉冲，无雷达的纯干扰机也能被 ARM 锁定，且该通道天然只被 ARM 识别（RWR/雷达探测不经过该管线）。


