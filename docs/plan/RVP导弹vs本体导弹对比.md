# RVP 导弹 vs 本体导弹对比

## 武器体系对比

| 维度 | 本体 `VehicleMissile` | RVP `RVP_WeaponBase` + `RVP_MissileEntity` |
| --- | --- | --- |
| **武器类** | `VehicleMissile extends AbstractVehicleWeapon<VehicleMissileWeaponData>`，独立的导弹武器类 | `RVP_WeaponBase extends AbstractVehicleWeapon<RVP_WeaponData>`，所有武器类型（导弹/火箭/机炮/炸弹/激光/投放器/吊舱）共用同一个基类，通过 `RVP_EnumWeaponKind.MISSILE` 区分 |
| **弹体实体** | `MissileEntity extends AmmoEntity`，独立实体类 | `RVP_MissileEntity extends RVP_BaseBullet extends AmmoEntity`，通过 `RVP_EnumWeaponKind.MISSILE` 区分行为 |
| **配置格式** | `VehicleMissileWeaponData` 内置字段（`seekerFov`、`activeRadarActivationRange` 等） | `RVP_WeaponData` + `guidance_data.stages[]` + `sources[].params`，完全 JSON 配置化，支持多阶段切换 |
| **反辐射导弹** | 无原生支持 | `AntiRadiationSeekerHelper` + `MissileEntityMixin` + `RVP_ClientArmState`，完整的 ARM 预选/HUD/导引 |

## 导引头（Seeker）对比

| 维度 | 本体 | RVP |
| --- | --- | --- |
| **`withSeeker()`** | 写死 `return true` | 修复后为 `return kind == MISSILE`。修复前只对 `require_lock=true` 或 ARM 返回 `true`，普通 ARH/IR/SARH 都返回 `false` |
| **导引头实际作用** | 导引头开启后，`tickFireControl()` 自动搜索目标并锁定（详见下文） | 导引头开关仅控制 `seekerOn` 状态。ARM 用该状态控制 HUD 扫描；其他导弹主要用于 UI 反馈 |
| **`toggleSeeker()`** | 命中条件 `withSeeker()` 写死 true，总能执行 | 修复后 `kind == MISSILE` 即可执行 |

## 锁定系统对比

### 发射前锁定检查

| 维度 | 本体 | RVP |
| --- | --- | --- |
| **`doClientShoot()`** | HOMING 模式下直接检查 `lockedEntity == null`，无锁则拒绝发射 | `requiresEntityLock()` 方法，排除了 ARH/ARM/GPS/SACLOS 弹（这些不需要发射前锁） |
| **RVP 的 `requiresEntityLock()`** | — | `data.getWeaponKind() == MISSILE && requireLock && !isActiveRadar() && !isAntiRadiationMissile() && !isGpsMissile()` |
| **ARH 弹** | 必须有 lockedEntity 才能发射 | 不需要，`dispatchShots()` 中故意传 `null`，弹体发射后自行搜索 |
| **ARM 弹** | 无支持 | 不需要，通过 `RVP_ClientArmState` + `WeaponUnitArmExt` 提交预选目标 |

### 发射锁传递

| 本体 | RVP |
| --- | --- |
| `missileEntity.targetEntity = rootWeaponUnit.getLockedEntity()`，锁定目标直接传给弹体 | `dispatchShots()` 中：ARH 传 `null`（弹体自主搜索）；其他类型传 `lockedEntity` |

### 自动锁定（重要差异）

本体的 `WeaponUnit.tickFireControl()` 有一段重要逻辑（[WeaponUnit.java#L488-L504](file:///d:/ywzj/ywzj/ywzj_vehicle/src/main/java/org/ywzj/vehicle/vehicle/part/WeaponUnit.java#L488-L504)）：

```java
// 导引头开启并冷却后，自动搜索并锁定目标
else if (isSeekerOn() && lockedEntity == null) {
    lockCoolingTick += 1;
    if (lockCoolingTick > 20) {
        if (weapon instanceof VehicleMissile missile) {
            if (sensor == RF && homingMode == ACTIVE_RADAR) {
                entity = Radar.findTarget(getMainRadarUnit(), fov, this);
            } else if (sensor == IR) {
                entity = Infrared.findTarget(this, fov);
            }
        }
        if (entity != null) setLockedEntity(entity);
    }
}
```

**RVP 的问题**：RVP 的武器类型是 `RVP_WeaponBase`，不是 `VehicleMissile`，所以 `weapon instanceof VehicleMissile` 永远为 `false`，这段自动锁定逻辑对 RVP 导弹**完全不执行**。

| 影响 | 说明 |
| --- | --- |
| **ARH 弹** | 影响不大。RVP 的 ARH 弹本身就不需要发射前锁，弹体飞行中通过 `RVP_ArhGuidanceSource` 自主搜索。但导引头开启后，你不会看到本体那种"自动找到目标 → lockedEntity 被设置 → HUD 显示锁定框"的反馈 |
| **IR 弹** | 同上。需要手动按 `R` 键锁定，不能指望本体的 `tickFireControl()` 自动给你锁 |
| **SARH 弹** | 必须手动按 `R` 键锁定雷达目标。即使 `require_lock=true`，这里也不会帮你自动锁 |
| **ARM 弹** | 完全不受影响。ARM 的预选不走本体的 `tickFireControl()` 锁定系统，走 `RVP_ClientArmState` |

### R 键锁定

| 本体 | RVP |
| --- | --- |
| `fireControlLock()` 完整实现 RF/IR/EO 三种传感器模式的锁定/解锁 | RVP 只有 `RVP_SaclosLockInput`（SACLOS 激光开关）。RF 和 IR 的 R 键锁定走本体逻辑 |

## 飞行中制导对比

| 维度 | 本体 | RVP |
| --- | --- | --- |
| **ARH** | 基于 `homingMode == ACTIVE_RADAR` + `activeRadarActivationRange`，单阶段硬编码 | `RVP_ArhGuidanceSource` + guidance stage 系统，支持多阶段切换（如 IOG → ARH），参数全 JSON 配置 |
| **SARH** | 基于 `homingMode == SEMI_ACTIVE_RADAR`，依赖载机雷达持续照射 | `RVP_SarhGuidanceSource`，通过 `RVP_GuidanceSeekerUtil.getIlluminatedTarget()` 获取载机锁定目标 |
| **IR** | 基于 `homingMode == INFRARED`，简单热源追踪 | `RVP_IrGuidanceSource`，支持 `ignore_flares`、`vehicle_only`、`fallback_on_jammed` 等参数 |
| **ARM** | 无支持 | `MissileEntityMixin` + `AntiRadiationSeekerHelper` |
| **IOG** | 无原生 IOG 阶段 | `RVP_IogGuidanceSource`，惯性飞行至目标点 |
| **GPS** | 无支持 | `RVP_GpsGuidanceSource` + `GPSTargetManager` |
| **TV/HITL** | 无支持 | `RVP_HitlGuidanceSource` + `RVP_MissileEntity` HITL 状态 |
| **SACLOS/MCLOS** | 无支持 | `RVP_SaclosGuidanceSource` + `RVP_MclosGuidanceSource` |

## 已修复的问题

1. **`withSeeker()` 返回 false** — 修复前只对 `require_lock=true` 的导弹返回 true，改为所有 `kind == MISSILE` 都返回 true
2. **ARM HUD 不显示** — 重写 `RVP_ClientArmState` 扫描逻辑，使用 `worldPivotPosition()` + `worldRot()` 获取正确导引头位置
3. **旧格式武器 JSON** — 5 个文件从旧数组格式（`guidance_data: [...]`）改为新对象格式（`guidance_data: {"stages": [...]}`）

## 一个值得注意的残留问题

**`tickFireControl()` 中的 `instanceof VehicleMissile` 检查**。RVP 的武器不是 `VehicleMissile`，所以导引头开启后本体的自动锁定逻辑不执行。实际影响：

- 按 `~` 开导引头后，RVP 导弹不会像本体导弹那样自动搜索并锁定目标
- 你需要自己按 `R` 键手动锁定（RF/IR 传感器）或使用 `[`/`]` 切换 ARM 预选目标
- 对游戏体验影响有限，因为 RVP 导弹的核心制导逻辑（ARH/IR/SARH）在发射后由弹体自行处理，发射前锁不是必须的

如果要修复这个问题，需要让 `RVP_WeaponBase` 触发本体 `tickFireControl()` 中的自动搜索逻辑，或者 RVP 自己实现一套等效的自动锁定。但这是优化性质的问题，不是阻塞性 bug。
