# RVP gunner 本地锁俯仰射界门修复（2026-09-16）

> 状态：已完成代码与文档，待实机验证
> 关联：`docs/plan/RVP交接_隐身与gunner适配与尾迹音效_20260916.md`（实机验证清单 §四）

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
