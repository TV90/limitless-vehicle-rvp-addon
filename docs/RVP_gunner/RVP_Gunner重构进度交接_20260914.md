# RVP Gunner 阶段 A：行为等价基线交接

> 完成日期：2026-09-14  
> 代码基线：Git `HEAD=4f509f1666ec`，并以完成时工作树中的 Gunner 源码为权威  
> 对应方案：[RVP_Gunner行为组合渐进式重构实施方案_20260914.md](./RVP_Gunner行为组合渐进式重构实施方案_20260914.md) §12 阶段 A  
> 基线测试：`src/test/java/org/ywzj/rvp/entity/gunner/ai/RVP_GunnerBehaviorBaselineTest.java`  
> 状态：阶段 A 已实施；未引入行为管理器、未修改 Gunner 生产逻辑、未修改 `gunner.json`

---

## 1. 本阶段交付了什么

阶段 A 新增一组源码特征测试，冻结当前 Gunner 权威执行链的可观察语义。测试不复制一套 Gunner 算法，也不尝试在普通 JUnit 中伪造 Minecraft 完整世界；它直接检查当前生产源码中的关键顺序、分流、控制输出、门控常量和清理动作。

这组基线的用途是：阶段 B 以后每移动一段代码，先确认对应语义已经被新的动作层/行为层测试接住，再更新旧源码位置断言。它是重构护栏，不是最终行为管理器测试形态。

新增测试共 8 项：

| 测试 | 冻结内容 |
|---|---|
| `serverTickPipelineKeepsCurrentAuthoritativeOrder` | 服务端主 tick 的完整调用顺序，SEAD 对普通驾驶/战斗的抢占 |
| `targetingKeepsCiwsPreemptionAndCurrentTierOrder` | CIWS 快路径、普通扫描节流、目标优先层级、GPS 最远目标、空中/雷达范围 |
| `weaponEngagementKeepsAimLockGuidanceFireTransactionOrder` | 瞄准、选武器、导弹纪律、burst、发射前锁定、制导准备、RIPPLE/SALVO、开火后冷却 |
| `movementKeepsCurrentVehicleDispatchAndControlOutputs` | 固定翼、旋翼、发射架、普通地面分流，以及停车、前进、倒车、转向、升降、姿态控制输出 |
| `radarGuidanceAndDefenseKeepCurrentSupportSemantics` | 本车雷达、外置雷达/UAV、GPS、照射、HITL、本体反制武器、Smoke、ECM、低频 Flare/Chaff |
| `seadKeepsCurrentPreemptionStateMachineAndTiming` | SEAD 触发、立即发射、Chaff、飞离/回旋/锁定发射、总超时、冷却和退出清理 |
| `entityLifecycleKeepsServerAuthorityAndCleanupContract` | 客户端早退、服务端入口、离座 40 tick 删除、目标/驾驶状态清理、通用冷却推进 |
| `phaseAProfileSchemaAndDefaultsRemainFlatAndExplicit` | 当前平铺 Profile 字段和主要 Java 默认值；阶段 A/F 前不得意外提前切换 `behaviors` schema |

生产代码未因本阶段改变，因此现有游戏行为不受测试代码影响。

---

## 2. 当前权威基线

### 2.1 服务端主 tick 顺序

必须保持的当前顺序：

```text
tickCooldowns
  -> Profile / 座位 / driver / WeaponUnit 解析
  -> tickTargeting
  -> 司机首次补满 + 持续补给，或非司机控制/状态清理
  -> tickCountermeasure
  -> tickEcmActive
  -> tickSmokeEvasion（仅 driverAi）
  -> tickRadarLock
  -> GunnerExternalRadarController.tick
  -> GunnerGuidedWeaponController.tick
  -> tickSead（仅 driverAi）
       -> 接管时跳过普通 driving/combat
  -> tickDriving
  -> tickCombat（有 WeaponUnit、有目标、允许开火）
  -> 无战斗时 controlledWeaponIndex=-1
```

阶段 B 拆动作层时不能随意改变该顺序，尤其不能把 `prepareLaunchLock`、`prepareForLaunch` 放到实际开火之后，也不能让普通驾驶覆盖 SEAD 控制。

### 2.2 目标选择

当前目标权威规则：

- 每 tick 先查 CIWS；命中后立即覆盖普通目标。
- 普通目标仅在 `tickCount % scan_interval_tick == 0` 时重扫，中间 tick 复用实体同步目标。
- 普通候选层级依次为：可拦截 RVP 弹药、ECM 假目标、敌对 Gunner 载具、玩家/玩家载具、其他有效目标。
- 层内开启 `gps_prefer_farthest` 且存在 GPS 可打目标时选最远，否则使用当前距离/夹角评分。
- 固定翼和旋翼基础搜索半径为 Profile 半径的 6 倍；本车或外置雷达范围更大时取雷达范围。
- 目标死亡或无法解析时清空，不允许把失效实体继续交给开火链。

### 2.3 武器事务

当前普通交战的不可拆顺序：

```text
预测瞄准点
  -> WeaponUnit.aim（发射架跳过普通炮塔瞄准）
  -> selectWeaponIndex
  -> 写 controlledWeaponIndex
  -> 对空持锁/同目标重射纪律
  -> 全制导武器冷却
  -> burst 窗口
  -> 非制导炮塔角误差窗口
  -> prepareLaunchLock
  -> 制导门控失败时回退非制导武器
  -> prepareForLaunch
  -> 选 RIPPLE 单上下文或 SALVO 全上下文
  -> WeaponUnit.shoot(..., gunner)
  -> burst / 导弹 / CIWS 目标冷却
```

当前关键常量与规则：

| 项目 | 当前值/规则 |
|---|---|
| RVP 制导武器统一冷却 | 100 tick |
| 对空首次持锁 | 100 tick |
| 对同一空中目标重射 | 100 tick |
| CIWS 远近分界 | 200 格；远处优先制导，近处优先非制导 |
| 制导优先级 | GPS 1、AntiRadiation 2、IR/AIR 3、ARH/SARH 4、SACLOS/SALH/LBR/LH 5、HITL 6 |
| RIPPLE | 单个 `aimContext()` |
| SALVO | 全部 `aimContexts()` |

注意：`WeaponUnit.shoot()` 没有直接返回“本次是否真正生成弹体”。阶段 B 设计原子武器动作时必须继续尊重武器舱先开启、Forge `VehicleFireEvent.Pre` 取消、武器自身冷却/弹药校验等本体语义，不能把“调用了 shoot”简单等同于“成功发射”。

### 2.4 移动输出

每次普通驾驶先 `ControlUnit.reset()`，然后按载具能力分流：

| 分支 | 当前典型输出 |
|---|---|
| 普通地面 | 朝目标左右转；满足角度/距离时 `forward`；卡住时 `backward + left/right`；近距先 hold 后战术规避 |
| 地面无目标 | `ground_wander_enabled` 时持续 `forward`，周期性选择大角度左右转 |
| Smoke 驻留 | 未进入烟云时转向烟云并 `forward`；进入半径 60% 后不给输入，即停车 |
| 发射架有作战弹药 | 再次 reset 并停车，让武器站自由工作 |
| 发射架无弹/装填 | 无目标时漫游；有目标时使用地面转向、前进、倒车脱困和战术规避 |
| 固定翼无目标 | 巡航/回航，但返回 `allowFire=false` |
| 固定翼有目标 | 攻击/脱离阶段计算 `yRot`，保持 `forward`，通过 `xRot` 控制高度；目标过近时拉开 |
| 旋翼低高度 | `up=true`，姿态 keep，暂不允许开火 |
| 旋翼正常空战 | 攻击阶段朝向目标、脱离阶段背向目标，通过 `up/down/xRot/yRot` 保持高度和姿态 |
| SEAD | 固定翼保持前飞并写俯仰/偏航；旋翼写偏航并按高度写 `up/down`；优先于普通驾驶 |

后续引入 `MovementIntent` 时，“停车”必须是显式最终结果；不能因为某行为没有写控制量，就留下上一 tick 或另一行为的控制输入。

### 2.5 雷达、制导与防御

- 本车 RF 锁定顺序为：选择/打开雷达、范围和转角校验、`detect`、箔条禁锁、写 RadarUnit 与 root WeaponUnit 锁。
- 外置雷达仅在司机 AI + RF 火控下运行；中继缺失时每 20 tick 尝试部署 UAV，之后开中继发动机/雷达、探测、检查箔条并写 requested/locked 状态。
- 制导 tick 固定执行 designation、GPS、在途 HITL；HITL/照射 Owner 弹药搜索半径为 4096 格。
- 武器站式反制先找危险弹药，再 `aim -> shoot -> countermeasure cooldown`。
- Smoke 只用于地面司机，错相每 10 tick 检测 IR/AIR 锁、100 格内敌方来袭弹或敌方激光照射，触发后驻留 260 tick。
- 主动 ECM 接受 RWR 的 `RADAR_LOCK`/`MISSILE_LAUNCH` 或有效半径内危险弹药。
- `RVP_GunnerVehicleTickService` 每 5 tick 扫描 Gunner 司机载具；自动反制同车 100 tick 节流，IR/AIR 用 Flare，SARH/ARH/雷达锁用 Chaff。

### 2.6 SEAD

| 状态/参数 | 当前基线 |
|---|---:|
| 威胁扫描间隔 | 10 tick |
| 雷达锁来源搜索半径 | 1024 格 |
| `FLY_AWAY` | 100 tick |
| `REVERSAL` | 最多 160 tick |
| `LOCK_FIRE` | 最多 40 tick |
| 总保险超时 | 400 tick |
| 退出冷却 | 400 tick |

入口存在可用 AntiRadiation 且门控通过时先尝试立即发射，然后释放 Chaff 并进入飞离。复仇阶段实际发射成功后才设置 `seadRevengeFired`，成功、目标丢失、阶段超时或总超时都会进入统一清理和冷却。

### 2.7 生命周期和清理

- `GunnerEntity.tick()` 客户端执行 `super.tick()` 后立即返回，战术只在服务端运行。
- 骑乘载具时每 20 tick 尝试修复到有效武器座，再进入 `GunnerBrain.tick()`。
- 离车后立即清司机状态、清目标；超过 40 tick 后删除 Gunner。
- `clearDriverRideState()` 当前清除补给绑定、脱困、战术 hold/evade、Smoke、home position 和全部 SEAD 临时状态。
- `tickCooldowns()` 当前推进 burst、反制、导弹、CIWS 目标、脱困、战术、Smoke 和 SEAD 冷却。

---

## 3. 自动验证结果

针对性命令：

```powershell
$env:JAVA_HOME='C:\Users\FishKing0721\.jdks\ms-17.0.16'
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=D:\WgameProject'
./gradlew test --tests org.ywzj.rvp.entity.gunner.ai.RVP_GunnerBehaviorBaselineTest
```

完成时结果：

```text
tests=8, skipped=0, failures=0, errors=0
BUILD SUCCESSFUL
```

完整构建与服务端冒烟结果见本文 §6；若后续代码变更，以新的命令输出为准，不要沿用本文历史结果替代验证。

---

## 4. 这组测试怎样用于后续重构

### 4.1 阶段 B 的使用方式

每次只迁移一个动作域：

1. 修改前运行本基线测试，确认工作树起点为绿。
2. 为新动作适配器增加输入/输出单元测试。
3. 将现行调用改为经过适配器。
4. 再运行本基线测试和新测试。
5. 若源码标记因合理移动而变化，只有在新测试已经覆盖相同语义后，才可更新对应特征断言。
6. 不允许用“实现位置变了”为由直接删掉整个基线测试。

推荐迁移顺序仍为：武器事务、雷达、制导、反制/ECM、移动、补给。

### 4.2 阶段 C/D 的演化方式

引入 Context/Intent 后，逐步把源码特征断言升级为记录型 fake gateway 测试：

```text
相同上下文输入
  -> 现行行为基线期望
  -> 新行为提交 Intent
  -> 仲裁后的动作记录
  -> 比较 target / movement / aim / fire / radar / defense / cleanup
```

一个领域完成这种语义级测试后，可以删除该领域对旧私有方法名的依赖，但必须保留本文记录的规则或在后续文档中明确记载经批准的行为变化。

### 4.3 阶段 F 的处理

`phaseAProfileSchemaAndDefaultsRemainFlatAndExplicit` 会阻止意外提前加入 `behaviors` 字段。正式执行阶段 F 时，应在同一变更中：

- 用脚本改写全部 Gunner JSON；
- 增加 schema v2 解析/校验测试；
- 将该测试从“平铺 schema 基线”替换为“schema v2 only”；
- 不加入旧字段别名或 Java 迁移分支。

---

## 5. 已知边界与不能误读的结论

### 5.1 自动测试边界

当前测试是源码特征测试，能够及时发现关键调用缺失、顺序改变、常量变化和清理遗漏，但不能单独证明：

- Minecraft 物理下的实际转弯半径和飞行轨迹完全一致；
- Forge `VehicleFireEvent.Pre` 被其他模组取消时的最终弹体数量；
- 武器舱动画完成后真实发射时刻；
- 多实体实战中的目标评分结果和 TPS；
- 客户端 HUD、声音和远程姿态表现。

因此阶段 B/D 涉及相应领域时，仍要补记录型动作测试和实机场景。源码特征测试不是“实机已验证”的替代品。

### 5.2 当前实现中已有的技术债

以下是阶段 A 冻结时已经存在的问题，不应在后续实现中复制为新架构原则：

- `isCountermeasureWeapon()` 仍以武器路径片段识别本体式反制武器，违反最终“不得按武器 ID 分支”的目标。阶段 B 封装武器能力时应改为数据能力判断，并将其作为明确的行为变化单独验证。
- 世界实体扫描目前分散在索敌、Smoke、SEAD、HITL、外置雷达和低频反制服务中；阶段 E 再合并，阶段 B 不应顺手改变扫描频率。
- `GunnerProfileManager` 当前解析失败处理不够显式；严格错误与原子重载属于后续 Profile 阶段。
- 当前调试监控以客户端工具为主，不能替代服务端权威动作记录。阶段 C 建议增加服务端 debug snapshot。
- 部分攻击代码以“调用 `WeaponUnit.shoot`”作为发射推进点，但本体方法不返回实际成功结果；动作事务设计时需要补足可验证语义。

### 5.3 性能基线

阶段 A 只记录静态扫描入口，没有在自动场景生成 1/8/16/32 个 Gunner，因此不能声称已经得到 MSPT 实测值。

当前 Gunner 相关源码可见的全量遍历入口共有 10 处：

- `RVP_GunnerVehicleTickService`：4 处；
- `GunnerGuidedWeaponController`：2 处；
- `GunnerTargeting`：1 处；
- `GunnerExternalRadarController`：1 处；
- `GunnerBrain`：Smoke 威胁 1 处、SEAD 雷达锁来源 1 处。

这里记录的是源码入口数量，不等于每 tick 实际执行次数。阶段 E 必须以运行期计数和 MSPT 为准。

---

## 6. 构建与服务端冒烟

### 6.1 完整构建

权威命令：

```powershell
$env:JAVA_HOME='C:\Users\FishKing0721\.jdks\ms-17.0.16'
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=D:\WgameProject'
./gradlew build
```

完成时结果：

```text
BUILD SUCCESSFUL in 25s
17 actionable tasks: 13 executed, 4 up-to-date
```

### 6.2 服务端冒烟

完整构建通过后执行了 `./gradlew runServer`，并每 10 秒轮询一次。本次启动日志出现：

```text
[16:31:38] [Server thread/INFO] [minecraft/DedicatedServer]: Done (3.090s)! For help, type "help"
```

结论：服务端冒烟通过。确认 `Done` 后已结束测试服务器；结束动作造成的 Gradle 进程退出码不参与启动结论。

本次 `ERROR` 与上一份 `debug-1.log.gz` 对比一致：本体 Bedrock 模型缺失 8 条、`abramsx.structure - 副本.json` 非法路径 4 条、`rvp_bomber:ac130u` 配方解析和 `rvp_bomber:tu160` 载具数据各 1 条，均为本阶段之前已存在的载具包噪音；没有发现由 Gunner 基线测试引入的新错误。

---

## 7. 接手清单

开始阶段 B 前依次确认：

- [ ] 阅读本交接与总实施方案 §2～§6、§12。
- [ ] 检查工作树，保护用户已有未提交改动。
- [ ] 运行 8 项 Gunner 基线测试。
- [ ] 选择一个动作域，不同时迁移多个高耦合域。
- [ ] 新动作层使用本体公开 API/RVP 自有 API，无新增 Mixin。
- [ ] 新增字段和调用点按项目要求补清晰中文注释。
- [ ] 不按载具 ID、武器 ID 或路径名新增分支。
- [ ] 新测试覆盖旧断言后，才更新源码特征标记。
- [ ] 执行完整构建与服务端冒烟。
- [ ] 将有意改变的战术规则另立变更记录，不伪装成“等价重构”。

阶段 B 的最小推荐起点是武器动作事务：先封装 `aim -> select -> lock -> guidance prepare -> shoot -> cooldown`，保持 `GunnerBrain` 的现行顺序不变。
