# RVP Gunner 行为组合重构进度交接

> 最后更新：2026-09-26
>
> 当前状态：阶段 A、B、C、D 已完成；阶段 E～G 尚未实施
>
> 实施范围：仅 `limitless-vehicle-rvp-addon` 的 Java 源码、测试与文档
>
> 未修改：`ywzj_vehicle` 本体、Mixin、Accessor、载具包资产、结构模型、Gunner Profile JSON

---

## 1. 当前结论

阶段 D“按风险从低到高抽取内建行为”已经完成，服务端权威入口现由不可变固定行为计划驱动：

- `GunnerEntity.tick()` 不再直接调用 `GunnerBrain.tick()`，而是进入 `RVP_GunnerBehaviorManager.INSTANCE.tick(...)`。
- `GunnerBrain` 已缩减为发射架能力、司机座位、武器站解析和诊断用武器选择的兼容查询入口，不再包含索敌、支持或战术总编排。
- `RVP_BuiltinGunnerBehaviors` 以 18 个固定行为实例承接普通/CIWS 索敌、补给、雷达、制导、交战、地面/发射架/固定翼/旋翼移动、Smoke、反制、ECM 与 SEAD。
- `RVP_GunnerBehaviorPlan` 保持声明顺序、拒绝重复实例 ID，并按 TARGET、SUPPORT、TACTICS 固定阶段筛选当前能力适用的行为。
- `RVP_IGunnerBehavior` 明确 `onEnter`、`plan`、`onExit` 生命周期；管理器按能力变化、换车、换 Profile 和资源重载进入或退出行为实例。
- 管理器每 tick 构建只读 `RVP_GunnerBehaviorContext`，按 TARGET、支持、移动、战斗、清理阶段执行固定计划。
- TARGET、MOVEMENT、FIRE、RADAR_LOCK、COUNTERMEASURE、ECM、GUIDANCE_MAINTAIN、SUPPLY 和 SYNC 已形成显式意图通道。
- MOVEMENT 与 FIRE 按“优先级 → 固定计划顺序 → 行为实例 ID”确定唯一胜者；不依赖 `HashMap` 遍历顺序。
- 同 tick 没有移动候选时，司机由管理器提交显式停车兜底，避免沿用上一 tick 控制输入。
- 普通攻击、CIWS 和 SEAD AntiRadiation 共用 FIRE/weapon 资源，同 tick 只允许一个胜者进入阶段 B 武器事务。
- Aim 未拆成可独立竞争的写动作：瞄准、锁定准备、制导准备和 `shoot` 仍由一个带 `transactionId` 的 FireIntent 对应到 `RVP_GunnerWeaponActions` 原子事务，避免瞄准 A、发射 B。
- Profile ID、Profile 资源代次或所乘载具变化时，旧计划会先退出并清理；离座路径也显式调用管理器退出入口。
- Profile 仍是现有平铺 schema，没有加入 `behaviors` 字段，也没有旧版 JSON 兼容/迁移分支。

阶段 D 仍保持现有平铺 Profile schema；内建行为已是独立实例，但尚不能由 JSON 增删或重排，注册表与 Profile 编译器属于阶段 F。

原 `RVP_GunnerVehicleTickService` 中独立执行的自动 Flare/Chaff 路径已删除，现由 `rvp_countermeasure` 行为提交 `COUNTERMEASURE` 意图，避免载具服务与行为计划双执行。该行为的节流状态存放在行为 Runtime 中，只有动作层返回 `DISPATCHED` 才推进上次释放时间。

---

## 2. 新增结构

```text
src/main/java/org/ywzj/rvp/entity/gunner/behavior/
├─ api/
│  ├─ RVP_GunnerBehaviorContext.java
│  ├─ RVP_GunnerBehaviorIntent.java
│  ├─ RVP_GunnerBehaviorRuntime.java
│  └─ RVP_GunnerIntentSink.java
├─ debug/
│  └─ RVP_GunnerBehaviorDebugSnapshot.java
└─ runtime/
   ├─ RVP_GunnerBehaviorManager.java
   ├─ RVP_GunnerIntentArbiter.java
   ├─ RVP_IGunnerIntentExecutor.java
   └─ RVP_GunnerActionIntentExecutor.java
```

### 2.1 单 tick Context

`RVP_GunnerBehaviorContext` 在管理器入口创建，只在本 tick 使用，包含：

- Gunner、UUID、Owner UUID、Faction、Team；
- 载具、位置、速度、偏航、俯仰、AGL、损毁状态；
- 座位部件、是否司机、是否允许 AI 驾驶、解析后的 WeaponUnit；
- 已提交目标；
- 雷达锁与导弹发射告警快照；
- 地面/固定翼/旋翼、武器站、雷达、RF 火控、发射架等 capability；
- game time、Profile ID 和 Profile generation。

`withTarget(...)` 只在 TARGET 胜者提交后生成带权威目标的新上下文，其余快照保持不变。跨 tick 运行时不保存这些实体强引用。

### 2.2 Intent 与通道

`RVP_GunnerBehaviorIntent` 携带行为实例 ID、通道与资源键、固定计划顺序、优先级、动作类型、目标/移动命令/干扰物类型、FireIntent `transactionId` 和动作结果回调。

| 通道 | 阶段 C 规则 |
|---|---|
| `TARGET` | 单一权威目标 |
| `SUPPLY` | 每车一个补给事务；首次补满与持续补给合并执行 |
| `COUNTERMEASURE` | 按 `countermeasure:<type>` 资源键去重，可并行不同类型 |
| `ECM` | 每车一次 |
| `RADAR_LOCK` | 本车 `local` 与外置 `external` 分资源仲裁 |
| `GUIDANCE_MAINTAIN` | 当前固定计划每 Gunner 一个操作者维护事务 |
| `MOVEMENT` | 每车单一胜者；无候选时司机显式停车 |
| `FIRE` | `weapon` 资源每 tick 单一胜者 |
| `SYNC` | 当前用于清理 `controlledWeaponIndex` |

动作结果保留阶段 B 的 `EXECUTED`、`DISPATCHED`、`GATED`、`UNSUPPORTED`、`INVALID`、`NOT_DRIVER`，并新增 `OCCUPIED` 表示意图被更高优先级/更早计划候选占用。被仲裁拒绝的意图也会收到结果回调，不会被静默丢弃。

### 2.3 仲裁器与动作执行器

`RVP_GunnerIntentArbiter` 的确定性顺序为：优先级高者；同优先级固定计划靠前者；仍相同则行为实例 ID 字典序靠前者。

`RVP_GunnerActionIntentExecutor` 是生产执行器，只把胜者映射到阶段 B 的 `RVP_GunnerActionGateway`。`RVP_IGunnerIntentExecutor` 是测试替换边界；记录型 fake 不需要构造 Minecraft 世界即可验证胜者数量和顺序。

### 2.4 行为 Runtime 与 Debug Snapshot

`RVP_GunnerBehaviorRuntime` 由每个 `GunnerEntity` 持有：

- `Map<String, Object>` 按行为实例 ID 隔离后续强类型状态；
- 记录当前实际适用的行为实例 ID，支持能力变化时精确调用 `onEnter`/`onExit`；
- 地面接敌/巡逻/脱困、发射架定位、固定翼/旋翼阶段、Smoke、武器反制、RVP 反制和 SEAD 临时状态均归属对应行为实例；
- 使用载具/WeaponUnit 弱引用支持离座和 Profile 切换清理，避免长期强持有实体；
- 记录 Profile ID 与 Profile generation；
- 保存最近一帧 `RVP_GunnerBehaviorDebugSnapshot`。

调试快照包含候选列表、`通道/资源键` 胜者、仲裁拒因和动作层结果。`DISPATCHED` 仍只表示请求已进入本体/RVP 权威链，不表示已确认生成弹体或命中。

---

## 3. 当前服务端固定计划顺序

```text
KERNEL_PREPARE
  -> 解析 Profile / generation / Context capability
  -> 检测换车、换 Profile、资源重载并退出旧计划
  -> tickCooldowns

TARGET
  -> ciws_targeting / primary_targeting
  -> 仲裁 TARGET
  -> RVP_GunnerTargetActions.commit（组网跟踪记账 + trackedTarget 同步）
  -> context.withTarget

EXECUTE_SUPPORT
  -> driver_supply
  -> weapon_countermeasure / rvp_countermeasure / active_ecm / smoke_evasion
  -> ownship_radar / external_radar / guided_weapon_support

PLAN
  -> sead_revenge 提交最高优先级多通道意图
  -> smoke_evasion / stuck_recovery 提交生存移动候选
  -> fixed_wing / rotary_wing / launcher / ground 行为提交普通移动候选
  -> weapon_engagement 提交普通/CIWS FireIntent

EXECUTE_MOVEMENT
  -> 仲裁并应用一个 MovementIntent
  -> 无候选且当前为司机时应用显式 stop Command

EXECUTE_COMBAT
  -> 仲裁并执行一个 FireIntent
  -> 同一武器动作事务内完成 aim / lock / guidance / shoot / cooldown

KERNEL_CLEANUP
  -> controlled weapon 同步清理
  -> 保存 DebugSnapshot
```

SEAD 仍维持原状态机和时序常量。入口 AntiRadiation、复仇 AntiRadiation、Chaff 和 SEAD 飞行动作现在分别提交到 FIRE、COUNTERMEASURE 和 MOVEMENT；只有 AntiRadiation 动作层返回 `DISPATCHED` 时才推进“已发射”状态。SEAD 与普通交战不会再从两条路径同 tick 执行普通开火事务。

Smoke 仍在移动规划前执行：只有 Smoke 动作返回 `DISPATCHED` 才设置 `smokeHoldTicks`，因此同 tick 的地面移动算法能够立即进入烟雾规避分支。

---

## 4. 生命周期与退出清理

`GunnerProfileManager` 新增单调递增的 `generation`，每次资源 Profile 应用完成后加一。管理器发现 Profile ID、Profile generation 或所乘载具变化时，先退出旧计划。

退出路径通过阶段 B 动作层执行：

- 清除司机补给计时与装填覆盖；
- 以空目标清理当前 WeaponUnit 的全部本车 RadarUnit 锁和 root WeaponUnit 锁；
- 清除外置雷达 requested/locked 状态、关联中继锁和搜索接触；
- 按 Gunner UUID 清除 GPS 目标与 SACLOS/designation 会话；
- 清空 tracked target、controlled weapon、司机补给标记和全部行为 Runtime 状态；
- 删除全部行为实例状态与调试快照。

`GPSTargetManager` 为此增加了实体所有者版本的 `clear(Entity)`，没有修改玩家原有 `clear(ServerPlayer)` 语义。

---

## 5. 测试与验证

### 5.1 自动测试

`RVP_GunnerBehaviorBaselineTest` 已更新为阶段 D 固定行为计划断言，当前共 11 项，覆盖战术/动作/Profile/组网基线，以及行为接口、固定计划、生命周期、Runtime 和旧权威路径删除。

新增 `RVP_GunnerIntentArbiterTest` 2 项：

- 两个 MovementIntent 与两个 FireIntent 竞争时，记录型 fake executor 每通道只收到一个胜者；SEAD 高优先级胜出；
- 同优先级时按固定计划顺序，再按行为实例 ID 确定胜者，不依赖输入 Map 顺序。

新增 `RVP_GunnerBehaviorPlanTest` 2 项：

- 保持声明顺序并按阶段/能力筛选行为；
- 重复行为实例 ID 在计划构造时立即拒绝。

定向测试：

```powershell
./gradlew test --tests org.ywzj.rvp.entity.gunner.ai.RVP_GunnerBehaviorBaselineTest `
  --tests org.ywzj.rvp.entity.gunner.behavior.runtime.RVP_GunnerIntentArbiterTest `
  --tests org.ywzj.rvp.entity.gunner.behavior.config.RVP_GunnerBehaviorPlanTest
```

结果：使用 `--rerun-tasks` 强制重跑后 `BUILD SUCCESSFUL in 36s`，7 个 task 全部 executed；共执行上述 15 项 Gunner 测试。

### 5.2 完整构建

执行：

```powershell
$env:JAVA_HOME='C:\Users\FishKing0721\.jdks\ms-17.0.16'
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=D:\WgameProject'
./gradlew build
```

结果：最终复验 `BUILD SUCCESSFUL in 43s`，17 个 task（14 executed，3 up-to-date）。

### 5.3 服务端冒烟

按 10 秒周期轮询 `run/server/logs/latest.log`，出现：

```text
[04:27:31] [Server thread/INFO] [minecraft/DedicatedServer]: Done (3.280s)! For help, type "help"
```

结论：服务端冒烟通过，确认 `Done` 后已结束测试进程。

本次 ERROR 与既有噪音基线一致：本体 Bedrock 模型缺失 8 条、`abramsx.structure - 副本.json` 非法路径 4 条、`rvp_bomber:ac130u` 配方解析 1 条、`rvp_bomber:tu160` 载具数据 1 条。未发现 BehaviorPlan、内建行为、Context、Intent、管理器或退出清理相关新增错误。

---

## 6. 阶段 D 边界与已知限制

- 当前仍是代码内固定计划，不读取 Profile `behaviors`；注册表、强类型行为配置和 JSON 计划编译属于阶段 F。
- burst、导弹发射冷却和 CIWS 目标冷却仍是武器动作事务状态，由 `GunnerEntity` 承载；地面机动、空战、Smoke、反制和 SEAD 等行为状态已迁入按实例 ID 隔离的 Runtime。
- ObservationService 尚未实施；索敌、Smoke、ECM、SEAD 扫描仍按原周期和原入口运行。扫描合并属于阶段 E。
- 本体 `WeaponUnit.shoot` 与 RVP 干扰物 `fire` 仍不返回“实际生成”布尔值，故 `DISPATCHED` 不能解释为确认发射或命中。
- 当前 Aim 始终封装在 FireIntent 对应的武器动作事务内，没有开放可独立竞争的 AimIntent；后续不得把 aim 与 shoot 拆成不同胜者。
- DebugSnapshot 已可从 `gunner.getBehaviorRuntime().debugSnapshot()` 读取，但尚未新增命令/HUD 展示入口。

---

## 7. 阶段 E 接手建议

下一步只实施共享观察与性能回归，不改变阶段 D 行为结果：

1. 合并普通目标、CIWS、Smoke、ECM 与 SEAD 的重复实体扫描；
2. 缓存 Team/Faction、目标载具归一化与 AGL；
3. 为雷达锁来源和在途制导弹药建立共享查询；
4. 对 1/8/16/32 Gunner 场景记录 MSPT 和扫描统计。

接手时应直接复用 Context、BehaviorPlan、现有 Intent 通道、Runtime 和动作网关；ObservationService 只提供只读快照，不得成为第二套决策或写操作入口。

阶段 E 仍不需要修改 `ywzj_vehicle`、新增 Mixin、按武器/载具 ID 特判或改动载具结构模型。
