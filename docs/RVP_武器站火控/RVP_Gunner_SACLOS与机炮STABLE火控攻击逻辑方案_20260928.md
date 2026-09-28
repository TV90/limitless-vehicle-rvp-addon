# RVP Gunner 使用 SACLOS 导弹与机炮的 STABLE 火控方案

> 日期：2026-09-28  
> 状态：已实施（2026-09-28）；Java 编译通过，未运行测试。

## 1. 目标

让 RVP Gunner 在攻击目标时按当前实际武器使用已有 STABLE 火控能力：

- 选中符合 RVP 火控资格的机炮时，服务端 Gunner 直接瞄准弹道解算出的预瞄点，并沿用现有开火窗口。
- 选中符合 SACLOS STABLE PIP 资格的导弹时，维持 Gunner 的目标照射，并允许在途导弹复用现有服务端硬锁 PIP 辅助。
- 对火控配置、传感器、锁定或 SACLOS PIP 条件不满足的载具/武器，保留当前 Gunner 瞄准与 SACLOS 视线制导行为。

本方案假定保留当前武器选择顺序：有可用制导武器时优先使用，无法选择或准备发射时再按现有规则回退机炮；不新增同时齐射导弹与机炮的行为。

## 2. 当前实现与差距

1. `RVP_GunnerWeaponActions.engage` 每次交战先用 `GunnerTargeting.predictAimPoint` 和 Profile 的 `lead_scale` 调整目标点，再选武器。机炮仍按当前位置瞄准后经过现有 `fire_window_deg` 门控。
2. 玩家 STABLE 状态保存在客户端 `RVP_FireControlStabilizerState` 的 `WeakHashMap` 中，服务端 Gunner 不能通过切换这份状态启用 STABLE。
3. 玩家机炮 STABLE 已在 `RVP_BallisticLeadFireControlExecutor` 中使用 `RVP_MachinegunLeadSolver` 与机炮弹道数据跟随预瞄点；`RVP_MachinegunLeadSolver.solveForTarget` 使用双端安全实体数据，已有服务端调用点。
4. SACLOS STABLE PIP 已在服务端制导运行时接入，但 `RVP_SACLOSStablePIPAssist` 要求新鲜的 `stablePIPAssist` 会话、`rvp_rf` + RF、SACLOS、`predict_target_pos`、阶段/顶攻门控及正式雷达硬锁。Gunner 目前写入的照射会话不请求此辅助，且 PIP 辅助当前用玩家当前选中武器判断 SACLOS 资格。
5. Gunner 的目标选择、雷达锁定、发射冷却、目标组网和机炮回退已有独立行为链，本次应只接入瞄准与 STABLE 资格，不改变这些行为语义。

## 3. STABLE 资格口径

资格按当前开火的 RVP 武器和其根火控武器站判断，解析武器级动态传感器覆盖；不把另一无关武器站的配置借给当前武器。

### 3.1 机炮

复用 `RVP_BallisticLeadFireControlPolicy.supportsStabilizer` 的现有规则：

- `rvp_rf` 模式且有效传感器为 RF；或
- `rvp_ballistic_lead` 模式且有效传感器为 IR、EO、RF；
- 当前实际选中武器必须是 RVP `MACHINEGUN`。

满足时 Gunner 自动按 STABLE 处理，不读取或修改玩家客户端模式状态。

### 3.2 SACLOS 导弹

复用 SACLOS PIP 现有严格资格，不扩大为所有 SACLOS 弹：`rvp_rf` + RF、当前在途弹为 SACLOS、`predict_target_pos` 开启、飞行阶段已到 PIP 启用门、非顶攻，并且仍有服务端确认的本车/外置雷达硬锁。任一条件失效时，本 tick 不产生实体 PIP 意图，继续现有 SACLOS 视线制导。

因此，仅有 `rvp_ballistic_lead` 或仅有机炮 STABLE 资格，不会单独让 SACLOS 弹获得 PIP；这时仍可让同一载具的合格机炮使用 STABLE。

## 4. 拟改动步骤

### A. 增加双端安全的 Gunner 火控资格解析

- 从 Gunner 本次将发射的武器索引解析代理后的实际 RVP 武器和类型，避免沿用只看武器站手动当前武器的客户端判定。
- 解析承载该武器的根火控站及有效传感器，按第 3 节规则决定机炮 STABLE 与 SACLOS PIP 资格。
- 保持资格解析只读；不修改载具配置、不增加 JSON 字段。

### B. 在 `RVP_GunnerWeaponActions.engage` 中对机炮启用服务端 STABLE 预瞄

- 将选弹结果提前用于瞄准策略判断。
- 当前选中武器为合格 RVP 机炮时，使用 `RVP_MachinegunLeadSolver.solveForTarget` 和该武器的弹道数据计算世界预瞄点；瞄准原点沿用现有机炮火控使用的枪口/武器站原点口径。
- 解算有效时用预瞄点调用本体 `WeaponUnit.aim`，继续走现有炮塔转动、`fire_window_deg`、burst、弹药和发射门控。
- 解算无结果或武器不具备资格时使用当前 `predictAimPoint + lead_scale` 路径，避免改变旧 Profile 的行为。
- 导弹仍沿用 Gunner 当前的目标瞄准点和开火流程；不把机炮预瞄解算套到导弹上。

### C. 将 Gunner 的 SACLOS 稳定请求接入现有会话

- 在 `GunnerGuidedWeaponController` 维护当前目标照射点时，根据当前/在途 SACLOS 弹及第 3.2 节资格设置 `stablePIPAssist`；Gunner 每 tick 在服务端刷新会话，不依赖客户端按键或网络请求。
- 发射准备、目标切换、离座/清理时沿用现有会话生命周期，避免稳定请求泄漏到其他目标或其他 Gunner。
- 调整 `RVP_SACLOSStablePIPAssist` 的选中武器校验：玩家继续按玩家当前武器校验；Gunner 按服务端 Gunner 控制的武器索引/在途弹来源校验，不能因客户端当前武器状态而误拒绝合法 AI 请求。
- 沿用现有 PIP 硬锁校验和 `canUsePredictiveIntercept` 门控，不将软航迹、目标中心或照射点写入弹体持久 `targetEntity`。

### D. 保持回退边界

- 非 RVP 机炮、未配置火控模式、有效传感器不匹配、无解、无正式硬锁、PIP 尚未启用、顶攻或诱饵/等待期门控不允许时，分别走现有机炮瞄准或 SACLOS 视线制导。
- 不改变 Gunner 目标搜索、阵营过滤、武器优先级、锁定准备、发射冷却、组网和 burst 行为。

## 5. 预计代码落点

| 文件/模块 | 计划职责 |
| --- | --- |
| `entity/gunner/behavior/action/RVP_GunnerWeaponActions.java` | 按实际选中武器应用机炮 STABLE 预瞄，保留原路径回退 |
| `entity/gunner/ai/` 下的双端安全火控资格辅助（具体类名实施时定） | 实际武器、根火控站、有效传感器与 STABLE 规则解析 |
| `entity/gunner/ai/GunnerGuidedWeaponController.java` | 维护 Gunner SACLOS 目标点及稳定 PIP 请求生命周期 |
| `guidance/saclos/RVP_SaclosOperatorSession.java` | 若现有会话接口不足，补充服务端 Gunner 使用稳定请求的写入/清理语义 |
| `guidance/saclos/RVP_SACLOSStablePIPAssist.java` | 保留玩家资格校验，增加 Gunner 权威武器身份校验 |
| 现有 `RVP_MachinegunLeadSolver`、`RVP_BallisticLeadFireControlPolicy` | 复用弹道数学与现有资格规则，避免复制一套物理算法 |

不新增 Mixin，不修改 `ywzj_vehicle` 本体，不新增载具资源、武器 JSON 或 Gunner Profile 字段，不写旧版 JSON 兼容逻辑。当前无需扩展网络协议：Gunner 稳定请求由服务端自身写入会话。

## 6. 验收场景

1. **合格机炮 STABLE**：RVP 机炮 + 支持的火控模式/传感器；Gunner 炮塔跟随弹道预瞄点，并在原有角度窗口满足后开火。
2. **机炮不合格回退**：无火控模式、传感器不匹配、非 RVP 机炮或解算无结果；瞄准结果与当前 Profile 路径一致。
3. **合格 SACLOS PIP**：`rvp_rf` + RF、SACLOS `predict_target_pos`、有效飞行阶段及正式雷达硬锁；在途弹获得已有 PIP 修正，目标/锁定条件变化时回到视线制导。
4. **SACLOS 不合格回退**：无硬锁、只有软航迹、非 RF/`rvp_rf`、预测字段关闭、顶攻或 PIP 门控未到；导弹继续现有视线制导。
5. **选择与生命周期**：目标切换、Gunner 离座、选中机炮以及同时存在在途 SACLOS 弹时，请求只作用于该 Gunner 的有效交战，不影响玩家 SACLOS 会话。
6. **行为回归**：目标选择、导弹优先级、锁定失败后的机炮回退、对空持锁纪律、冷却和 burst 规则保持原样。

## 7. 待审阅假设

- 本方案把“载具支持 STABLE”解释为：当前实际武器所在根火控站满足现有 STABLE 资格，而不是载具任意一个无关武器站满足资格。
- SACLOS 的 STABLE 只复用已经实现的 RF 硬锁 PIP 能力；其它 SACLOS 类型按原视线制导工作。
- Gunner 保留“优先导弹、条件不满足时回退机炮”的现有选择语义，不进行双武器同时攻击。

## 8. 实施记录

- Gunner 按受控索引解析实际 RVP 武器与传感器覆盖；合格机炮通过双端安全弹道解算瞄准预瞄点，解算失败继续使用原 Profile 瞄准点。
- Gunner 对当前或在途合格 SACLOS 导弹刷新服务端 STABLE PIP 请求；在途弹使用自身武器数据和发射武器站验证资格，玩家原有“当前选中 SACLOS 武器”门控保留。
- 新增 `RVP_GunnerFireControlPolicy`，复用现有火控资格策略；未新增 Mixin、Gunner/武器 JSON 字段、网络协议或载具资源。
- 验证：按项目 Java 17 环境运行 `./gradlew compileJava`，`BUILD SUCCESSFUL`；未运行测试或服务端冒烟。
