# TEL发射架展开机制方案
> 日期：2026-07-14
> 范围：`ywzj_rvp` 侧为地空导弹 TEL / 发射车新增“发射架展开-收拢-射击门禁”机制的设计方案。

## 1. 背景与目标

当前 `ywzj_vehicle` / `ywzj_rvp` 的武器系统里：

- 武器位一旦具备弹药与射击条件，就可以直接发射；
- 没有“发射架必须展开才能打”的机械状态约束；
- 也没有“车辆高速移动时禁止发射、防空架低速自动展开、起步自动收回”的一整套流程。

对于一些防空系统 TEL 车，这会带来几个问题：

1. 表现层不符合常见 SAM 载具逻辑；
2. 高速移动射击不合理；
3. 发射架展开动画只能当纯表现，无法真正约束武器；
4. 车载玩家在停车待机时，不能自动进入可发射状态。

本方案目标是为 `ywzj_rvp` 增加一套 **Launcher Deploy（发射架展开）状态机**：

- 低速或静止时自动展开；
- 移动时自动收回；
- 展开/收回都有时间成本；
- 发射架未展开、展开中、收回中都禁止发射；
- 允许后续加入手动覆盖逻辑；
- 尽量复用现有 `SwitchableUnit` / 弹舱自动开关思路，而不是重造一整套动画系统。

---

## 2. 总体思路

推荐做法不是把 TEL 发射架直接当成 `WeaponBayUnit`，而是：

- **表现层**：复用本体 `SwitchableUnit`
- **控制层**：在 `ywzj_rvp` 增加发射架部署状态机
- **门禁层**：在武器发射入口拦截不满足条件的射击

一句话概括：

> **RVP 侧管理“是否允许发射”，本体 `SwitchableUnit` 只负责展开/收拢动画与开关状态同步。**

这样做有几个好处：

- 不需要修改 `ywzj_vehicle`；
- 兼容现有 Bedrock 动画播放链路；
- 适合一套发射架约束多个 `WeaponUnit`；
- 后续可以继续扩展支腿、雷达升起、发射后回位等机械状态。

---

## 3. 参考链路

本方案建议借鉴以下现有机制：

### 3.1 `SwitchableUnit`

本体已存在通用开关部件：

- [SwitchableUnit.java](/D:/ywzj/ywzj/ywzj_vehicle/src/main/java/org/ywzj/vehicle/vehicle/part/SwitchableUnit.java)

其能力：

- 保存 `on/off` 状态；
- 服务端同步到客户端；
- 客户端 `SwitchableRunner` 可自动驱动对应动画。

这很适合作为 TEL 发射架的“动画开关层”。

### 3.2 弹舱自动开关思路

RVP 当前已有一套“按当前武器自动开关弹舱”的逻辑：

- [WeaponUnitSetWeaponMixin.java](/D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/mixin/WeaponUnitSetWeaponMixin.java)
- [ClientVehicleActionWeaponBayOverrideMixin.java](/D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/mixin/ClientVehicleActionWeaponBayOverrideMixin.java)
- [WeaponBayManualOverrideManager.java](/D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/config/WeaponBayManualOverrideManager.java)

这说明：

- RVP 已经有“自动状态 + 手动覆盖”的本地模式；
- 也已经有 mixin 方式去控制 `SwitchableUnit` / `WeaponBayUnit` 的经验。

TEL 发射架完全可以沿用这种实现方向，但语义上单独做一套，不与弹舱混用。

---

## 4. 推荐配置设计

建议在 **载具 JSON 顶层** 新增：

```json
"rvp_launcher_deploy": [
  {
    "enabled": true,
    "id": "sam_launcher_main",
    "part_unit_id": "sam_launcher_switch",
    "weapon_unit_ids": ["main_sam"],
    "deploy_speed_max": 2.0,
    "retract_speed_min": 2.5,
    "deploy_time_tick": 60,
    "retract_time_tick": 40,
    "require_player_present": true,
    "auto_deploy": true,
    "auto_retract": true,
    "allow_manual_override": true,
    "block_fire_when_closed": true,
    "block_fire_when_deploying": true,
    "block_fire_when_retracting": true,
    "block_fire_when_speeding": true
  }
]
```

建议做成 **数组** 而不是单对象，原因是：

- 一辆车可能有多个发射架组；
- 后续可支持左右两组、不同武器位分别绑定；
- 结构上更好扩展。

### 4.1 字段说明

| 字段 | 含义 |
| --- | --- |
| `enabled` | 是否启用该发射架部署规则。 |
| `id` | 规则 ID，仅用于调试/日志/后续扩展。 |
| `part_unit_id` | 对应动画开关部件，必须能解析到 `SwitchableUnit`。 |
| `weapon_unit_ids` | 受此发射架约束的 `WeaponUnit` 列表。 |
| `deploy_speed_max` | 速度低于等于该值时，允许展开。 |
| `retract_speed_min` | 速度高于等于该值时，强制收回。 |
| `deploy_time_tick` | 展开耗时。 |
| `retract_time_tick` | 收回耗时。 |
| `require_player_present` | 车上必须有玩家时才自动展开。 |
| `auto_deploy` | 是否允许自动展开。 |
| `auto_retract` | 是否允许自动收回。 |
| `allow_manual_override` | 是否允许手动切换覆盖自动状态。 |
| `block_fire_when_closed` | 关闭状态是否禁止发射。 |
| `block_fire_when_deploying` | 展开中是否禁止发射。 |
| `block_fire_when_retracting` | 收回中是否禁止发射。 |
| `block_fire_when_speeding` | 超过允许速度时是否直接禁止发射。 |

### 4.2 为什么 `deploy_speed_max` 和 `retract_speed_min` 分开

建议不要只用一个速度阈值。

如果只配一个值，比如 `2.0`：

- 速度在 `1.95 ~ 2.05` 附近抖动时，
- 发射架会不断展开/收回抖动。

因此推荐做一个简单滞回：

- `deploy_speed_max = 2.0`
- `retract_speed_min = 2.5`

这样低于 2.0 才展开，高于 2.5 才收回，中间区间保持当前状态，更稳定。

---

## 5. 状态机设计

推荐 4 态：

```text
CLOSED
DEPLOYING
OPEN
RETRACTING
```

### 5.1 状态定义

| 状态 | 含义 |
| --- | --- |
| `CLOSED` | 发射架收起，不可发射。 |
| `DEPLOYING` | 发射架展开中，不可发射。 |
| `OPEN` | 发射架已展开，可发射。 |
| `RETRACTING` | 发射架收回中，不可发射。 |

### 5.2 推荐转移规则

#### `CLOSED -> DEPLOYING`

满足以下条件时进入展开：

- `auto_deploy = true`
- 车辆速度 `<= deploy_speed_max`
- 若 `require_player_present = true`，则车上存在玩家

#### `DEPLOYING -> OPEN`

- 展开计时达到 `deploy_time_tick`

#### `OPEN -> RETRACTING`

满足以下任一条件：

- `auto_retract = true` 且速度 `>= retract_speed_min`
- 要求玩家在车上，但当前无人
- 玩家手动关闭

#### `RETRACTING -> CLOSED`

- 收回计时达到 `retract_time_tick`

### 5.3 状态机行为原则

1. **只有 `OPEN` 允许发射**
2. 其它状态默认全部禁止发射
3. 状态切换由服务端主导
4. 客户端只负责表现和提示

---

## 6. 自动展开/自动收回逻辑

### 6.1 自动展开

推荐条件：

- 车上有玩家
- 车辆静止或低速
- 当前至少有一个受约束的 `WeaponUnit` 有可用弹药

是否要求“当前选中了对应武器”有两种选择：

#### 方案 A：保守

只有切到对应武器位才自动展开。

优点：

- 更省状态变更；
- 不会一上车就乱动机械结构。

缺点：

- 玩家切到 SAM 武器后还要等展开。

#### 方案 B：积极

只要车上有玩家且低速，就自动进入可发射待机状态。

优点：

- 更符合许多防空 TEL 的待战逻辑；
- 玩家停车后很快能发射。

缺点：

- 会更频繁播放展开动画。

**建议第一版用方案 B。**

### 6.2 自动收回

当车辆开始移动时：

- 若速度超过 `retract_speed_min`
- 自动进入 `RETRACTING`

这样可以保证：

- 一旦车辆起步，不再允许保持展开发射架高速跑动；
- 机械表现符合预期。

---

## 7. 手动覆盖机制

建议支持手动开关，并且采用和弹舱类似的“手动优先级覆盖”。

### 7.1 目标行为

- 默认自动控制
- 玩家手动展开 / 收回后，暂时覆盖自动逻辑
- 但当硬条件被打破时，自动逻辑重新接管

### 7.2 手动覆盖失效条件

推荐以下情况清除手动覆盖：

- 车辆速度明显越过安全阈值
- 玩家离开载具
- 切换到完全不受该发射架约束的武器位
- 载具被摧毁

### 7.3 推荐实现

参考：

- [WeaponBayManualOverrideManager.java](/D:/ywzj/ywzj/ywzj_rvp/src/main/java/org/ywzj/rvp/config/WeaponBayManualOverrideManager.java)

单独新增一个：

- `LauncherDeployManualOverrideManager`

避免与弹舱覆盖状态混在一起。

---

## 8. 动画与 `SwitchableUnit` 的关系

### 8.1 推荐做法

`part_unit_id` 对应一个现有 `switchable` 部件。

发射架状态与 `SwitchableUnit` 的映射建议如下：

| Launcher State | Switchable `on` |
| --- | --- |
| `CLOSED` | `false` |
| `DEPLOYING` | `true` |
| `OPEN` | `true` |
| `RETRACTING` | `false` |

原因：

- `SwitchableRunner` 通常用 `on/off` 驱动正放/反放动画；
- 逻辑状态机自己单独记“正在展开”还是“正在收回”；
- 动画层只负责朝目标状态播放。

### 8.2 动画时长建议

最好让：

- `deploy_time_tick`
- `retract_time_tick`

尽量和动画长度一致。

否则会出现：

- 动画还没播完，逻辑已允许发射；
- 或动画播完了，逻辑还在等待。

第一版建议由逻辑时间主导，动画时长去贴逻辑，而不是反过来。

---

## 9. 射击门禁设计

这是整个功能最关键的一层。

### 9.1 基本规则

若某个 `WeaponUnit` 被某条 `rvp_launcher_deploy` 规则绑定，则：

- `CLOSED`：禁止发射
- `DEPLOYING`：禁止发射
- `RETRACTING`：禁止发射
- `OPEN`：允许发射

额外地，若 `block_fire_when_speeding = true`：

- 即使当前 `OPEN`
- 只要速度已经超过允许阈值
- 也直接禁止发射

### 9.2 提示文案建议

建议在禁止发射时返回短提示：

- `发射架未展开`
- `发射架展开中`
- `发射架收回中`
- `车速过高，无法发射`

这样玩家能快速理解为什么按下发射没反应。

### 9.3 射击门禁落点

推荐在 `WeaponUnit` 发射路径上做 mixin 拦截。

原因：

- 不需要改本体；
- 可以统一约束主武器、次武器；
- 可以和现有 seeker / bay / radar 相关 mixin 一样放在 RVP 侧管理。

---

## 10. 推荐实现结构

建议增加如下模块：

### 10.1 配置类

- `RVP_LauncherDeployConfig`
- `RVP_LauncherDeployConfigCache`

职责：

- 解析载具 JSON 顶层 `rvp_launcher_deploy`
- 缓存到 `vehicleId -> config list`

### 10.2 运行时状态

建议给 `AbstractVehicle` 挂 mixin，保存：

- 每条 deploy 规则当前状态
- 当前状态已运行 tick
- 是否存在手动覆盖

可做成：

- `Map<String, LauncherDeployState>`

其中 key 用配置里的 `id`。

### 10.3 Tick 驱动

新增一个类似：

- `AbstractVehicleLauncherDeployMixin`

每 tick 做：

1. 取配置
2. 读取车速
3. 读取是否有玩家
4. 推进状态机
5. 同步 `SwitchableUnit.on`

### 10.4 手动覆盖管理

- `LauncherDeployManualOverrideManager`

### 10.5 射击门禁

在 `WeaponUnit` 发射入口 mixin：

- 若当前武器位被发射架规则约束
- 且状态不允许发射
- 则取消本次射击

---

## 11. 数据持久化建议

建议对运行时状态做 NBT 持久化，至少保存：

- 当前状态
- 当前状态已持续 tick

这样可以避免：

- 存档重进后发射架状态瞬间重置；
- 刚展开一半的机械结构突然回到关闭态。

手动覆盖是否持久化可以分两种策略：

### 策略 A：不持久化

优点：

- 简单
- 重进世界自动回到默认自动逻辑

缺点：

- 玩家手动设定不会保留

### 策略 B：持久化

优点：

- 行为连续

缺点：

- 更复杂，需要处理版本兼容和异常恢复

**建议第一版不持久化手动覆盖，只持久化状态。**

---

## 12. 适配范围建议

第一版建议只覆盖：

- 地空导弹 TEL
- 需要展开导轨或竖起发射架的发射车

先不覆盖：

- 舰载垂发
- 机载武器挂架
- 火炮驻锄/支腿系统
- 多级联动机构（支腿 + 雷达 + 发射架）

这样范围小、风险低、最容易先做稳。

---

## 13. 风险点

### 13.1 动画与逻辑不同步

风险：

- 动画没播完但逻辑已 `OPEN`

处理：

- 用 `deploy_time_tick` / `retract_time_tick` 明确约束
- 要求配置时动画长度贴逻辑

### 13.2 速度边界抖动

风险：

- 速度在阈值附近反复开关

处理：

- 使用 `deploy_speed_max` 与 `retract_speed_min` 双阈值

### 13.3 多武器位绑定一套发射架

风险：

- 一个发射架约束多个 `WeaponUnit` 时，状态同步要一致

处理：

- 配置上明确 `weapon_unit_ids`
- 统一由 deploy rule 管，不在武器位内部各自维护独立状态

### 13.4 手动覆盖与自动逻辑打架

风险：

- 玩家手动打开后，下一 tick 又被自动关闭

处理：

- 单独维护 manual override
- 只在硬条件冲突时清除

---

## 14. 推荐第一版实施顺序

### 第一步：最小可用版本

实现：

1. 配置解析
2. `AbstractVehicle` 侧状态机
3. `SwitchableUnit` 开关驱动
4. 射击门禁

完成后即可得到：

- 停车自动展开
- 开车自动收回
- 未展开不能发射

### 第二步：手动覆盖

补：

1. 手动开关键
2. 覆盖状态缓存
3. 覆盖失效规则

### 第三步：体验增强

可选补：

1. HUD 提示
2. Debug 命令
3. 日志输出
4. 多发射架联动

---

## 15. 结论

最合理的实现方向是：

> **在 `ywzj_rvp` 侧新增一套 `Launcher Deploy` 配置与状态机，复用本体 `SwitchableUnit` 播放发射架展开/收回动画，并在武器发射入口增加部署状态门禁。**

这套方案的优点是：

- 不改 `ywzj_vehicle`
- 复用现有动画链路
- 逻辑清晰
- 适合 TEL 车
- 后续容易扩展

如果进入实现阶段，建议先做“单发射架 + 自动展开/自动收回 + 射击门禁”的第一版，不要一开始把支腿、雷达、联动机构全部塞进去。
