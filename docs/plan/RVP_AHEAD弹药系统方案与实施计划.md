# RVP AHEAD 弹药系统方案与实施计划

## 目标

- 在 `ywzj_rvp` 内实现一套可配置的 `AHEAD` 弹药系统。
- 采用方案 A：母弹按可编程空爆距离飞行，在空中开花后释放真实子弹药实体。
- 第一版优先绑定到 `rvp:machinegun`，与现有机枪预瞄圈系统联动。
- `AHEAD` 作为 `rvp:machinegun` 上的一种配置化能力存在，而不是新增独立武器类型。
- AHEAD 的空爆编程距离直接使用“主弹药到预瞄圈的飞行距离”，再叠加一个可配置的“提前多少米爆炸”偏置。
- Java 侧只负责“预瞄圈 -> 空爆距离”的自动编程；开花后放什么子弹药、速度倍率多少、散布多大，全部由 `submunition_data` 与子弹药自身配置决定。

## 设计前提

### 已有能力

- `RVP_MachinegunLeadSolver` 已经能在客户端解出机枪的预瞄点世界坐标。
- `RVP_AirburstRangeStore` 已经能为当前武器存储可编程空爆距离。
- `RVP_BaseBullet` 已经支持 `programmable_airburst` 引信，并按累计飞行距离触发。
- `submunition_data` 已经支持 `on_fuse` 触发器和真实子弹药生成。

### 本方案的关键简化

- 不单独为子弹药做第二套提前量解算。
- 不单独计算“子弹药散布后谁去打中目标”的命中学。
- 只要求：
  - 母弹飞到预瞄圈附近时开花。
- 额外通过一个预设偏置 `ahead_data.burst_offset_meters` 控制开花点相对预瞄圈的前后位置。
  - 子弹药的具体载荷、速度、散布形态全部留给 `submunition_data` 配置层处理。

### 这样做成立的原因

- 现有预瞄圈本质上已经是“主弹药如果继续飞行，将最接近目标的命中点”。
- `AHEAD` 的核心问题不是“Java 帮你决定子弹药怎么飞”，而是“Java 帮你把母弹开花点编程到预瞄圈附近”。
- 开花后的子弹药如何飞行，本来就可以通过 `payload.type = "rvp_weapon"` 调用另一枚 `rvp:*` 武器弹药，再由那枚子弹药自己的配置决定。
- 因此，第一版完全可以把 AHEAD 简化成：
  - 先解出预瞄圈；
  - 再计算“枪口到预瞄圈”的距离；
- 然后写入 `airburstDistance = leadDistance - ahead_data.burst_offset_meters`。

## 功能定义

### 玩家侧体验

- 当当前武器是支持 AHEAD 的 `rvp:machinegun` 时：
  - 继续显示现有绿色预瞄圈；
  - 火控自动/半自动模式依旧围绕预瞄圈工作；
  - 发射前，系统自动把本次空爆距离编程到弹药上；
  - 炮弹飞到预设距离时在空中开花；
  - 开花后释放一束真实子弹药，对目标前方空间形成破片云。

### 第一版不做的内容

- 不做单独的“AHEAD 专属新 HUD 图标”。
- 不做子弹药再次制导。
- 不做复杂的时间引信手动调节界面。
- 不做“不同子体速度、不同比阻”的二级求解。

## 配置方案

### 武器启用方式

建议直接把 `AHEAD` 设计成 `rvp:machinegun` JSON 上的 `ahead_data` 扩展分组，避免所有可编程空爆机炮都默认变成 AHEAD，也避免新增单独武器类型带来的管理分裂。

建议字段：

| 字段 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `ahead_data.enabled` | `bool` | `false` | 是否启用 AHEAD 自动编程逻辑。 |
| `ahead_data.burst_offset_meters` | `float` | `3.0` | 相对预瞄圈提前多少米开花；正值表示在到达预瞄圈前开花。 |
| `ahead_data.require_lock` | `bool` | `true` | 是否要求当前必须有锁定目标且能解出预瞄圈。 |
| `ahead_data.min_ground_clearance` | `float` | `0.0` | 最低离地空爆高度；低于该值时取消 AHEAD 空爆，避免对地过强。 |

第一版约束：

- `ahead_data.enabled = true` 时，武器必须同时满足：
  - `type = "rvp:machinegun"`
  - `fuse_data.programmable_airburst = true`
  - `submunition_data.trigger = "on_fuse"`

### 子母弹配置约束

第一版建议使用以下配置套路，但不把子弹药参数写死在 Java 中：

- 母弹：`rvp:machinegun`
- 引信：`programmable_airburst`
- 触发：`on_fuse`
- 子弹药载荷：`rvp_weapon`
- 子弹药类型：由配置层自由决定，可做同口径小破片弹 / 小 HE 破片弹 / 小燃烧破片弹 / 其他特殊子弹药

示意：

```json
{
  "type": "rvp:machinegun",
  "ahead_data": {
    "enabled": true,
    "burst_offset_meters": 3.0,
    "require_lock": true,
    "min_ground_clearance": 20.0
  },
  "fuse_data": {
    "programmable_airburst": true
  },
  "submunition_data": {
    "trigger": "on_fuse",
    "parent_action": "discard_after_release",
    "payload": {
      "type": "rvp_weapon",
      "weapon": "rvp:example_ahead_fragment"
    },
    "pattern": {
      "type": "canister",
      "count": 18,
      "spread_deg": 4.0
    }
  }
}
```

### 子弹药配置原则

- Java 侧不强行规定子弹药速度、数量、散布角。
- 推荐做法是把这些全部留在 `submunition_data.pattern` 与子弹药武器 JSON 里管理。
- 如果想做更接近经典 AHEAD 的手感，可以在配置层把子弹药速度倍率配得接近母弹当前速度。
- 如果想做别的变体，例如减速散布、燃烧破片、微型 HE，则同样只需要改配置，不需要再改 Java 逻辑。

## 技术方案

### 总体流程

1. 客户端预瞄圈系统正常解出 `leadWorldPos`。
2. 发射前读取枪口位置 `muzzlePos`。
3. 计算 `leadDistance = distance(muzzlePos, leadWorldPos)`。
4. 计算 `programmedDistance = max(0, leadDistance - ahead_data.burst_offset_meters)`。
5. 将 `programmedDistance` 写入 `RVP_AirburstRangeStore`。
6. 母弹生成时读取该距离并保存到弹体。
7. 母弹飞行累计距离达到 `programmedDistance` 后，触发 `programmable_airburst`。
8. `submunition_data.on_fuse` 生效，生成真实子弹药。

### 为什么不需要额外二次解算

- 预瞄圈已经是对主弹药飞行路径的命中点求解。
- Java 侧只需要把开花位置编程到预瞄圈附近，AHEAD 的核心就已经成立。
- 开花后的子弹药飞行趋势、密度和杀伤形状交给配置层决定。
- AHEAD 的核心不再是“单发精准命中”，而是“在目标附近的空间截面制造破片云”。
- 因此只需把开花位置控制在预瞄圈附近即可。

### 与现有系统的接入点

#### 1. 预瞄圈解算层

复用：

- `org.ywzj.rvp.client.lead.RVP_MachinegunLeadSolver`
- `org.ywzj.rvp.client.state.RVP_MachinegunLeadState`

新增职责：

- 增加一个面向 AHEAD 的辅助读取函数，用于稳定地取得“本 tick 用于发射编程的预瞄点”。

建议新增：

- `RVP_AheadSolution`
- `RVP_AheadProgrammer`

#### 2. 空爆距离编程层

复用：

- `org.ywzj.rvp.weapon.fuse.RVP_AirburstRangeStore`

新增职责：

- 在当前武器满足 `ahead_data.enabled` 时，不再让玩家手动测距写入空爆距离；
- 改为由系统在发射前自动写入。

建议逻辑：

- 若当前武器不是 AHEAD，则保持原有可编程空爆行为不变。
- 若当前武器是 AHEAD，则优先使用自动计算值覆盖手动测距值。

#### 3. 发射链

优先接入：

- `RVP_ProjectileSpawner`
- 或当前 `machinegun` 发射前的统一上下文构建点

目标：

- 每次发射前，把本次编好的 `programmedDistance` 附着到弹丸实体。
- 不在这里决定子弹药载荷细节；载荷继续完全来自武器 JSON 的 `submunition_data`。

#### 4. 引信触发层

复用：

- `RVP_BaseBullet` 中现有的 `programmable_airburst`

不改动原则：

- 不新造一套 AHEAD 专属引爆链。
- 只复用当前可编程空爆判定。

#### 5. 子母弹释放层

复用：

- `RVP_SubmunitionRunner`
- `RVP_SubmunitionSpawner`

要求：

- 第一版子弹药真实生成。
- 不走伪爆炸半径，不走假的 AoE 判伤。

## 代码改动规划

### 新增配置字段

建议在 `RVP_WeaponData` 上新增：

- `aheadEnabled`
- `aheadBurstOffsetMeters`
- `aheadRequireLock`

并同步更新：

- `RVP包新增参数字段说明.md`

### 新增类

建议新增：

- `org.ywzj.rvp.client.ahead.RVP_AheadSolution`
  - 保存 `leadWorldPos`
  - 保存 `leadDistance`
  - 保存 `programmedAirburstDistance`
  - 保存是否有效及失效原因

- `org.ywzj.rvp.client.ahead.RVP_AheadProgrammer`
  - 判断当前武器是否为 AHEAD
  - 从预瞄圈系统取解
  - 计算空爆编程距离
  - 写入 `RVP_AirburstRangeStore`

### 需要修改的现有类

- `RVP_WeaponData`
  - 增加 AHEAD 配置读取字段和 getter

- `RVP_MachinegunLeadSolver`
  - 视情况抽取一个稳定的公共求解入口，方便 AHEAD 和 HUD 共用同一套结果

- `RVP_AirburstRangeStore`
  - 若现有键设计不足，补充“按武器站/武器实例”更稳定的索引

- `RVP_ProjectileSpawner`
  - 在发射前后接入自动编程逻辑

- `RVP包新增参数字段说明.md`
  - 补 AHEAD 字段说明

## 实施计划

### 第 1 阶段：方案落地与 schema 扩展

- 给 `RVP_WeaponData` 增加 AHEAD 字段
- 更新参数说明文档
- 准备一份示例武器 JSON

交付物：

- 代码字段可读
- 文档可查
- 示例 JSON 可配置

### 第 2 阶段：自动编程核心

- 新建 `RVP_AheadSolution`
- 新建 `RVP_AheadProgrammer`
- 基于现有预瞄圈结果计算：
  - `leadDistance`
  - `programmedDistance`

计算公式：

```text
leadDistance = |leadWorldPos - muzzlePos|
programmedDistance = max(0, leadDistance - ahead_data.burst_offset_meters)
```

交付物：

- 客户端可得到稳定的 AHEAD 编程距离

### 第 3 阶段：发射链接入

- 在 `machinegun` 发射前自动写入 `RVP_AirburstRangeStore`
- 保证每发弹都带上本次编程值
- 确保非 AHEAD 武器不受影响

交付物：

- 发射时可自动编程

### 第 4 阶段：子母弹联动验证

- 准备一枚 AHEAD 示例机炮弹
- `submunition_data.trigger = on_fuse`
- 子弹药载荷使用 `rvp_weapon`
- 子弹药速度与散布完全从配置层控制
- 验证空爆后真实生成子弹药

交付物：

- 第一枚可用的 AHEAD 弹药

### 第 5 阶段：手感调参与 HUD 观察

- 调 `ahead_data.burst_offset_meters`
- 调子体数量
- 调散布角
- 观察是否需要额外显示“空爆点提示”

交付物：

- 第一版可玩的实战手感

## 验证计划

### 静态验证

- 无锁定目标时是否按配置禁止 AHEAD 编程
- 非 `ahead_data.enabled` 武器是否完全不受影响
- 非 `rvp:machinegun` 武器是否不会误触发

### 动态验证

- 静止目标：验证预瞄圈附近开花
- 横向飞行目标：验证破片云能覆盖目标前方路径
- 高速机动目标：验证至少能稳定在预瞄圈附近开花
- 己方高速运动：验证继承速度后距离计算无明显跑偏

### 回归验证

- 普通机枪预瞄圈不能被破坏
- 普通 programmable airburst 弹不能被改坏
- 普通子母弹系统不能被改坏

## 风险与注意事项

### 1. 预瞄圈抖动会直接传递到空爆距离

- 如果预瞄圈解不稳定，AHEAD 的编程距离也会跟着抖。
- 第一版建议直接复用当前平滑后的 lead 结果，而不是原始 raw 解。

### 2. 子弹药数量不能过大

- AHEAD 适合高射速场景。
- 如果每发都释放太多实体，会迅速拖垮性能。
- 第一版建议每发 `12 ~ 24` 个子体起步。

### 3. 开花偏置是核心调参项

- 偏置太小，容易穿过目标后才开花。
- 偏置太大，会提前开花导致命中不足。
- 第一版建议从 `2.0 ~ 4.0 m` 开始试。

### 4. 子弹药参数完全由配置层决定

- 如果配置把子弹药速度调得过低、散布拉得过大，命中表现会很快变差。
- 这是配置设计问题，不是 AHEAD 自动编程链本身的问题。
- 第一版不打算在 Java 里替配置兜底修正子弹药参数。

## 推荐第一枚测试弹

建议先做一枚 30mm 或 35mm 机炮 AHEAD 测试弹：

- 武器类型：`rvp:machinegun`
- 有锁定目标时自动编程
- 无锁定目标时退回普通空爆或禁止 AHEAD
- 子体数量：`16`
- 散布角：`3.5°`
- `ahead_data.burst_offset_meters = 3.0`
- 子体类型：用 `submunition_data.payload = rvp_weapon` 指向独立破片弹药

## 当前执行状态

- [x] 写方案文档
- [x] 明确采用方案 A
- [x] 明确 AHEAD 作为 `rvp:machinegun` 的配置化能力
- [x] 给出实施计划
- [ ] 扩展 `RVP_WeaponData` AHEAD 字段
- [ ] 编写 `RVP_AheadSolution`
- [ ] 编写 `RVP_AheadProgrammer`
- [ ] 接入发射链自动编程
- [ ] 配置第一枚 AHEAD 示例弹
- [ ] 构建与实战验证
