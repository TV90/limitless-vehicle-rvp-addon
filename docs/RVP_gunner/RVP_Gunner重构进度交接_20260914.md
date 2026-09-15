# RVP Gunner 渐进式重构进度交接

> 更新日期：2026-09-16
> 代码基线：Git `HEAD=3ede38d6`（包含 `d3f765dd` 的 RCS/Gunner 修复及阶段 B 动作层）；以当前 Gunner 源码为权威
> 对应方案：[RVP_Gunner行为组合渐进式重构实施方案_20260914.md](./RVP_Gunner行为组合渐进式重构实施方案_20260914.md) §12
> 当前状态：阶段 A、阶段 B 已完成；阶段 C～G 未实施
> 本轮范围：仅修改 Addon 的 Java 源码、Gunner 基线测试与文档；未修改本体、Mixin、Profile JSON 或载具包资产

---

## 1. 当前结论

阶段 B“先封装动作能力”已经形成可编译、可测试、可在服务端加载的最小闭环：

- `GunnerBrain` 继续保持阶段 A 的权威调用顺序和战术算法；尚未引入 Context、Intent、行为管理器或新版 Profile schema。
- 新增 `RVP_GunnerActionGateway`，统一聚合移动、武器、雷达、制导、防御和补给六个动作域。
- `GunnerBrain` 已不再直接调用 `WeaponUnit.shoot`、不再直接写 `ControlUnit`、不再直接写本车雷达锁、RVP 干扰物、主动 ECM 或补给反射。
- 普通攻击、CIWS 本体式反制和 SEAD AntiRadiation 发射均从同一个武器动作适配器进入本体权威发射链。
- 地面、发射架、固定翼、旋翼、Smoke 驻留和 SEAD 的移动算法只生成 `Command`；每条最终路径由移动动作适配器执行一次 `reset + apply`。
- 动作结果使用统一枚举显式表达 `EXECUTED`、`DISPATCHED`、`GATED`、`UNSUPPORTED`、`INVALID` 和 `NOT_DRIVER`。

阶段 B 没有修改 Gunner Profile 字段、默认值、扫描频率、目标层级、飞行/地面战术参数或 JSON。

2026-09-16 将 `d3f765dd` 的目标丢失清锁修复接入重构后的动作边界：`RVP_GunnerRadarActions.maintainLocalLock()` 在目标为空或死亡时清理该武器站全部 `RadarUnit` 锁和 root `WeaponUnit` 锁，再返回 `INVALID`。`GunnerBrain.tick()` 每 tick 都调用此适配器，因此目标死亡、离开感知范围或索敌结果为空时不再留下 RWR 幽灵锁或 SARH 空中继。新增阶段 B 基线断言保护该清理契约；有效目标锁定流程保持不变。验证：指定环境下 `./gradlew build` 通过；`./gradlew runServer` 日志出现 `Done (2.636s)!`。日志 ERROR 与 §5.2 基线一致，未见本次新增错误。

---

## 2. 新增动作层

新增目录：

```text
src/main/java/org/ywzj/rvp/entity/gunner/behavior/action/
├─ RVP_GunnerActionGateway.java
├─ RVP_GunnerActionResult.java
├─ RVP_GunnerMovementActions.java
├─ RVP_GunnerWeaponActions.java
├─ RVP_GunnerRadarActions.java
├─ RVP_GunnerGuidanceActions.java
├─ RVP_GunnerDefenseActions.java
└─ RVP_GunnerSupplyActions.java
```

### 2.1 统一网关与结果

`RVP_GunnerActionGateway.INSTANCE` 持有六个无状态领域适配器。阶段 B 的 `GunnerBrain` 直接取得对应适配器；阶段 C 应让管理器持有同一网关，不要再创建第二套执行入口。

`RVP_GunnerActionResult.DISPATCHED` 有特殊含义：本体 `WeaponUnit.shoot(...)` 和 RVP 干扰物 `fire(...)` 不返回“是否真正生成弹体/干扰物”，因此动作层只能明确表示请求已交给权威链。当前 burst、导弹和 CIWS 冷却仍保持阶段 A 的语义，在本体 `shoot` 调用后推进；本轮没有伪造“真实发射成功”。

### 2.2 武器动作

`RVP_GunnerWeaponActions` 现在拥有：

- 普通/CIWS 的 `aim -> select -> discipline/cooldown -> fire window -> prepareLaunchLock -> fallback -> prepareLaunch -> shoot -> cooldown` 完整事务；
- SEAD AntiRadiation 武器查找、门控、制导准备、单管发射和统一导弹冷却；
- 本体式反制武器的瞄准与发射；
- 武器选择优先级、GPS 优先、200 格 CIWS 远近分流、制导/机炮回退；
- 发射架是否单上下文、RIPPLE/SALVO 上下文选择；
- 发射架“是否仍有作战弹药”的只读能力查询；
- 调试监控使用的只读武器选择入口。

旧 `GunnerBrain.isCountermeasureWeapon` 的武器路径匹配已移除。现在使用本体武器运行时类型 `VehicleDecoyFlare`，以及 `VehicleGrenade` 的 `grenade=aps` 数据能力识别反制武器，不按武器 ID 或路径分支。

### 2.3 移动动作

`RVP_GunnerMovementActions.Command` 与当前实际用到的 `ControlUnit` 字段一一对应，并额外携带旋翼机 hoverMode 的显式写入请求。所有字段均有中文用途注释。

`apply(gunner, vehicle, command)`：

1. 校验载具和命令有效；
2. 用本体 driver 入口与 seat 0 兼容判断确认 Gunner 是司机；
3. 调用一次 `ControlUnit.reset()`；
4. 一次性复制本 tick 的最终前进、倒车、转向、升降、俯仰和偏航输出；
5. 需要时同步旋翼机 hoverMode。

非司机提交返回 `NOT_DRIVER`。停车是全 false 的显式 `stopCommand()`，发射架停车、战术 hold、Smoke 云内停车和 `allow_drive=false` 清理不再依赖多个方法直接重置载具控制。

### 2.4 雷达与制导动作

`RVP_GunnerRadarActions` 直接拥有本车雷达动作：打开雷达、选择主锁定雷达、目标归一化、范围/射界、探测、箔条禁锁、RadarUnit/root WeaponUnit 锁定与清理。

外置雷达动作由同一适配器做 driver/RF 能力检查后，调用既有 `GunnerExternalRadarController` 维持中继部署、扫描、requested/locked 状态和失效清理。保留现有控制器作为动作适配器内部实现，避免阶段 B 同时改动外置雷达算法。

`RVP_GunnerGuidanceActions` 提供两条边界：

- `maintain(...)`：维持 designation、GPS 与多枚在途 HITL；
- `prepareLaunch(...)`：在武器事务内部、`shoot` 之前准备本发武器的 GPS/照射控制源。

当前仍复用经过阶段 A 冻结的 `GunnerGuidedWeaponController` 作为内部实现。阶段 C 的行为/管理器只能调用动作适配器，不应重新直接调用旧控制器。

### 2.5 防御与补给动作

`RVP_GunnerDefenseActions` 统一暴露：

- 本体式反制武器发射，内部复用武器动作适配器；
- RVP Flare、Chaff、Smoke 系统查询与发射请求；
- 主动 ECM 发射，并保留底层 boolean 真实结果。

`RVP_GunnerSupplyActions` 接管：

- Gunner 首次成为司机时设置 home、启动发动机、补能源和弹药；
- 按原武器装填时间持续补给；
- 失去司机资格后的补给计时清理；
- RVP 武器公开 `ywzj_rvp$setReloadTime` 调用；
- 本体武器唯一一处 `ObfuscationReflectionHelper` 兼容反射。

反射解析失败会安全降级，并通过“仅尝试解析一次”的字段避免每 tick 重复反射和刷警告。

---

## 3. `GunnerBrain` 当前权威顺序

阶段 B 后的服务端主顺序仍为：

```text
tickCooldowns
  -> Profile / 座位 / driver / WeaponUnit 解析
  -> tickTargeting
  -> SupplyActions 首次补满 + 持续补给，或清理补给/显式停车
  -> tickCountermeasure -> DefenseActions / WeaponActions
  -> tickEcmActive -> DefenseActions
  -> tickSmokeEvasion -> DefenseActions
  -> RadarActions 本车锁
  -> RadarActions 外置雷达
  -> GuidanceActions 在途维持
  -> tickSead
       -> WeaponActions AntiRadiation
       -> DefenseActions Chaff
       -> MovementActions SEAD 命令
       -> 接管时跳过普通驾驶/战斗
  -> tickDriving
       -> 现行算法填充 Command
       -> MovementActions 单次提交
  -> tickCombat -> WeaponActions 完整交战事务
```

没有引入第二条并行战术链。目标选择、威胁扫描、SEAD 状态机和移动战术计算暂时仍在 `GunnerBrain`，这是阶段 C/D 的迁移对象，不是阶段 B 未封装的写操作。

---

## 4. 基线测试演化

`RVP_GunnerBehaviorBaselineTest` 从 8 项增加为 9 项，并把已迁移职责的源码断言指向新的动作类：

| 测试 | 阶段 B 后冻结内容 |
|---|---|
| `serverTickPipelineKeepsCurrentAuthoritativeOrder` | `GunnerBrain` 编排顺序及动作网关调用顺序 |
| `weaponEngagementKeepsAimLockGuidanceFireTransactionOrder` | 武器动作内瞄准、锁定、制导、发射、冷却及优先级 |
| `movementKeepsCurrentVehicleDispatchAndControlOutputs` | 各载具算法的 Command 输出与唯一 `reset + apply` |
| `radarGuidanceAndDefenseKeepCurrentSupportSemantics` | 本车/外置雷达、制导、防御的原有时序和入口 |
| `seadKeepsCurrentPreemptionStateMachineAndTiming` | SEAD 时序及统一 AntiRadiation 武器事务 |
| `phaseBActionGatewayOwnsAllMutableCapabilityBoundaries` | 六域网关、Brain 禁止直写、补给反射归属、禁止武器 ID 路径分支 |

其余目标、生命周期和 Profile schema 基线继续保留。阶段 F 之前，`phaseAProfileSchemaAndDefaultsRemainFlatAndExplicit` 仍应阻止提前加入 `behaviors` JSON schema。

针对性结果：

```text
tests=9, skipped=0, failures=0, errors=0
BUILD SUCCESSFUL
```

---

## 5. 验证结果

### 5.1 完整构建

执行：

```powershell
$env:JAVA_HOME='C:\Users\FishKing0721\.jdks\ms-17.0.16'
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=D:\WgameProject'
./gradlew build
```

结果：

```text
BUILD SUCCESSFUL in 40s
17 actionable tasks: 13 executed, 4 up-to-date
```

### 5.2 服务端冒烟

执行 `./gradlew runServer` 后按 10 秒周期读取 `run/server/logs/latest.log`，日志出现：

```text
[17:29:04] [Server thread/INFO] [minecraft/DedicatedServer]: Done (2.236s)! For help, type "help"
```

结论：服务端冒烟通过。确认 `Done` 后已结束测试服务器；结束测试进程产生的 Gradle 退出码不参与启动判定。

本次新增日志错误与阶段 A 基线一致：本体 Bedrock 模型缺失 8 条、`abramsx.structure - 副本.json` 非法路径 4 条、`rvp_bomber:ac130u` 配方解析 1 条、`rvp_bomber:tu160` 载具数据 1 条。未发现 Gunner 动作层相关新增错误。

---

## 6. 阶段 B 的边界与已知限制

- 本体 `WeaponUnit.shoot` 仍无 boolean 返回值，`DISPATCHED` 不能证明弹体已生成。阶段 C 的 `onIntentResult` 必须区分“动作层已提交”与“本体确认发射”；若要取得真实结果，应优先寻找 Forge Post 事件或 Addon 自有可观测记录，不能新增 Mixin。
- RVP 干扰物 `fire` 同样为 void；状态机继续拥有弹量、模块与冷却权威。
- 外置雷达和制导动作当前保留原控制器作为内部实现。后续若移动文件或改名，应先把相同语义接入记录型 fake gateway 测试，不能仅因包结构调整删除基线。
- `GunnerBrain` 仍负责战术计算和状态推进；阶段 B 只建立动作边界，没有完成 Context/Intent/仲裁。
- 世界扫描仍分散，ObservationService 合并属于阶段 E，本轮没有改变扫描周期。
- 未修改任何结构模型、载具包资产、本体源码、Mixin 或 Accessor。

---

## 7. 阶段 C 接手建议

阶段 C 应直接复用本轮网关，建立固定计划管理器，不要重新实现动作：

1. 新建单 tick `RVP_GunnerBehaviorContext`，只读解析 Gunner、载具、司机、WeaponUnit、目标和能力。
2. 新建 Target/Movement/Fire/Radar/Defense 等 Intent 与确定性仲裁；先用代码内固定计划复刻本交接 §3 的顺序。
3. MovementIntent 的执行结果映射为一个 `RVP_GunnerMovementActions.Command`，每 tick 只调用一次 `apply`。
4. 普通攻击、CIWS 和 SEAD 统一生成 FireIntent，胜者调用 `RVP_GunnerWeaponActions`；不要把 `prepareLaunchLock` 与 `shoot` 拆成两个可竞争动作。
5. 行为结果先接受 `DISPATCHED` 的底层限制，在 debug snapshot 中明确记录结果，不把它显示成“确认命中/确认生成弹体”。
6. 先增加记录型 fake gateway 测试，再将 `GunnerEntity.tick()` 切到管理器；Profile 继续保持当前平铺 schema。
7. 保持无 Mixin、无武器/载具 ID 特判、无本体修改。

阶段 C 完成判定应至少包括：固定计划下 9 项现有基线继续通过；同 tick 只有一个最终 MovementIntent 和一个普通 FireIntent 胜者；离座/Profile generation 变化时能显式清理动作租约和运行时状态。
