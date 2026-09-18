# RVP gunner 本地锁俯仰射界门修复（2026-09-16）

> 状态：已完成代码与文档，待实机验证
> 关联：`docs/RVP_gunner/RVP交接_隐身与gunner适配与尾迹音效_20260916.md`（实机验证清单 §四）

---

## 一、问题现象

用户驾驶 Su-57 **打开弹舱**、**贴脸**（极近距离）掠过 gunner 操作的山毛榉（bukm3），
gunner 始终无法锁定。用户怀疑：分角度 RCS 隐身、弹舱开启增幅、或 gunner 锁链 bug。

## 二、排查结论（证据链）

### 1. RCS 隐身/弹舱排除

- 三条隐身消费链（客户端探测表过滤、ARH 获取距离、gunner 索敌感知）**都只收缩探测
  外边界**：判据是 `距离 ≤ 半径 × combinedFactor`，贴脸时距离→0 **恒通过**；
- 弹舱增幅只会**放大**因子（Su-57 单舱 0.12×6=0.72，封顶 1），让载具更"亮"；
- `RVP_AspectRcs.combinedFactor` 终值钳 `[0.01, 1.0]`，所有退化路径（无 profile、
  水平重合、字段缺失、解析异常）都偏向 1.0，**不存在归零路径**；
- 12 份 JSON（3 机 × 本地包+3 份 run 副本）`rvp_radar_rcs_factor` / `open_radar_rcs_multiplier`
  逐字节一致，无缺字段、无旧值残留。

### 2. 玩家能锁、gunner 锁不了 → 两条锁路径分叉（关键对照）

| | 玩家手动锁 | gunner AI 锁 |
|---|---|---|
| 目标来源 | 探测表（phase：`RVP_ClientRadarTickHandler`，**俯仰仅 ±90° 宽过滤**） | `findBestTarget` 索敌（RCS 感知门只缩外边界） |
| 锁定落位 | `RVP_RadarRoleHelper.applyRequestedLock`（只查箔条+在表）→ `ClientRadarAction.LOCK` → 服务端**裸写** `setLockedEntity`（本体 `ClientRadarAction.java:51-52`，零角度校验） | `RVP_GunnerRadarActions.maintainLocalLock`（**每 tick 硬门校验**） |
| 俯仰限制 | **无（天顶可锁）** | **视轴以上全拒（bug，≈0°）** |

### 3. 根因：俯仰门把 rot_info 的 x_rot 用错了语义

`RVP_GunnerRadarActions.maintainLocalLock`（修复前）：

```java
Vec2 aimRot = radar.aimRot(lockTarget.position());
if (aimRot.y < radar.getYRotMin() || aimRot.y > radar.getYRotMax()
        || aimRot.x < radar.getXRotMin() || aimRot.x > radar.getXRotMax()) {
    clearLocalLock(weaponUnit, radar);
    return RVP_GunnerActionResult.GATED;
}
```

- `VectorUtil.vecToRot`（本体 `VectorUtil.java:279-283`）是 MC 俯仰约定：
  `pitch = atan2(-y, 水平距)`，**负=向上**；
- 载具包雷达惯例 `x_rot_min: 0 / x_rot_max: 85`（bukm3/cssa5/ps1sm/96l6 全部如此），
  本意是"仰角 0~85°"；
- 但 `RotatableUnit.getXRotMin()` 原样返回 JSON 值（`RotInfo` 无符号翻转），
  于是 `x_rot_min=0` 变成"MC 俯仰下限 0"——**目标只要高于雷达瞬时视轴
  （aimRot.x < 0）恒 GATED 清锁**；
- 本体对这组值唯一用法是 `RadarUnit.tickRot` 的**碟面动画钳制**（bukm3 为
  `scan_animation_mode: "phase"` + `x_rot_speed: 0`，碟面恒 0，该组值实际不生效）；
  本体扫描（`tickScan`）/探测（`tickDetect`）目标过滤**只查方位 y，从不查俯仰**；
- RVP 两条扫描链（`RVP_RadarScanService` 服务端 / `RVP_ClientRadarTickHandler` 客户端）
  俯仰也只有 `|aimRot.x − 当前xRot| ≤ scanSectorAngle/2 = 90°` 的宽过滤（等于不限制）。

**净效果**：数据写的上视 85°；扫描/探测/玩家锁全链路无俯仰限制；
只有 gunner 本地锁把它执行成"视轴以上全拒"≈0°。贴脸掠顶时炮塔追瞄、
目标持续高于视轴 → 本地锁永远立不住 → 无 RADAR_LOCK 告警、
`prepareLaunchLock` 失败、不开火。受影响面：**所有"雷达挂炮塔 + rot_info x_rot_min=0"
的 gunner 载具**（bukm3/cssa5/ps1sm/96l6 等），非 Buk 独有。

## 三、修复（零新增 Mixin，全部在 RVP 侧）

### 1. 俯仰门修正（`RVP_GunnerRadarActions.maintainLocalLock`）

- **删除 `aimRot.x` 俯仰判定**，保留 `aimRot.y` 方位 ±45° 扇区判定
  （对齐本体扫描/探测"只查方位"语义）；
- 注释记录 rot_info x_rot 的真实语义与本次根因；
- 语义定版：**无死区**（贴近正头顶也可锁；炮塔物理仰角上限 75° 仍由本体瞄准限制，
  只影响炮口指向，不影响锁判定；9M317MA 主动弹发射后自行爬升转向）。
- 预留变体（如后续想要真实性死区，再实施）：改世界系仰角判定，
  目标仰角 > `x_rot_max`（85°）才拒锁，阈值走 JSON 可调。

### 2. `/rvpdebug gunnerlock` 常驻诊断（新增 `RVP_GunnerLockDebug`）

仿 `RVP_GunnerDebugMonitor` 纪律（`@OnlyIn(CLIENT)` + 调用点
`FMLEnvironment.dist == Dist.CLIENT` 守卫 + 专用日志 `logs/rvp_gunner_lock_debug.log`）。
事件驱动 + 按载具/门控 20t 节流，默认关闭：

| 通道 | 门控 | 记录点 |
|---|---|---|
| LOCK | RANGE / YAW / CHAFF / UNSUPPORTED / LOCKED | `maintainLocalLock` 各出口（含 dist/aimY/限位） |
| FIRE | NO_WEAPON / AIR_DISCIPLINE / COOLDOWN / AIM_WINDOW / LOCK_PREPARE_FAIL / FIRED | `RVP_GunnerWeaponActions.engage` 各出口 |
| SENSE | REJECT#目标id | `GunnerTargeting.passesAspectPerception` 拒绝（factor/effective/dist，40t 节流） |
| RELAY | RELAY_DOWN / RELAY_UNLOCKED / RELAY_LOCKED | `GunnerExternalRadarController.tick`（锁目标变化才记 RELAY_LOCKED；缺中继 100t 心跳） |

改动文件：
- 新增 `org.ywzj.rvp.entity.gunner.ai.RVP_GunnerLockDebug`；
- `RVP_GunnerRadarActions`（修门+插桩）、`RVP_GunnerWeaponActions`（插桩）、
  `GunnerTargeting`（插桩）、`GunnerExternalRadarController`（插桩）、
  `client/debug/RVP_DebugCommands`（`rvpdebug gunnerlock on|off|status`）。

## 四、验证

### 已完成

- `./gradlew build`：见 `build_gunnerlock_fix_20260916.log`（结果回填）；
- `./gradlew runServer` 冒烟：见 `server_smoke_gunnerlock_fix_20260916.log`（结果回填）。

### 实机清单（用户执行）

1. `/rvpdebug gunnerlock on` 后复现：
   - [ ] Su-57 开弹舱贴脸掠顶 Buk → LOCK 通道不再出现持续 YAW/俯仰 GATED，
         出现 LOCKED → RADAR_LOCK 告警 → 5 秒纪律后导弹升空；
   - [ ] 远距正面接近 → SENSE 通道出现 REJECT（隐身缩减感知仍生效，RCS 无回归）；
   - [ ] cssa5/ps1sm 等"雷达 x_min=0"载具 gunner 对空回归；
   - [ ] 箔条断锁（CHAFF 门）、组网拦截、IR 锁定音抽查不回归。

## 五、功能丢失/降级

- **有意变更**：gunner 本地雷达锁不再有俯仰限制（原俯仰门从未正确工作过，
  实际效果是"视轴以上全拒"）；修复后贴脸/掠顶可被本地锁。玩家的雷达锁行为不受影响
  （玩家锁本就无俯仰门）。

---

## 六、追加排查（2026-09-16 第二轮）："只锁定不攻击"（F-14D/f14a_iriaf）

### 现象

俯仰门修复后，用户实测 gunner 山毛榉对玩家驾驶的非隐身飞机（先报 f14d，后更正为
f14a_iriaf——诊断日志里只有实体类名 `fixed_wing_vehicle#715`，无法区分机型，均无 RCS
配置，不影响结论）**只锁定（RWR 有锁定告警）不攻击**。

### 日志判读（logs/rvp_gunner_lock_debug.log）

- RELAY 通道有日志（含 RELAY_LOCKED target=fixed_wing_vehicle#715）；
- LOCK / FIRE 通道整段零日志。

LOCK/FIRE 全空 ⇒ `maintainLocalLock`（GunnerBrain:149）与 `engage`（经 tickCombat:166）
从未执行到任何带日志的出口——两处背靠背共用同一 target 参数，而中继锁成立（其兜底
`findRelayScanTarget` 只在 target==null 时触发）⇒ **AI 的 trackedTarget 恒为 null：
索敌（findBestTarget）从未选中该飞机，engage 从未被调用**。中继"锁定仅是告警"是
既有设计（见交接文档 §五.2），不构成攻击。

### 三个候选拒因（索敌收集谓词逐门排查）

| 拒因 | 位置 | 触发条件 |
|---|---|---|
| **创造模式保护** | `GunnerTargeting.isValidTarget` → `hasProtectedCreativePassenger` → `isProtectedCreativePlayer` | 乘客含创造/旁观玩家。**2026-09-15（c31171d9）起为无条件保护（任何难度都不打）**；中继不做此过滤 → 恰好"只锁不攻" |
| 对空弹专用门 | `GunnerWeaponSuitability.canSelectForTarget` L96-99 | 9M317MA 无 `lock_altitude_range` → 判"仅对空弹"；"对空"=硬编码 AGL>25 → **AGL≤25 的低空固定翼整个进不了候选池**（真 bug，已修） |
| 档案 target_types | gunner 档案 JSON（friendly=`["monster","vehicle:enemy_gunner"]` 等） | 档案不含玩家目标则永不索敌玩家；中继只查 team/owner 敌对照锁 |

已排除：RCS 感知（无配置因子恒 1.0）、组网硬禁（只管弹药）、俯仰门修复（只动锁门）。

### 本轮修复（cbaf5fac 之上）

1. ~~恢复困难难度例外~~（当日上午中间版，**同日已被下方"方案A 最终定版"取代**）：曾把
   `isProtectedCreativePlayer` 改为"创造仅非困难保护（创造+困难可被打，步行/驾驶一并生效）"。
2. **低空对空弹误拒修复**：`GunnerWeaponSuitability.isAirTarget` 对 FixedWing/RotaryWing
   恒真（低空掠飞也是空中目标）；其余实体维持 AGL>25（不打地面单位语义保留）。
3. **插桩补全**：LOCK 通道补 NO_TARGET/NO_RADAR 静默出口；新增 AI 通道 NO_TARGET
   （profile/索敌半径/驾驶员模式/难度）；SENSE 通道候选拒绝首因分类
   （CREATIVE_PLAYER/CREATIVE_PASSENGER/PROFILE_TYPE/ALLIED/WEAPON_UNUSABLE(AGL)/PERCEPTION）。
   下次日志可直接读出拒因。

### 创造保护最终定版（2026-09-16 方案A，用户选定矩阵）

用户澄清：09-15 的误解在于把"创造免攻击"扩大到载具、且早先还有难度例外。最终矩阵
（难度只在**载具分支**参与）：

| 状态 | 是否被 gunner 攻击 |
|---|---|
| 创造 · 步行 | ❌ 任何难度都不被打 |
| 创造 · 驾驶载具（含载具内创造乘员） | 困难→✅被打；非困难→❌保护 |
| 生存/冒险 · 任何状态 | ✅ 任何难度都被打 |
| 旁观 | ❌ 永不被打 |

实现：
- `isProtectedCreativePlayer`（仅步行玩家分支）＝创造/旁观无条件保护，**难度不参与**；
- `hasProtectedCreativePassenger`（载具分支）＝存在创造/旁观乘员保护整车，但仅
  **非困难难度**（`getDifficulty() != Difficulty.HARD`），困难难度可被打；
- 行为基线 `RVP_GunnerBehaviorBaselineTest` 同步冻结：步行保护方法体断言不得含
  `Difficulty`（步兵保护与难度无关）；载具乘员分支断言含
  `getDifficulty() != Difficulty.HARD`。

### 实机验证清单（追加）

- [ ] 创造步行（任意难度）：不被任何 gunner 攻击；
- [ ] 创造 + 困难难度驾驶 f14a_iriaf：Buk 选中并 5 秒对空纪律后发射导弹；
- [ ] 创造 + 非困难难度驾驶：受保护（不打）；
- [ ] 生存模式步行/驾驶各难度：都被攻击；
- [ ] 低空（<25 格 AGL）飞行可被锁定并攻击（对空弹低空门修复）；
- [ ] 若仍不攻击：`/rvpdebug gunnerlock on` 后看 SENSE REJECT#id 的 reason 与 AI NO_TARGET
      的 profile/driverMode/difficulty——PROFILE_TYPE=档案不对（换 enemy 档案刷）；其它按 reason 对号。

---

## 七、第三轮（2026-09-16）："gunner 老远探测到隐身战机"——乘员绕过 RCS 感知门

### 现象

用户生存模式驾驶隐身战机（Su-57），AI gunner（山毛榉）**老远就探测/锁定**；同距离下用户
自己操作 AA 车却探测不到 AI 的隐身战机——明显不对称。

### 根因（用户猜想"gunner 没找到飞机却感知到驾驶飞机的我"——正确）

`GunnerTargeting.collectTargetEntities` **不排除骑乘者**：驾驶隐身战机时，玩家的 Player
实体作为独立候选进入索敌收集（target_types "player" 命中）；而
`passesAspectPerception` 原实现对非载具实体**直接放行、不吃分角度 RCS 因子**——
- 你的隐身战机（载具实体）：被因子正常限制；
- **驾驶飞机的你（Player 实体）：绕过隐身门**，在全索敌半径（有 96L6 中继时
  max(1024, 1500, 3500) 格）内都是合法候选 → trackedTarget=你 → `normalizeTarget`
  解析回战机 → 锁定开火；
- 反方向无此洞：AI 隐身战机驾驶员是 GunnerEntity 非 Player，不会被"player"层收集——
  **不对称完全吻合**。创造模式下此洞被创造保护（步行分支拒创造玩家）掩盖，生存必现。

### 修复

`passesAspectPerception` 开头解析骑乘关系：候选骑乘在载具上（`entity.getVehicle()`）
时，RCS 因子按**所乘载具**的 `combinedFactor` 判定（距离按乘员实体自身位置）；
真正步行玩家/弹药维持"直接放行"。行为基线同步冻结
（`RVP_GunnerBehaviorBaselineTest`：断言源码含 `entity.getVehicle() instanceof AbstractVehicle ridden`）。

### 实机验证清单（追加）

- [ ] 生存模式开隐身战机正面接近 AI gunner：探测/锁定距离收缩到 索敌半径×方位因子
      （有中继 3500×0.12≈420 格内才被发现，拉远即脱）；`/rvpdebug gunnerlock on` 下
      AI NO_TARGET 持续、SENSE REJECT#id 的 factor 为载具因子；
- [ ] 同距离 AA 车对 AI 隐身机与 AI gunner 对用户隐身机行为对称；
- [ ] 步行玩家/导弹拦截等非骑乘目标的索敌行为不变。

---

## 八、第四轮（2026-09-16）：搜索中继与火控中继分离（96L6 vs IRIST TADS）

### 用户指出的语义问题

96L6 是搜索/指示雷达（现实中不能锁定目标），但配置里 `radar_role` 竟是 `"fire_control"`，
导致 gunner 用 96L6 中继"锁定"并发射导弹（RWR 出现 96L6 的锁定告警）。正确语义应为：
①96L6 只提供搜索/指示——gunner 炮口转向目标但不发射；②RWR 只有 96L6 "S400" 的
RADAR_SEARCH、无锁定告警；③目标进入 bukm3 发射车**自身雷达烧穿距离**后 gunner 才发射。
IRIST TADS（radar_role "all"）是火控中继，可锁可射——保持不变。

### 调查结论

- **配置**：`96l6.json:82` `radar_role: "fire_control"`（错误源头）；`irist_slm_tads.json:82`
  `radar_role: "all"`（TADS 可锁符合预期）；
- **选锁雷达**：`RVP_ExternalRadarLinkHelper.getPreferredRelayLockRadar` 本就按
  `canLock`（排除 SEARCH）过滤——配置改对后 96L6 自动退出锁雷达选择；
- **发射授权**：`GunnerWeaponSuitability.getStrictRfLockedEntity:369-372` 允许外置中继锁
  顶替本车火控锁 → 搜索中继不写锁后该授权自然消失（TADS 仍写锁仍可射）；
- **搜索告警**：96L6 探测表唯一喂食者是控制器每 tick 的 `radar.detect(target)`，
  `RVP_WarnRelayService` 由此发 radar_type "S400" 的 RADAR_SEARCH——只喂表不落锁即可保告警；
- **炮口指示无现成路径**：炮塔瞄准只在 engage（`weaponUnit.aim`）里，而 engage 需要
  trackedTarget；原中继接触不进 trackedTarget。

### 修复（搜索中继 vs 火控中继双分支）

1. **配置**：4 份副本 `96l6.json` `radar_role → "search"`（载具包不进 git）。
2. **搜索中继分支**（`GunnerExternalRadarController`）：`getPreferredRelayLockRadar` 为
   null 时改用 `getPreferredRelaySearchRadar`（新 helper，search 优先/最大扫描兜底）——
   仍 `findRelayScanTarget`（RCS effectiveRange 门控）+ `radar.detect` 喂表；接触有效性按
   **烧穿距离**校验（新 `isWithinRelaySearchVolume`，acquire/retain 一致无盲区）；接触记入
   侧表（40t 新鲜度）；**不落锁、不写外置授权表**。
3. **gunner 炮口指示**：`GunnerBrain.tickTargeting` 自身索敌无结果时回退取
   `getRelaySearchContact` 作 trackedTarget——engage 会 `weaponUnit.aim` 转炮口，因无锁
   `prepareLaunchLock` 失败不发射（fire 日志 LOCK_PREPARE_FAIL）。
4. **本车烧穿发射门**：`maintainLocalLock` 距离门改为 `maxScan × combinedFactor`，出烧穿
   距离保持 100t（5 秒）宽限再脱锁（镜像客户端链 LOCKED_TRACK_GRACE_TICKS 语义）；
   非隐身目标因子 1.0 行为不变。
5. **诊断**：gunnerlock RELAY 通道新增 SEARCH_ACQ / SEARCH_LOST（接触变化触发）；
   LOCK 通道 BURN_THROUGH 门（dist/burnThrough/graceExpired）。
6. **基线**：`RVP_GunnerBehaviorBaselineTest` externalRadar ordered 断言改写
   （搜索分支 recordRelaySearchContact 即 return；火控分支保留原顺序）+
   radar 段冻结烧穿门（`maxRange * RVP_AspectRcs.combinedFactor` + 100t 常量）+
   tickTargeting 断言追加 `getRelaySearchContact` 回退。

### 修复后完整时序（隐身战机 vs 带搜索中继的 SAM 阵地）

96L6 RCS 门控发现 → "S400" 搜索告警 + Buk 炮口跟转（无锁、无锁定告警、不发射）
→ 目标进入 Buk 烧穿距离（1500×因子，正面 0.12≈180 格）→ 本车落锁 → "BUK" 锁定告警
→ 5 秒对空纪律 → 导弹发射。

### 实机验证清单（追加）

- [ ] 带 96L6 的 Buk：隐身战机远距只有 "S400" 搜索告警，Buk 炮口跟转但无锁定告警/无导弹；
- [ ] 目标进入烧穿距离（正面 ≈180 格内）→ "BUK" 锁定告警 + 5 秒后导弹；拉出烧穿距离
      （含 5 秒宽限）后锁告警停止、不再发射；
- [ ] IRIST TADS 中继：远距锁定+开火行为保持不变（radar_role "all"）；
- [ ] 非隐身目标（f14a_iriaf）：96L6 搜索 + Buk 全程行为与旧版一致（因子 1.0）；
- [ ] gunnerlock 日志：搜索中继阶段 RELAY SEARCH_ACQ / AI NO_TARGET（或 FIRE
      LOCK_PREPARE_FAIL）、进入烧穿后 LOCK LOCKED + FIRE FIRED。

### S400 告警点亮条件核对表（2026-09-16 追加，问题二排查指引）

S400（96L6）亮 = 搜索分支接触通过全部四道门 + `RVP_WarnRelayService` 从探测表发
RADAR_SEARCH（文字每 5t 刷新、响声每 `scan_period_tick`=60t 一轮）。不亮只可能：

| 条件 | 阈值/来源 | 日志特征 |
|---|---|---|
| ① 96L6 存活 | 被毁后 `deployable_uav_redeploy_cooldown_tick`=1200t（60 秒）冷却 | RELAY 通道 RELAY_DOWN"无可用中继载具" |
| ② 距离 ≤ 3500 × 隐身因子 | 隐身机正面 0.12≈420 格、侧 0.35≈1225；非隐身 3500 | RELAY 无 SEARCH_ACQ（距离越界即不记录接触） |
| ③ 离地 ≥ `scan_min_height`（96L6=25） | 96l6.json；**低飞时 96L6 看不见你，但 BUK 本车锁无高度门——低空只剩 BUK 告警** | 同上 |
| ④ 告警节奏 | 96L6 `scan_period_tick`=60 → 每 3 秒闪一次（BUK 每秒） | 视觉易漏，文字 600ms 平顶 |

"S400 只和 BUK 一起亮"的典型场景即 ③（低空）——BUK 本车锁无高度门而 96L6 有，属数据
语义不对称；如需统一可调 96l6 `scan_min_height` 或给 maintainLocalLock 加同款高度门
（会改变低空可攻击性，需用户定版，未实施）。若四条件均满足仍不亮，属代码 bug，另立排查。

同日追加修复：搜索中继指示目标采纳前必须过 `GunnerTargeting.isValidDesignationTarget`
（=索敌同款 `isValidTarget` 全链：创造保护方案A矩阵/target_types/敌我）——修复"创造+
和平模式驾驶被攻击"回归（搜索中继接触链不做创造过滤，指示采纳后又经本车落锁获得
发射授权，绕过方案A保护）。

---

## 九、第五轮（2026-09-17）：弹药分角度雷达信号（ammo_radar_rcs_factor）

### 需求

给 RVP 导弹/炸弹/火箭弹加与战机同款的分角度 RCS 隐身。现有
`misc_data.signal_intensity_factor_on_radar`（14 个武器配 5/6）是均匀倍率不分角度——
全量替换为新字段 `misc_data.ammo_radar_rcs_factor: [迎头, 侧向, 尾向]`（字段名含 ammo
与载具的 `rvp_radar_rcs_factor` 区分），参照方向为**弹体飞行方向**（迎头突防信号最小），
探测距离 = `雷达 max_scan_distance × 方向因子`，无 ≤1 封顶（弹药可增透）。

### 迁移（用户定版）

- j16_akf98a / rafale_storm_shadow → `[0.08, 0.35, 0.18]`（与 j20a 相同隐身参数）；
- 其余 12 个（9k720_9m723、f14d_mk84、j10c_gb3_ir/laser、m142_atacms、
  f14d_maodie_yeshenggounai、mi28_kh_39、rafale_aasm_ir/laser、m1a2sep_lahat、
  spice_1000、su57_kh38）→ `[1, 1, 1]`（不再有旧 5/6 倍增透，按雷达标称距离探测）；
- 迁移脚本 `scripts/migrate_ammo_radar_rcs_20260917.py`（带 type=missile/bomb/rocket 断言，
  机炮等其它类型跳过）：主包 14 + run/server 14 + client_1/client_2 各 11 成功（这两份
  陈旧副本本就缺 j16_akf98a/f14d_mk84/f14d_maodie_yeshenggounai 三文件）；旧字段全项目 0 残留。

### 实现

- `RVP_MiscData`：删旧字段/resolver/默认工厂，新增 `ammo_radar_rcs_factor` float[3]
  （Gson 解析，缺省 null→[1,1,1]，单档钳 [0.01,10]，非法整体回退 1）；`RVP_WeaponData` 转发；
- `RVP_BaseBullet`：三档字段（spawn 赋值 + 生成包同步三 float，替代原单 sig）；
  `getSignatureSize()` 改返回**侧向值**（红外虚拟箱/遗留消费不变）；新增
  `getRadarSignatureTowards(observerPos)` 按速度方向插值（纯 Math 的
  `RVP_AmmoRadarRcs.factorTowards`，零速度回退侧向）；无 rvpData 保持 0 不可见；
- 消费链：`RVP_RadarScanHelper.appendRvpAmmoTargets`（玩家客户端雷达 + 服务端 gunner
  雷达表）与 `RVP_ExternalRadarSyncService.appendAmmoTargets`（中继快照，顺带修复旧
  "sig 只当开关不当倍率"不一致）改调方向因子；`GunnerTargeting` 弹药层
  （findCiwsTarget/findNearbyAmmoTarget/findBestTarget rvpAmmo 层）新增拦截感知门
  （索敌半径 × 方向因子，与战机 passesAspectPerception 同构；>1 由索敌 AABB 封顶）；
- 不动：红外虚拟箱仍用侧向均匀值（红外不分角度）、ARH 导引头（弹药本非候选）、
  接触保持（探测难跟踪易）、组网记账、载具探测链。

### 实机验证清单（追加）

- [ ] AKF98A/风暴阴影迎头突防：雷达/中继快照上出现距离明显短于侧掠（j20a 同款 0.08）；
      gunner CIWS/拦截对迎头隐身弹明显变难；
- [ ] 9M723/kh38 等配 [1,1,1] 的弹药：雷达探测距离 = 雷达标称值（不再有旧 5/6 倍增透，
      属用户定版的平衡变化）；
- [ ] 机炮弹/未配置弹药行为不变；红外弹对导弹的锁定（虚拟箱）不变。

---

## 十、（2026-09-17）激光武器命中载具触发激光照射告警

用户提出：`rvp:laser` 命中载具也应触发激光告警（此前仅 LH/SALH 操作手照射会话触发 LWR，
激光武器命中无任何告警）。实现：`RVP_LaserWeapon.shoot` 射线命中载具时调
`RVP_LaserWarnService.warnLaserHit(sourceVehicle, targetVehicle)`——敌对才告警
（Team 联盟判定同 scanLevel）、向目标乘客发 `S2CMissileTrackAlert(TYPE_LASER)`
（客户端 `laser_alert` 音效 + "被激光照射"提示）、同"射手车×目标车"对 10t 节流；
友方照射不告警。照射会话型 LWR（scanLevel 扫描 LBR/LH/SALH 照射点）不变。
文档同步：《RVP 激光武器数据模型文档》联动表与伤害结算节更新。

实机验证：生存模式用 rvp:laser 照射敌对载具 → 目标乘客收到"被激光照射"提示与音效；
友军载具不被告警；创造载具同样会收告警（告警非攻击，不涉创造保护）。

---

## 十一、（2026-09-17）激光致盲系统（laser_data blind_* 三字段，默认关闭）

### 语义（用户定版）

`rvp:laser` 命中玩家/gunner/其骑乘载具时累计受击次数；`blind_hit_count` 次（窗口内）触发
致盲——**触发时计数清零、窗口重开、重新累计**（再次攒满刷新时长；超窗清零重计）。
`blind_hit_count` **默认 0 = 功能默认关闭**，仅显式配置的激光生效。

- 玩家：S2CLaserBlind → 客户端全屏白色闪光滤镜（前 70% 全亮、后 30% 渐隐，登出清理）；
- gunner：服务端致盲状态（isBlinded）——视觉索敌失效；**有雷达载具例外**：致盲期间仅
  雷达制导/雷达中段（SARH/ARH/AIR）导弹可选且要求 hasUsableRadar（96L6 搜索中继不算、
  TADS 算）；无雷达载具 gunner 彻底哑火；
- CIWS 拦截不变；射手/宿主不自我致盲（canHit 排除）。

### 实现

- `RVP_LaserData` +3 字段；`RVP_LaserBlindService`（新，服务端：累计侧表/致盲状态表/
  onLaserHit/isBlinded）；`S2CLaserBlind`（新包，协议 11→12）+ 客户端
  `RVP_ClientLaserBlindState` + `RVP_LaserBlindOverlay`（RenderGuiEvent.Post 白色滤镜）；
- `RVP_LaserWeapon.shoot` 命中累计调用；`GunnerWeaponSuitability.canSelectForTarget`
  致盲分支（SARH/ARH/AIR + hasUsableRadar(weaponUnit, target)）；
- 消费点语义：索敌层 hasUsableWeaponForTarget 自动收紧 → 雷达无力/无雷达武器的
  gunner 自然失去目标；CIWS 雷达指向不受影响。

### 实机验证清单（追加）

- [ ] 默认（未写 blind_* 字段）激光：无任何致盲；
- [ ] 配置 blind_hit_count=3 后：生存玩家被命中 3 次（5 秒内）→ 白屏 10 秒渐隐，触发后
      计数重置（再打 3 次才再次致盲，期间命中不刷新白屏时长）；
- [ ] 创造驾驶被命中 → 白屏；
- [ ] 有雷达+SARH/ARH 弹的敌方 gunner 被致盲 → 失去目标但雷达弹仍会打；
- [ ] 无雷达/无机炮弹 gunner 被致盲 → 哑火。
