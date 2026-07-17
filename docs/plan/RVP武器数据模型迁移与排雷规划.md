# RVP 武器数据模型迁移与排雷规划

> 本文只规划武器级数据模型重构，不修改载具级雷达、载具级雷达头瞄或其它载具参数。
>
> `RVP武器数据模型文档.md` 是目标 schema 的只读依据，本文不修改该文档，只补充从当前实现迁往目标模型时必须处理的逻辑、风险和验证步骤。
>
> 本次不兼容旧武器 JSON。配置文件将在代码完成后统一重写；但运行功能原则上不能因为删类或迁字段而无意丢失。

## 1. 本次调整范围

### 1.1 删除并迁入 `RVP_GuidanceData` 的红框类

| 当前类 | 处理方式 | 迁移目标 |
| --- | --- | --- |
| `RVP_GuidanceActivationData` | 删除 | 主制导激活条件并入 `RVP_GuidanceData`；末端接管条件并入 `RVP_TerminalGuidanceData` |
| `RVP_GuidanceSeekerData` | 删除 | 搜索、锁定、跟踪、抗干扰字段并入 `RVP_GuidanceData`；末端专用值并入 `RVP_TerminalGuidanceData` |
| `RVP_GuidanceSourceParamsData` | 删除 | 通用能力并入 `RVP_GuidanceData`；GPS/HITL 专用能力进入对应子类；ARH/ARM 必需参数仍需在新模型保留 |
| `RVP_GuidanceStageData` | 删除 | 当前 `stages[]` 收敛为“主制导 + 可选末端制导”两段状态机 |
| `RVP_GuidanceSteeringData` | 删除 | 转向、刚性段、比例导引等字段并入 `RVP_GuidanceData` 和 `RVP_TerminalGuidanceData` |
| `RVP_HumanInTheLoopData` | 删除 | HITL 字段并入 `RVP_GuidanceDataHITL` |

### 1.2 合并的橙框类

| 当前类 | 处理方式 | 迁移目标 |
| --- | --- | --- |
| `RVP_AheadData` | 删除 | AHEAD 自动编程字段平铺并入 `RVP_FuseData` |

### 1.3 仍在本次模型调整范围内的类

- `RVP_GuidanceData`
- `RVP_GuidanceDataHITL`
- `RVP_GuidanceDataGPS`
- `RVP_TerminalGuidanceData`
- `RVP_FireData`
- `RVP_FuseData`
- `RVP_ProjectileData`
- `RVP_MiscData`

### 1.4 两项硬约束

#### 约束一：默认不新增 Mixin

本次重构的 Mixin 增量预算默认为 **0**。

实施规则：

1. 弹体制导、状态切换、PN、范围过滤和 HITL 会话优先写在 RVP 自有 entity、controller、resolver 和 helper 中。
2. 武器站锁定继续复用现有 `WeaponUnitFireControlLockMixin`、`WeaponUnitTickFireControlMixin`。
3. 火控传感器切换继续复用现有 `WeaponUnitSensorOverrideMixin`。
4. HUD 优先通过现有 Forge overlay/event 和 RVP 客户端 state 实现，不为每个新字段增加独立 Mixin。
5. 不使用 `@Overwrite` 重写本体整段方法，不修改 `ywzj_vehicle` 源码。
6. 只有现有 hook 无法取得必需上下文、普通事件也无法完成时，才允许提出新增 Mixin；提出时必须附调用链证据、目标方法、注入点和影响范围，不能直接落地。
7. 若确需新增，必须是单一职责、窄注入点、可在本体更新后快速验证签名的 Mixin。

“不新增 Mixin”不表示现有 Mixin 不允许调整。现有注入点可以改为调用新的普通 Java helper，但不应继续堆积业务算法。

#### 约束二：保持现有功能行为

本次不兼容旧 JSON，但必须尽量保持玩家可观察到的功能行为。字段位置和配置写法可以变化，以下行为不能因数据模型重构而意外改变：

- IR HMD、无 HMS IR、EO 获取、锁定圈和离轴限制。
- EO_CCIP 的视角切换、持续渲染和 RVP 炸弹落点算法。
- 激光照射开关、LH/SALH 目标点和实体照射。
- TV/HITL 相机、控制输入、链路遮挡和退出流程。
- ARH 初始目标优先、中段更新、pitbull 和自由捕获条件。
- SARH 照射依赖、ARM 预选/记忆/再捕获。
- GPS 目标点、CEP、巡航和末端接管。
- AHEAD 自动编程与普通手动可编程空爆的区分。

实施前先建立 characterization tests 或 debug 基线；每迁完一条功能链立即验证，不等所有红框类删除后再一次性测试。

## 2. 当前实现与实际配置审计

对 live 武器目录进行只读统计后，当前实际情况如下：

| 项目 | 数量 / 结论 |
| --- | --- |
| 使用 `guidance_data` 的武器 | 38 个 |
| 双阶段武器 | 18 个，全部恰好为两个阶段 |
| 双阶段实际结构 | 全部可归约为“主制导 → 末端制导” |
| 使用 `steering_data` | 40 处 |
| 使用 `seeker` | 33 处 |
| 使用 `guide_head_max_angle` | 33 个武器 |
| 使用 `lock_min_height` | 7 个武器 |
| 使用 `active_radar_activation_range` | 12 个武器 |
| 使用 HITL | 4 个武器 |
| 使用 `eo_ccip` | 2 个武器 |
| 使用 AHEAD | 1 个武器 |
| 使用旧“弹体离地高度阶段激活” | 0 个 live 武器 |
| 使用旧“实体距离阶段激活” | 0 个 live 武器 |

因此，live 武器可以迁移为新模型，但不能直接删除红框类。当前大量功能通过这些类的 getter 间接工作，必须先建立新模型的等价读取链，再删除旧类。

另外，现有 `docs/examples/guidance` 和 white-box 测试包含 3～6 阶段、跨阶段复合、source blend 等实验能力。这些能力超过新文档的“主制导 + 末端制导”目标模型。它们不应继续反向约束新 schema，后续应改写为新状态机测试，而不是保留旧阶段系统。

## 3. 主制导与末端制导的正确关系

主制导和末端制导不应同时运行，也不需要做方向混合。正确状态机如下：

```text
MAIN
  ├─ 未满足末端接管条件：只运行主制导
  └─ 满足末端接管条件：本 tick 原子切换到 TERMINAL

TERMINAL
  ├─ 只运行末端制导
  ├─ 主制导保持关闭
  └─ 末端丢失目标时，只执行末端自己的惯性/失效策略，不回退并重启主制导
```

必须遵守以下规则：

1. `terminalGuidance == null` 时，弹药全程只使用主制导。
2. 末端接管是单向状态：`MAIN -> TERMINAL`，默认不允许返回 `MAIN`。
3. 接管 tick 内只能调用一套制导算法，不能先算主制导再算末端制导。
4. `guidanceStartTick`、`guidanceStartDist`、`guidanceStartHorizontalDist` 中配置的条件采用 AND：所有已配置条件均满足后才接管。
5. `terminalGuidance` 非空但三个接管条件全部为空时，应在加载期报配置错误或禁用末端制导，不能静默地在 tick 0 接管。
6. 旧 `enter_once` 不再需要公开字段。末端状态一旦进入便天然保持，相当于永久 `enter_once`。
7. 末端制导丢失目标后，如启用 `enableInertialGuidance`，只朝末端最后记忆点继续飞行；它不是恢复 GPS/SARH 等主制导。

因此，所谓“冲突风险”不应描述成主、末制导逻辑本身会同时生效，而应描述成：如果迁移时仍沿用当前 `stages + compositor` 调度器，就可能错误地在切换 tick 同时执行两段。新实现应直接移除这种可能性。

## 4. 新运行时应保留的统一接口

删除数据类不等于让各功能直接读取零散字段。建议保留一个内部只读快照，例如 `RVP_GuidanceRuntimeConfig`，但它不是新的 JSON data 类，只是运行时解析结果。

快照至少应提供：

- 当前状态：`MAIN` 或 `TERMINAL`
- 当前 `guidanceType`
- 搜索参数
- 锁定保持参数
- 转向参数
- 惯性制导参数
- 制导方式专用参数
- 当前状态进入 tick

所有飞行算法、HUD、发射前锁定和 seeker 扫描应通过明确的 resolver 获取数据：

| 使用场景 | 建议接口 | 数据来源 |
| --- | --- | --- |
| 发射前锁定 / HUD | `resolveLaunchGuidance()` | 只读取主制导 |
| 飞行中制导 | `resolveActiveGuidance(projectile)` | 根据 `MAIN/TERMINAL` 状态读取当前段 |
| 判断武器能力 | `hasGuidanceType(type)` | 检查主制导和末端制导 |
| 判断发射前 IR 武器 | `isLaunchIrGuided()` | 只检查主制导，不能因为末端为 IR/AIR 就要求发射前 IR 锁定 |
| 判断当前飞行类型 | `getActiveGuidanceType(projectile)` | 只返回当前段 |

这是迁移中最重要的隔离层。否则 `usesGuidanceType(IR)` 之类的“全武器能力判断”会继续被误用于发射前 UI，导致 GPS + 末端 IR 武器被当成纯 IR 弹处理。

### 4.1 第一步先实现 `RVP_Range`

代码重构的第一步只新增 `RVP_Range<T extends Comparable<T>>` 及其 JSON 解析，不立即改动制导算法。该类型是后续所有距离、高度、时间和角度门迁移的基础。

实现要求：

1. 内部使用不可变、已排序、已合并的闭区间列表，构造后不能被配置对象修改。
2. 支持单区间和多个区间并集。
3. 相交区间和首尾相接区间在加载时规范化，避免同一数值命中多段。
4. 提供 `contains(T value)`，所有运行时范围判断统一调用该方法。
5. `[[inf,100]]` 中左侧 `inf` 解析为负无穷；`[[100,inf]]` 中右侧 `inf` 解析为正无穷。
6. 出现 `[[100,10]]`、空区间、非数字端点或不支持的泛型类型时抛出 `JsonParseException`。
7. 为 `RVP_Range<Integer>` 和 `RVP_Range<Float>` 提供 Gson adapter。
8. `Map<RVP_Range<Float>, RVP_Range<Float>>` 需要支持 JSON object key 形式，不能依赖默认 `toString()` 猜测解析。
9. `equals/hashCode/toString` 基于规范化后的区间，保证测试、日志和 map key 行为一致。

第一阶段测试只验证数据结构和 JSON，不接入现有武器。确认区间、无穷边界和 map key 全部稳定后，再开始改 `RVP_GuidanceData`。

## 5. 红框类字段迁移

### 5.1 `RVP_GuidanceStageData`

| 当前字段 | 新位置 / 处理 | 注意事项 |
| --- | --- | --- |
| `name` | 不再作为 JSON 必需字段 | 运行时使用 `MAIN` / `TERMINAL` 枚举记录状态；debug 可直接输出状态名 |
| `activation` | 主段范围并入 `RVP_GuidanceData`；末段触发并入 `RVP_TerminalGuidanceData` | 不再建立阶段数组 |
| `seeker` | 并入对应段的数据模型 | 发射前和飞行中不能共用错误的 resolver |
| `steering_data` | 拆入 Guidance 与 Projectile 两侧 | `max_degree_of_missile` 迁为 `maxGuidanceAngle`；旧方向插值交给 `ProjectileData.turningFactor`；旧提前量预测删除，仅保留 PN |
| `sources` | 删除 | 主 source 变为单一 `guidanceType`；IOG fallback 变为 `enableInertialGuidance` |
| `composite_weight` | 删除 | 新模型不再支持跨阶段同时复合 |

live 配置中的 source 归约规则：

| 当前组合 | 新模型 |
| --- | --- |
| `IOG` | `guidanceType: NONE` 或对应主制导 + `enableInertialGuidance: true`，具体取决于该段是否只有刚性飞行 |
| `GPS + IOG` | `guidanceType: GPS` + `enableInertialGuidance: true` |
| `ARH + IOG` | `guidanceType: ARH` + `enableInertialGuidance: true` |
| `IR + IOG` | 发射前有锁定要求时用 `IR`；自主搜索时用 `AIR`；并设置 `enableInertialGuidance: true` |
| `IR + GPS + IOG` 末段 | 末段改为 `AIR` 或 `IR`；丢目标后使用末段记忆点，不重新启用主 GPS |
| `SACLOS + IOG` | 先按真实语义改名为 `SALH` / `HITL_TV` / `HITL_CLOS_TV`，再使用 `enableInertialGuidance` |

### 5.2 `RVP_GuidanceActivationData`

| 当前字段 | 新字段 | 迁移规则 |
| --- | --- | --- |
| `start_tick` + `end_tick` | `guidanceTickRange` | 转为闭区间；无结束值时上界为正无穷 |
| `min_target_distance` + `max_target_distance` | `guidanceTargetDistanceRange` | 主段有效范围使用该字段；末端“开始接管”优先使用 `guidanceStartDist` |
| `min_entity_distance` + `max_entity_distance` | 不直接保留 | live 配置未使用；新制导类型若必须有实体目标，由制导类型语义保证 |
| `min_altitude_agl` + `max_altitude_agl` | 不可直接映射到 `guidanceAltitudeRange` | 旧字段检查弹体自身 AGL，新文档字段检查目标/记忆点 AGL，语义不同；live 配置未使用，可删除旧能力 |
| `require_target` | 制导类型隐含规则 | GPS/LH 等要求坐标，IR/SARH/ARH 要求实体或相应信号；不再暴露重复开关 |
| `require_entity_target` | 制导类型隐含规则 | 不应再与制导类型形成矛盾配置 |
| `require_illumination` | `SARH` / `SALH` 语义内置 | 照射中断后的行为由惯性制导字段决定 |
| `enter_once` | 删除 | 末端接管天然为单向保持 |

`guidanceTargetDistanceRange` 的距离基准必须统一：

1. 有有效实体目标时，测量弹体到实体中心的距离。
2. 无实体但有目标点时，测量弹体到目标点的距离。
3. 只有记忆点时，测量弹体到最后记忆点的距离。
4. 三者均不存在时，该范围条件不满足。

### 5.3 `RVP_GuidanceSeekerData`

当前 `seeker.fov/range` 同时被发射前搜索和发射后跟踪读取。新模型把这两个阶段拆开后，迁移必须同时填充两侧，否则会出现“圈能套上但锁不上”“发射后立刻丢目标”或“雷达锁定绕过射程”等问题。

| 当前字段 | 新字段 | 迁移规则 |
| --- | --- | --- |
| `fov` | `maxLockAngle` | 作为发射前 / 未捕获时的搜索全角 |
| `range` | `lockTargetDistanceRange` | 作为发射前火控锁定距离范围 |
| `range` | `guidanceTargetDistanceRange` | 同时复制为发射后跟踪距离范围，之后配置作者可分别调节 |
| `guide_head_max_angle` | `maxOffAxisLockAngle` | 发射前已锁定目标的离轴保活与 IR HMD 机械限位 |
| `guide_head_max_angle` | `maxGuidanceAngle` | 为保持当前行为，发射后 IR 跟踪角也先复制该值 |
| `scan_interval_tick` | `scanIntervalTick` | 发射后自主 seeker 扫描周期 |
| `lock_min_height` | `lockAltitudeRange` + `guidanceAltitudeRange` | 当前一个字段同时参与发射前和发射后过滤，应复制到两侧 |
| `ignore_flares` | `RVP_GuidanceData.ignoreFlares` | 新文档当前未列出，但 live 有 7 个武器使用，不能先删 |
| `ignore_chaff` | `RVP_GuidanceData.ignoreChaff` | 保留雷达 seeker 抗诱饵能力 |
| `jam_resistance` | `RVP_GuidanceData.jamResistance` | 保留抗干扰链 |
| `dircm_resistance` | `RVP_GuidanceData.dircmResistance` | 保留 DIRCM 判定 |
| `home_on_jam` | `RVP_GuidanceData.homeOnJam` | 保留干扰源归向 |
| `decoy_filter` | `RVP_GuidanceData.decoyFilter` | 保留诱饵过滤 |

`lock_min_height` 转换规则：

| 旧值 | 新范围 |
| --- | --- |
| `N > 0` | `[[N,inf]]`，只允许高于该 AGL 的目标 |
| `N < 0` | `[[inf,abs(N)]]`，只允许低于该 AGL 的目标 |
| `0` | `null`，不限制 |

对于并集范围，不能继续靠一个正负数直接决定整个 IR HUD。范围过滤与 HUD 表现应彻底分离：

- `lockAltitudeRange` 只回答“这个目标能否在发射前被捕获”。
- `guidanceAltitudeRange` 只回答“弹药发射后能否继续跟踪这个目标”。
- HUD profile 由 `lockAltitudeRange` 的区间拓扑和当前候选/锁定目标动态推导，不反向修改搜索结果。

建议增加内部枚举 `RVP_IrHudProfile`，它不是新的 JSON data 类：

| profile | 判定 | 未锁定时表现 |
| --- | --- | --- |
| `AIR` | 范围只有向正无穷延伸的高空分支，如 `[[5,inf]]` | 保持原空对空 IR HMD 样式 |
| `GROUND` | 范围只有从负无穷开始的近地分支，如 `[[inf,25]]` | 保持原空对地 IR HMD 样式 |
| `MIXED` | 同时含近地和高空分支，或为无法自动归类的有限高度区间 | 使用中性搜索圈；捕获候选后按候选所在分支切换标记样式 |
| `UNRESTRICTED` | `null` 或覆盖全部高度 | 使用当前通用/空对空搜索圈，保持旧 `lock_min_height=0` 的显示习惯 |

并集示例：

```json
"lock_altitude_range": "[[inf,10],[30,inf]]"
```

其行为为：

1. AGL `<=10` 的目标可锁定，并使用空对地目标标记。
2. AGL `>=30` 的目标可锁定，并使用空对空目标标记。
3. AGL `10～30` 的目标不可锁定。
4. 没有候选目标时显示中性混合搜索圈，不同时绘制两套互相覆盖的 HUD。
5. 候选或锁定目标同时命中多个重叠区间时使用中性目标标记，不能靠区间声明顺序决定样式。

旧字段迁移后保持原行为：

| 旧 `lock_min_height` | 新范围 | 自动 profile |
| --- | --- | --- |
| 正值 `N` | `[[N,inf]]` | `AIR` |
| 负值 `-N` | `[[inf,N]]` | `GROUND` |
| `0` | `null` | `UNRESTRICTED` |

只有未来出现配置作者确实需要强制 HUD 样式、且自动分类无法表达的武器时，再考虑增加可选 `irHudMode` override；本轮先不增加该 JSON 字段。

### 5.4 `RVP_GuidanceSteeringData`

新模型不再保留一套独立的“制导转向插值参数”，而是让制导算法只计算期望方向，再由 `RVP_ProjectileData.turningFactor` 和弹体物理限制实际机动。因此旧字段按以下方式处理：

| 当前字段 | 新位置 / 处理 | 迁移要求 |
| --- | --- | --- |
| `rigidity_time` | 折叠进 `guidanceTickRange` / `guidanceStartTick` | 主制导把启用 tick 下界后移；末端制导把接管 tick 后移。live 末段 rigidity 配置都同时具有 start tick，可以在重写 JSON 时直接相加，不再保留独立字段 |
| `turning_factor` | `RVP_ProjectileData.turningFactor` | 不是简单移动 getter，而是删除 `RVP_GuidanceMath` 中的速度插值转向，让弹体过载模型统一负责转向能力 |
| `max_degree_of_missile` | `maxGuidanceAngle` | 当前代码实际将其用于“目标方向是否超过制导允许角”的门控，可由 `maxGuidanceAngle` 等价替代 |
| `predict_target_pos` | 删除旧“预测拦截点”语义 | 不再调用 `RVP_InterceptSolver`，新字段 `predictTargetPos` 只表示启用比例制导 |
| `use_proportional_navigation` | 合并进 `predictTargetPos` | 删除独立 PN 开关，避免两个 boolean 组合出矛盾状态 |
| `proportional_navigation_gain` | 删除配置字段 | PN gain 改为代码内统一常量或统一算法参数，不再由武器 JSON 单独配置 |
| `max_lateral_accel` | 由 `RVP_ProjectileData.turningFactor` 约束 | 不再维护第二套横向机动上限 |
| `tick_end_homing` | 删除 | live 武器未使用；若未来需要寿命末段制导，应作为新的明确功能重新设计 |
| `terminal_dive_angle` | 由 `topAttackHeight` 对应的新攻顶算法替代 | 不能继续保留旧俯冲角算法和新攻顶高度算法并行执行 |

纯比例制导迁移要求：

1. 删除 `RVP_InterceptSolver` 在制导链中的调用和“按弹速预测目标位置”的分支。
2. `predictTargetPos=false`：使用普通追踪方向，不计算 PN 修正。
3. `predictTargetPos=true`：只执行比例制导，不再先算提前拦截点后再叠加 PN。
4. PN 输出的是期望转向方向或横向修正，最终可实现机动量由 `RVP_ProjectileData.turningFactor` 限制。
5. 主制导和末端制导分别读取自己的 `predictTargetPos`，末端接管后不继续读取主段 PN 状态。

`rigidity_time` 的折叠规则：

- 主段：`guidanceTickRange` 的开始 tick = 旧阶段开始 tick + `rigidity_time`。
- 末段：`guidanceStartTick` = 旧末段开始 tick + `rigidity_time`。
- `ignitionDelayTick` 仍只控制发动机点火，不承担 rigidity 语义。
- 新模型不再支持“仅由距离触发末段后，再按进入时刻等待 N tick”的相对 rigidity；若未来确有需求，再单独增加字段。

因此，`turningFactor` 不再是缺失字段，但它对应的是一次算法 ownership 转移：从 `RVP_GuidanceSteeringData` 的方向插值迁到 `RVP_ProjectileData` 的弹体机动模型。只改数据位置、不改 `RVP_GuidanceMath` 会造成双重转向或继续读取已删除字段。

### 5.5 `RVP_GuidanceSourceParamsData`

| 当前字段 | 新位置 / 处理 | 说明 |
| --- | --- | --- |
| `use_target_pos` | 删除，按制导类型内置 | GPS/LH/SALH 等自然使用目标点 |
| `use_last_guidance` | `enableInertialGuidance` | 丢失实时目标后使用最后记忆点 |
| `use_launch_heading` | 删除或继续预留 | 当前运行时未接入，不应伪装成已实现功能 |
| `vehicle_only` | 可删除 | 当前 seeker 实体扫描本身只接受 `AbstractVehicle`；若未来要扫普通实体，应另设明确目标类型字段 |
| `reacquire` | 由 `IR/AIR` 等类型区分 | `IR` 表示依赖既有锁定，`AIR` 表示允许弹载 seeker 自主搜索/再捕获 |
| `memory_tick` | ARM 专用值需保留 | 普通 IOG 当前并未实际消费该字段；ARM 的辐射记忆确实在使用 |
| `require_illumination` | SARH/SALH 类型内置 | 不再允许写出“类型要求照射但字段关闭”的矛盾状态 |
| `break_on_smoke` | 删除或继续预留 | 当前运行时未接入 |
| `use_weapon_unit_aim` / `use_owner_look` | 由 MCLOS/SACLOS/HITL 类型内置 | 不再通过两个 boolean 拼出控制模式 |
| `scan_interval_tick` | `scanIntervalTick` | ARM 辐射源扫描与主动 seeker 扫描统一字段名，但算法按类型解释 |
| `radiation_pulse_memory_tick` | `radiationPulseMemoryTick` | ARM 必需，不能删除 |
| `locked_bonus` | `armLockedEmitterBonus` | ARM 辐射源评分必需 |
| `active_radar_activation_range` | `activeRadarActivationRange` | ARH 必需，不能用普通 seeker range 或末端接管距离替代 |

`activeRadarActivationRange` 必须保留为独立字段：

- `guidanceStartDist` 决定何时从主制导切到末端制导。
- `activeRadarActivationRange` 决定 ARH seeker 何时开机进入 pitbull。
- ARH 在 seeker 开机前仍可接收载机/外置雷达提供的指定目标和中段更新。
- 两个距离若合并，主动弹会表现成发射即开机、提前自由捕获其它目标，或开机前完全失去雷达引导。

当前有若干 JSON 把 `fallback_on_jammed` 错写进 `params`；现代码只读取 source 顶层的该字段，因此这些写法本来就是无效配置，不应当作现成功能迁移。有效的 fallback 语义应收敛为 `enableInertialGuidance` 和各制导类型自己的目标丢失策略。

### 5.6 `RVP_HumanInTheLoopData`

HITL 不应再通过 `enabled + control_mode + source type` 三套字段拼装，而应由 `guidanceType` 直接确定状态机：

| 当前组合 | 新 `guidanceType` |
| --- | --- |
| `human_in_the_loop.enabled=true` + `MOUSE` + MCLOS | `HITL_CLOS_TV` |
| `human_in_the_loop.enabled=true` + `DESIGNATE` + 当前 SACLOS | `HITL_TV` |
| 仅弹载图像观察、发射后不允许人工修正 | `TV` |

字段迁移：

| 当前字段 | `RVP_GuidanceDataHITL` 新字段 |
| --- | --- |
| `signal_source` | `signalSource` |
| `control_range` | `hITLMaxControlDist` |
| `timeout_tick` | `hITLMaxControlTick` |
| `max_look_offset_deg` | `hITLMaxLookOffset` |
| `video_modes` | `hITLVideoModes` |
| `max_turn_deg_per_tick` | `hITLMaxTurnDegPerTick` |
| `enabled` | 删除，由具体 HITL guidance type 隐含 |
| `control_mode` | 删除，由 `HITL_TV` / `HITL_CLOS_TV` 隐含 |

`hITLMaxLookOffset` 和 `hITLMaxTurnDegPerTick` 不是同一个参数：

- 前者限制 DESIGNATE 模式视线相对弹轴的最大偏移。
- 后者限制 MOUSE 驾控时弹体每 tick 的最大转向速度。
- 两者已在目标文档中分别保留，迁移时只需确保客户端输入、网络同步和服务端转向分别读取正确字段。

迁移时还必须保留以下非数据状态：

- 一次只控制最后发射的 HITL 弹药。
- 服务端进入/退出弹载视角同步。
- RADIO 模式方块遮挡、临时雪花屏和连续 40 tick 后永久断链。
- FIBER 模式不走无线电遮挡判定。
- 弹药爆炸、失效、超时、玩家死亡或离开后强制退出视角。
- `MOUSE` 与 `DESIGNATE` 两套网络输入不能串用。

### 5.7 制导类型迁移、重构与缺失实现

当前 enum 名称不能代表真实算法。必须先按运行行为重新分类，再替换 source 和数据模型。

| 目标类型 | 当前实现状态 | 当前实际对应代码 | 处理方式 |
| --- | --- | --- | --- |
| `NONE` | 已实现 | `RVP_NoneGuidanceSource` | 直接迁移；无目标修正 |
| `MCLOS` | **未实现真正 MCLOS** | 当前没有独立键盘舵面输入；现 `RVP_MclosGuidanceSource` 读取武器站瞄准方向或 HITL 鼠标 | 新写键盘上下左右舵面输入、网络同步和弹体积分；不能继续沿用当前同名 source |
| `SACLOS` | 部分核心可复用 | 当前 `RVP_MclosGuidanceSource` 的非 HITL 分支会跟随武器站/操作员瞄准线，行为更接近 SACLOS command guidance | 将非 HITL 的 operator aim 路径迁成真正 `SACLOS`；导弹根据操作员 LOS 自动修正，不要求玩家直接观察并手控弹体舵面 |
| `LH` | 已有主要逻辑但名称错误 | 当前 `RVP_SaclosGuidanceSource` 的“读取激光点并飞向坐标”分支 | 拆成 point-only 激光寻的；不自动绑定实体 |
| `SALH` | 已有部分逻辑但名称错误 | 当前 `RVP_SaclosGuidanceSource` + `RVP_SaclosOperatorSession` + 激光吊舱实体照射链 | 重命名并重构为实体/激光点半主动激光寻的；照射中断后按惯性策略处理 |
| `LBR` | **定义不完整、未实现** | 无独立 source；目标文档附录也未解释它与 `LOSBR` 的差异 | 实施前先明确是否保留；若与 `LOSBR` 同义，应删除一个 enum，避免两套重复类型 |
| `LOSBR` | **未实现** | 当前 operator aim 和 direct-motion 数学可复用一部分，但没有波束轴偏差、束内位置和驾束接收器模型 | 新写 beam-axis guidance；不能简单把当前 `MCLOS` 改名为 `LOSBR` |
| `TV` | **未实现独立 fire-and-forget TV seeker** | 当前只有 HITL 相机与人工输入，没有独立 TV source | 新写发射前图像/实体目标捕获、发射后自主保持；通信中断只影响图像回传，不应自动丢失弹载目标 |
| `HITL_TV` | 基本实现 | 当前 HITL `DESIGNATE` + `RVP_SaclosGuidanceSource` 的弹载重新指定分支 | 从错误的 SACLOS source 中拆出，保留相机、重新指定、链路和退出状态机 |
| `HITL_CLOS_TV` | 基本实现 | 当前 HITL `MOUSE` + `RVP_MclosGuidanceSource` + `RVP_WireGuidanceSteering` | 从错误的 MCLOS source 中拆出，保留鼠标指令与 `hITLMaxTurnDegPerTick` 限速 |
| `ATV` | **未实现** | 无主动电视目标识别 source | 新写自主图像目标搜索/评分/捕获；在没有可靠图像判定前不能用普通实体最近距离扫描冒充 |
| `IR` | 已实现但混入 AIR 行为 | 当前 `RVP_IrGuidanceSource` 既维持预锁目标，也会在无目标时自主扫描 | 拆成“依赖发射前锁定、发射后维持目标”的 IR；不得无规则自由捕获其它目标 |
| `AIR` | 未独立建模，但算法可拆出 | 当前 IR source 的无目标扫描和再捕获分支 | 抽成主动红外 source；按 `activeRadarActivationRange`/`scanIntervalTick` 开机并自主捕获 |
| `SARH` | 已实现 | `RVP_SarhGuidanceSource` + 平台雷达照射目标 | 直接迁移并移除 source params；照射要求由类型固定 |
| `ARH` | 已实现，状态分散 | `RVP_ArhGuidanceSource` + `RVP_MissileEntity` 的 designated target/pitbull 管理 | 合并读取新 `RVP_GuidanceData`，保留开机前中段更新和初始目标优先级 |
| `GPS` | 已实现 | `RVP_GpsGuidanceSource` + GPS 目标点/CEP/巡航逻辑 | 迁入 `RVP_GuidanceDataGPS`，IOG source 改为 `enableInertialGuidance` |
| `ARM` | 已实现，状态分散 | `RVP_ArmGuidanceSource` + `RVP_MissileEntity.initArmParams/tickArmGuidance` | 合并参数读取和状态 ownership，保留 PDW、预选、记忆与再捕获 |

`IOG` 不再作为公开 `guidanceType`。当前所有 IOG source 按以下规则吸收：

- 主/末段存在其它制导类型：转成该段的 `enableInertialGuidance=true`。
- 只有 IOG 的刚性飞行段：由 `guidanceTickRange`、`rigidityTick` 或无制导飞行状态表达，不再保留独立 source。

当前 `SACLOS` 的具体拆分：

1. 普通载具激光点分支迁到 `LH`。
2. 吊舱持续绑定实体、更新照射点的分支迁到 `SALH`。
3. HITL 弹载重新指定分支迁到 `HITL_TV`。
4. 当前 `MCLOS` 的非 HITL operator aim 分支迁成真正 `SACLOS` 的基础。
5. 当前 `MCLOS` 的 HITL MOUSE 分支迁到 `HITL_CLOS_TV`。
6. 新 `MCLOS` 另写键盘舵面控制，不能从当前同名实现直接继承语义。

### 5.8 `RVP_GuidanceData` 子类反序列化方案

当前 `RVP_WeaponData.guidanceData` 的声明类型是 `RVP_GuidanceData`，而加载器直接调用 `GsonUtil.GSON.fromJson(..., RVP_WeaponData.class)`。Gson 不会根据 `guidance_type` 自动创建 `RVP_GuidanceDataGPS` 或 `RVP_GuidanceDataHITL`。

建议使用字段级自定义 adapter，不增加 Mixin：

1. 在 `RVP_WeaponData.guidanceData` 上使用 `@JsonAdapter(RVP_GuidanceDataAdapter.class)`。
2. adapter 先读取 `guidance_type`，统一大小写并解析 enum。
3. `GPS` 反序列化为 `RVP_GuidanceDataGPS`。
4. `TV/HITL_TV/HITL_CLOS_TV` 反序列化为 `RVP_GuidanceDataHITL`。
5. 其它类型反序列化为基础 `RVP_GuidanceData`。
6. 子类与 `guidance_type` 不匹配时抛出 `JsonParseException`，不能静默忽略子类字段。
7. `terminalGuidance` 暂时只使用 `RVP_TerminalGuidanceData`，不参与该多态 adapter。

adapter 必须避免递归调用自身。应将 `@JsonAdapter` 标在 `RVP_WeaponData` 字段上，而不是直接标在 `RVP_GuidanceData` 基类上；adapter 内再使用普通 Gson/context 反序列化具体子类。

## 6. 红外弹头瞄迁移

### 6.1 字段位置

当前武器顶层 `enable_hms` 应迁到 `RVP_GuidanceData.enableIRHMD`。这里只处理武器级 IR HMD，不涉及载具级雷达 HMD。

删除顶层字段后，必须同步修改以下武器级消费者：

- `RVP_ClientHmdState`
- `RVP_MissileOverlay`
- `RVP_IrLockHelper`
- `RVP_WeaponBase` 发射门控
- `WeaponUnitFireControlLockMixin`
- `WeaponUnitTickFireControlMixin`
- 战术地图中的武器 seeker 锁定限制

### 6.2 无 HMS 红外弹

`enableIRHMD=false` 只表示不允许导引头圈跟随玩家视线离轴，不表示导弹失去 seeker，也不表示只能正前方零度锁定。

正确流程：

1. 小圈固定在武器/机头轴线中心。
2. 未锁定扫描使用 `maxLockAngle` 全角和 `lockTargetDistanceRange`。
3. 目标进入搜索 FOV 后允许捕获。
4. 锁定后使用 `maxOffAxisLockAngle` 保活。
5. 发射后使用 `maxGuidanceAngle` 和 `guidanceTargetDistanceRange` 跟踪。
6. 当火控传感器为 EO 时，仍允许无 HMS IR 武器走 EO 获取链。

因此，删除 `enable_hms` 后不能把 `usesIrAcquireOnEo()` 一并删掉；它应改为读取 `guidanceData.enableIRHMD`。

角度单位必须在 schema 和代码注释中固定：

| 字段 | 定义 | 运行时处理 |
| --- | --- | --- |
| `maxLockAngle` | 完整搜索 FOV，例如 `60` 表示轴线左右各 30 度 | 扫描时转换为 `maxLockAngle / 2` |
| `maxGuidanceAngle` | 发射后相对弹体轴线的单侧最大跟踪角 | 直接与目标夹角比较，不除以二 |
| `maxOffAxisLockAngle` | 发射前锁定保持和 IR HMD 的单侧机械离轴极限 | 直接钳制或比较，不除以二 |
| `lockAngleGate` / `guidanceAngleGate` | 目标运动方向与瞄准线/导引头方向的实际夹角区间 | 按 0～180 度真实夹角直接查询 `RVP_Range` |

禁止再提供一个不说明全角/半角的通用 `getFov()`。launch scan、锁定保持和飞行跟踪必须使用三个不同的 getter。

### 6.3 发射前与末端 IR 必须分开

以下判断必须只看主制导：

- 是否显示发射前 IR HMD。
- 是否需要发射前 IR 实体锁定。
- 是否从 WeaponUnit/EO 获取发射目标。

GPS + 末端 AIR/IR 武器不能因为 `terminalGuidance.guidanceType` 是 IR 类，就在发射前被提示“导引头需要锁定目标”。

## 7. EO / EO_CCIP 排雷

`rvp_fire_control_sensor_mode: eo_ccip` 和 `fire_control_sensor_type_override` 当前仍是武器级火控 UI 路由字段，不属于被删除的红框类。本次不应顺手删除或改成只根据 guidance type 推断。

必须保持以下行为：

- 非观瞄视角：WeaponUnit 传感器返回 `CCIP`，持续更新并绘制 RVP 弹道落点。
- 观瞄视角：WeaponUnit 传感器返回 `EO`，允许激光/IR 获取与观瞄 UI。
- 切换武器后不能只在第一 tick 写入 CCIP 状态。
- CCIP 计算继续使用 `RVP_ProjectileData` 的速度、重力、阻力和载具速度继承，不能换回本体炸弹公式。
- `usesGuidanceType(GPS)` 改造后必须检查主、末两段能力；但是否显示 CCIP 仍优先由 `eo_ccip` 模式决定。

尤其要避免使用“当前激活飞行段”决定载具 HUD。尚未发射时没有 projectile 状态，HUD 必须使用武器的 launch config 和火控模式。

## 8. ARH / SARH / ARM 迁移风险

### 8.1 ARH

ARH 至少有三个独立状态，不能只用一个 `guidanceType: ARH` boolean 代替：

1. 已接收载机/外置雷达指定目标。
2. seeker 尚未开机，继续接受中段目标更新或朝记忆点飞行。
3. 进入 `activeRadarActivationRange` 后 seeker 开机并捕获指定目标。
4. 只有指定目标失效且允许自由捕获时，才选择其它目标。

迁移必须保留“初始指定目标优先级”。否则会恢复成主动弹开机后攻击最近 B 目标、放弃雷达原先指定 A 目标的问题。

目标文档已经给 `RVP_TerminalGuidanceData` 补充了 `maxLockAngle` 和 `guidanceTargetDistanceRange`。其“主动类导引头开机距离”一行目前字段名仍为空，实施前应明确写为 `activeRadarActivationRange`。当 ARH/AIR/ARM 位于末端段时，必须读取末端自己的该字段，不能误读主制导值。

### 8.2 SARH

`require_illumination` 从配置字段移除后，必须成为 SARH 的固定类型语义：

- 有有效平台照射：更新实体目标。
- 暂时失照且启用惯性：飞向最后记忆点。
- 未启用惯性：停止转向或按失效策略处理。
- 不能因为 `require_target` 字段消失而在无照射时继续读取旧实体目标。

### 8.3 ARM

ARM 当前不完全依赖通用 stage controller，还在 `RVP_MissileEntity` 内初始化和维护专用状态。删除 `RVP_GuidanceSourceParamsData` 时必须同步迁移：

- `scanIntervalTick`
- `radiationPulseMemoryTick`
- ARM 目标记忆 tick
- `armLockedEmitterBonus`
- 是否允许重新捕获的类型策略
- 发射前预选辐射源 ID / radar index
- 预选目标坐标的初始惯性记忆

不能只让 `RVP_GuidanceData.guidanceType == ARM` 成立，而忘记 `RVP_MissileEntity.initArmParams()` 当前从 source params 复制运行值的过程。

## 9. GPS 与末端接管

### 9.1 GPS 专用字段

`gps_cep` 应迁到 `RVP_GuidanceDataGPS.gpsSpreadRadius`。GPS 巡航相关字段当前位于 `RVP_ProjectileData`，本次可暂时保留，但 resolver 必须只在当前激活段为 GPS 时启用巡航修正。

### 9.2 GPS + 末端 IR/AIR

当前 `GB-1000` / `SPICE-1000` 使用 `GPS + IOG -> IR + GPS + IOG`。新状态机建议迁为：

```text
MAIN: GPS + enableInertialGuidance
  ↓ 达到 terminal guidanceStartDist
TERMINAL: AIR + enableInertialGuidance
```

末端 AIR 丢失实体时：

- 继续朝末端最后记忆点飞行。
- 不重新调用主 GPS 算法。
- 不把主 GPS 状态重新标记为 active。

这样既满足“末端接管后主制导关闭”，也保留原配置中 IOG fallback 的主要飞行效果。

## 10. AHEAD 合并到 `RVP_FuseData`

本轮不重构 `RVP_FuseData` 的其它引信结构，只把 `RVP_AheadData` 的全部参数原样迁入现有 `RVP_FuseData`。临时新增以下平铺字段：

| 新字段 | 来源 | 默认值 |
| --- | --- | --- |
| `aheadEnabled` | `RVP_AheadData.enabled` | `false` |
| `aheadBurstOffsetMeters` | `burst_offset_meters` | `3.0` |
| `aheadRequireLock` | `require_lock` | `true` |
| `aheadMinGroundClearance` | `min_ground_clearance` | `0` |

运行门控必须保持：

```text
weaponKind == MACHINEGUN
&& fuseData.aheadEnabled
&& fuseData.programmableAirburst
```

不能把 `aheadEnabled` 与 `programmableAirburst` 合并成一个字段：

- `programmableAirburst=true, aheadEnabled=false` 是玩家按 R 手动测距的普通可编程空爆。
- 两者都为 true 才是根据预瞄解算自动编程的 AHEAD。

迁移时需要同步更新：

- `RVP_AheadProgrammer`
- `RVP_AheadDebug`
- `RVP_AirburstInput`
- `RVP_MachinegunLeadOverlay`
- `RVP_BaseBullet.shouldSuppressAheadAirburstAt()`
- `RVP_WeaponData` 中所有 AHEAD 便捷 getter

建议先让 `RVP_WeaponData.isAheadEnabled()` 等便捷 getter 改为代理 `RVP_FuseData`，确认所有调用链正常后，再删除 `RVP_AheadData` 和旧 getter。这样减少一次性改动范围，但不提供旧 JSON 兼容。

此次临时合并不改变 AHEAD 算法、按键、网络包、空爆距离存储或低空抑制逻辑。未来单独重构 `RVP_FuseData` 时，再决定是否将这些字段收进新的 fuse 子结构。

## 11. `RVP_FireData` 的锁定门控

顶层 `require_lock` 应按新文档迁入 `RVP_FireData.requireLock`。迁移后发射门控不能只判断“武器是否包含某种制导类型”，而应按 launch guidance 判断：

| launch guidance | `requireLock=true` 时的有效锁定来源 |
| --- | --- |
| IR + IR HMD | `RVP_ClientHmdState` 的有效 IR 锁定 |
| IR + 无 HMD + EO | WeaponUnit 的 EO/IR 锁定，且通过 range/FOV/高度过滤 |
| SARH/ARH | 本机或外置雷达的有效指定目标 |
| GPS/LH/SALH | 有效目标点/照射点，是否要求实体锁由类型语义决定 |
| MCLOS/HITL/ATV/AIR | 通常配置 `requireLock=false` |

终端制导类型不得参与发射前 `requireLock` 类型选择。

## 12. 建议实施顺序

### 阶段 -1：冻结行为基线

1. 为 IR HMD、无 HMS EO 获取、EO_CCIP、激光、HITL、ARH、SARH、ARM、GPS 和 AHEAD 建立 characterization tests 或可重复 debug 场景。
2. 记录锁定来源、使用的角度/距离/高度字段、目标 ID、主末状态和退出原因。
3. 统计当前 Mixin 列表并冻结；后续每个阶段检查新增数量，正常结果应为 0。
4. 不把当前错误命名的 guidance type 当成正确行为基线，只冻结其玩家可观察功能，再按迁移矩阵改名。

### 阶段 0：实现 `RVP_Range`

1. 新增 `RVP_Range`、区间规范化和 `contains()`。
2. 新增 Gson value adapter 和 map-key adapter。
3. 完成 Integer/Float、并集、无穷和非法输入测试。
4. 此阶段不改任何武器运行逻辑。

### 阶段 A：建立新模型和多态加载，不删除旧运行链

1. 新增 `RVP_GuidanceDataHITL`、`RVP_GuidanceDataGPS` 和 `RVP_TerminalGuidanceData`。
2. 新增字段级 `RVP_GuidanceDataAdapter`，按 `guidanceType` 创建正确子类。
3. 新增主/末状态枚举和单向切换状态。
4. 新增 launch config 与 active config 两套 resolver。
5. 编写新 schema、子类和末端数据的反序列化测试。

### 阶段 B：迁移制导算法消费者

1. 先迁 `RVP_GuidanceController`，改为每 tick 只执行主段或末段。
2. 删除预测拦截点分支，改为 `predictTargetPos` 单开关的纯 PN 路径。
3. 让 `RVP_ProjectileData.turningFactor` 接管机动限制，删除 guidance turning 插值。
4. 先迁可直接复用的 `NONE/GPS/SARH/ARH/ARM`。
5. 拆分 `IR` 和 `AIR`。
6. 将当前错误命名的 SACLOS 拆成 `LH/SALH/HITL_TV`。
7. 将当前 MCLOS 拆成 `SACLOS/HITL_CLOS_TV`。
8. 最后新写真正 `MCLOS/TV/ATV/LOSBR`；`LBR` 在定义明确前不实现。
9. 移除 `phase selector / resolver / compositor` 对飞行路径的参与。

### 阶段 C：迁移发射前与 HUD

1. 迁移 `requireLock` 到 `RVP_FireData`。
2. 迁移武器级 `enable_hms` 到 `RVP_GuidanceData.enableIRHMD`。
3. 新增普通 Java 的 IR altitude range classifier 和内部 `RVP_IrHudProfile`，不增加 Mixin。
4. 改造 IR HMD、无 HMS EO 获取、锁定圈和战术地图 seeker 校验。
5. 验证 EO_CCIP 在普通视角和观瞄视角间稳定切换。

### 阶段 D：迁移 AHEAD

1. 把 AHEAD 字段加入 `RVP_FuseData`。
2. 将所有读取点改为 `FuseData`。
3. 验证手动可编程空爆与 AHEAD 自动编程仍是两条输入链。
4. 删除 `RVP_AheadData`。

### 阶段 E：最后删除红框类

删除前必须执行全仓搜索，确保以下类型没有任何生产代码引用：

- `RVP_GuidanceActivationData`
- `RVP_GuidanceSeekerData`
- `RVP_GuidanceSourceParamsData`
- `RVP_GuidanceStageData`
- `RVP_GuidanceSteeringData`
- `RVP_HumanInTheLoopData`
- `RVP_AheadData`

随后删除旧 phase/composite 专用代码和旧测试，避免留下两套状态机。

## 13. 必须新增或改写的测试

### 13.1 数据模型

- 主制导无末端配置。
- 主制导 + 按 tick 接管末端。
- 主制导 + 按三维距离接管末端。
- 主制导 + 按水平距离接管末端。
- 多个接管条件按 AND 生效。
- `RVP_Range` 并集、无穷边界和非法倒置区间。
- `RVP_Range` 作为 map key 的反序列化。
- guidance subtype 的 Gson 多态反序列化。
- `guidanceType` 与实际子类不匹配时拒绝加载。

### 13.2 主末状态机

- 接管 tick 只调用末端制导一次。
- 进入末端后永不回到主制导。
- 末端丢目标后只使用末端记忆点。
- 无接管条件的非空末端配置被拒绝。
- 末端 `activeRadarActivationRange`、`maxLockAngle` 和跟踪距离只读取末端值。

### 13.3 制导类型迁移

- 当前 SACLOS point-only 分支迁移后只进入 `LH`。
- 当前 SACLOS 实体照射分支迁移后只进入 `SALH`。
- 当前 HITL DESIGNATE 分支迁移后只进入 `HITL_TV`。
- 当前 MCLOS operator aim 分支迁移后进入 `SACLOS`。
- 当前 HITL MOUSE 分支迁移后进入 `HITL_CLOS_TV`。
- 新 MCLOS 必须由键盘舵面输入驱动，不能读取武器站 LOS 冒充。
- `IR` 无目标时不得自由扫描其它目标；`AIR` 才允许自主搜索和再捕获。
- `TV/ATV/LOSBR` 在 source 尚未完成时不得通过 enum 配置伪装成已实现。
- `LBR` 未定义清楚前，加载期应拒绝或标记为未实现。

### 13.4 比例制导与机动物理

- `predictTargetPos=false` 时不调用 PN。
- `predictTargetPos=true` 时只调用 PN，不调用 `RVP_InterceptSolver`。
- guidance 转向和 `ProjectileData.turningFactor` 不得重复修改同一 tick 的速度方向。
- 主段和末段使用各自的 PN 开关。
- 不再读取 `useProportionalNavigation/proportionalNavigationGain/maxLateralAccel`。

### 13.5 IR / HMS

- 有 HMS：FOV 搜索、离轴钳制、锁后保活、发射后跟踪分别使用正确字段。
- 无 HMS：小圈固定机头，目标进入 `maxLockAngle` 后可锁定。
- 无 HMS + EO：仍可由 EO 获取目标。
- 高空 / 近地 / 并集高度范围的过滤与 HUD 样式。
- GPS + 末端 AIR 不触发发射前 IR 锁定门控。
- `maxLockAngle` 只在扫描时除以二；另外两个角度字段不除以二。
- `[[5,inf]]` 自动使用 `AIR` profile。
- `[[inf,25]]` 自动使用 `GROUND` profile。
- `[[inf,10],[30,inf]]` 在未锁定时使用 `MIXED`，锁定低空/高空目标后动态切换目标标记。
- AGL 位于并集空洞区间的目标不会因为 HUD profile 而绕过范围过滤。
- `lockAltitudeRange` 与 `guidanceAltitudeRange` 分开读取，发射前 UI 不读取飞行段过滤范围。

### 13.6 ARH / SARH / ARM

- ARH seeker 开机前继续接受雷达指定目标。
- pitbull 后优先捕获原指定目标，不抢锁最近其它目标。
- 原目标失效后才允许自由捕获。
- SARH 失照后的惯性与非惯性分支。
- ARM 预选、PDW 扫描、脉冲记忆、再捕获和锁定评分。

### 13.7 HITL

- `HITL_TV` 只接受重新指定输入。
- `HITL_CLOS_TV` 只接受鼠标驾控输入。
- `hITLMaxLookOffset` 与 `hITLMaxTurnDegPerTick` 分别生效。
- 弹药爆炸/失效/超时后退出视角。
- RADIO 遮挡恢复与 40 tick 永久断链。

### 13.8 EO_CCIP

- 非观瞄视角连续显示 CCIP，不是只在切换武器一瞬间显示。
- 进入观瞄后切为 EO，退出后恢复 CCIP。
- CCIP 落点使用 RVP 炸弹物理参数。
- 末端 guidance 状态不影响尚未发射时的火控 HUD。

### 13.9 AHEAD

- 普通 programmable airburst 仍允许手动测距。
- AHEAD 启用后跳过手动测距并使用预瞄解算。
- `aheadRequireLock=false` 时可回退到瞄点距离。
- 低于 `aheadMinGroundClearance` 时抑制空爆。
- 编程距离仍受 `airburstMeasureMin/Max` 限制。

## 14. 实施前的硬性阻断项

以下任一项未解决时，不应开始删除红框类：

1. 现有功能尚无 characterization baseline，无法判断重构是否改变行为。
2. 出现未经调用链论证的新 Mixin，或业务算法继续堆入现有 Mixin。
3. `RVP_Range`、无穷端点和 map-key adapter 尚未实现并通过测试。
4. `RVP_GuidanceDataHITL/GPS` 尚不能由 Gson 按 `guidanceType` 正确创建。
5. 末端主动 seeker 的开机距离字段名仍为空，或运行时仍误读主段值。
6. 当前 `SACLOS/MCLOS` 的错误语义尚未按迁移矩阵拆开。
7. `RVP_InterceptSolver` 和旧预测拦截点分支尚未从制导链删除。
8. guidance turning 插值和 `ProjectileData.turningFactor` 仍可能在同一 tick 双重修改速度。
9. ARM 的脉冲记忆和 emitter 评分参数尚无新位置。
10. IR seeker 的发射前范围与发射后范围仍由一个含糊 getter 返回。
11. IR altitude range 仍直接用单个正负数决定 HUD，或 `MIXED` 范围尚无稳定表现。
12. 三个角度字段的全角/单侧角定义尚未落实到 getter 和测试。
13. `enableIRHMD=false` 的 EO 获取链尚未改用新字段。
14. EO_CCIP 仍依赖旧 `usesGuidanceType()` 的模糊判断。
15. 主/末状态切换仍复用当前多阶段 compositor。
16. AHEAD 参数尚未完整迁入 `RVP_FuseData`，或自动/手动空爆两条链被合并。

满足以上条件后再删除数据类，重构风险会从“删除后到处补编译错误”变成“先让新 resolver 接管行为，再移除已经无消费者的旧模型”。
