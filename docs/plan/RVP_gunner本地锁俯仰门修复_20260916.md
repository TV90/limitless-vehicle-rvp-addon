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
