# SARH 导弹泄漏制导 Bug 修复任务（交给无上下文的 agent 执行）

## 你的身份
你是 Minecraft Forge 1.20.1 模组开发工程师，负责修复 RVP 附属模组中 SARH 半主动雷达导弹的制导来源泄漏 bug。

## 项目结构
- 本体模组（只读，禁止修改）：`D:\ywzj\ywzj\ywzj_vehicle\src\main\java`
- RVP 附属模组（你的修改对象）：`D:\ywzj\ywzj\ywzj_rvp\src\main\java`
- 项目约束：`D:\ywzj\ywzj\ywzj_rvp\agents.md`（Mixin 默认禁止、不改本体、中文注释）

## Bug 描述（用户实测复现步骤）

用户使用 F14A IRIAF 战斗机（rvp 包载具），挂载两型空空导弹：
- R-27R：SARH 半主动雷达制导导弹（`data/rvp/weapons/f14d_aim7p.json`，`guidance_type: "SARH"`）
- AIM-23B：ARH 主动雷达制导导弹（`data/rvp/weapons/aim_23b.json` 或类似文件名，`guidance_type: "ARH"`）

两型导弹挂在**同一个武器站**（`variable_missile`）下，通过武器切换键切换。

**复现步骤**：
1. 玩家雷达锁定敌机 → 发射 R-27R → R-27R 正常制导 ✓
2. 玩家关闭雷达 → R-27R 脱锁 ✓
3. 玩家重新开启雷达（仅扫描，不按锁定键）→ R-27R 继续脱锁 ✓
4. 玩家**切换武器至 AIM-23B（ARH）**→ 开启 ARH 导引头 → 导引头自动锁定敌机
   → **此时飞在空中的 R-27R（SARH）也重新开始制导了** ✗

**用户期望**：R-27R 是 SARH，玩家只给 AIM-23B 开了导引头，R-27R 应该继续脱锁。
SARH 的制导来源应该是 R-27R 发射时锁定的目标，不是 ARH 导引头后面抓到的目标。

## 根因分析（已定位，你负责修复）

泄漏链路在 `org.ywzj.rvp.guidance.runtime.RVP_RuntimeSarhGuidanceSource.evaluate()`：

```java
Entity illuminated = RVP_GuidanceTargetUtil.getStrictRadarIlluminatedTarget(context.projectile());
```

这行调用了 `RVP_GuidanceTargetUtil.getStrictRadarIlluminatedTarget()`（文件
`org/ywzj/rvp/guidance/RVP_GuidanceTargetUtil.java:19`），其内部链路：

```
getStrictRadarIlluminatedTarget(projectile)
  → projectile.getShooterWeaponUnit()
  → shooter.getRootParentWeaponUnit()          // root = variable_missile
  → RVP_RadarRoleHelper.getEffectiveRfLockedEntity(root)
      → 来源1: getLockedRadar(root).getLockedEntity()   // ← 雷达 TWS 锁（泄漏源！）
      → 来源2: RVP_WeaponLockStateTable.getExternalRadarLockedEntityId(root)
      → 来源3: root.getLockedEntity()                    // ← 武器站手动火控锁
```

**泄漏原理**：`getLockedRadar(root)` 返回载具雷达单元，其 `lockedEntity` 字段会被
雷达 TWS 扫描自动写入（`RadarUnit.java` 的扫描/锁写逻辑）。玩家切换武器至 ARH 并
开启导引头后，ARH 导引头锁定的目标被写入了雷达/武器站共享的锁定状态，导致飞在
空中的 R-27R（SARH）也读到了这个锁并开始制导。

**本质**：`getEffectiveRfLockedEntity` 的多个来源没有区分"锁的来源"（手动 vs TWS
自动），导致 ARH 导引头的自动锁定结果泄漏到了 SARH 弹上。

## 你需要做的事

1. **修改 `RVP_RuntimeSarhGuidanceSource.evaluate()`**：
   - SARH 的照射目标来源改为**仅认武器站手动火控锁**（`root.getLockedEntity()`）
     + 外置雷达锁（`RVP_WeaponLockStateTable.getExternalRadarLockedEntityId(root)`）
   - **不再读雷达 TWS 自动跟踪锁**（即跳过 `getLockedRadar(root).getLockedEntity()`）
   - 这样 SARH 只在玩家按锁定键时才制导，ARH 导引头的自动锁定不会泄漏到 SARH

2. **同时检查**：`WeaponUnitTickFireControlMixin.java` 中用户已做的修复
   （RF 分支跳过 SARH 的 `Radar.findTarget` 自动锁）是否完整——确认没有其他路径
   在 SARH 不应制导时写入锁定。

3. **写一个调试记录**：修改后编译通过 + 服务端冒烟通过，将修改摘要写入
   `docs/plan/代码问题记录_20260904.md`。

## 关键约束
- **不改本体源码**（`ywzj_vehicle`）
- 遵循 `agents.md` Mixin 纪律
- 中文注释
- 修改后 `./gradlew build` 编译通过
- 修改后同步 `limitless_vehicle/rvp/data/rvp/vehicles/f14d.json` 到运行目录（如需要）

## 关键文件清单
| 文件 | 作用 |
|------|------|
| `src/main/java/org/ywzj/rvp/guidance/runtime/RVP_RuntimeSarhGuidanceSource.java` | SARH 制导源（**主要修改对象**） |
| `src/main/java/org/ywzj/rvp/guidance/RVP_GuidanceTargetUtil.java` | 照射目标解析（当前调用的 `getStrictRadarIlluminatedTarget`） |
| `src/main/java/org/ywzj/rvp/radar/RVP_RadarRoleHelper.java` | `getEffectiveRfLockedEntity` / `getLockedRadar`（泄漏链路） |
| `src/main/java/org/ywzj/rvp/mixin/WeaponUnitTickFireControlMixin.java` | 用户已修：RF 火控分支跳过 SARH 自动锁 |
| `src/main/java/org/ywzj/rvp/weapon/core/RVP_WeaponLockStateTable.java` | 外置雷达锁状态表 |
| `src/main/java/org/ywzj/vehicle/vehicle/part/RadarUnit.java` | 本体：雷达单元（只读参考） |
| `src/main/java/org/ywzj/vehicle/entity/vehicle/AbstractVehicle.java` | 本体：手动锁写入（:1049） |

## 输出要求
修改完成后输出：
1. 修改的文件列表与每处修改摘要
2. 编译结果（BUILD SUCCESSFUL）
3. 实机验证要点
