# TV 导弹（人在回路）调研与实现方案（基于 MCH-Reforged 思想，落地到 YWZJ Vehicle + RVP）

## 0. 目标与约束

目标：在当前项目体系里实现“人在回路 TV 导弹”玩法：发射后玩家视角切到导弹上，由玩家实时指挥导弹飞行；HUD 复用现有“观瞄/开镜”HUD，但实际相机绑定导弹。

约束：

- 参考对象为 `D:\MCHR\MCH-Reforged`（MCH-Reforged），仅复用思想，不复用代码。
- 当前工程为 RVP 附属模组，但运行时依赖本体 `E:\ywzj\ywzj_vehicle`；实现应尽量通过 Mixin/扩展完成。

结论先说：能做，且在现有架构上实现成本可控。关键在于把功能拆成“弹体控制”“相机绑定”“HUD/滤镜”三块，各自单独落地，再串起来。

---

## 1. MCH 的核心思想（抽象成可迁移模块）

参考文档：[TV导弹实现思想与复用指南_2026-05-20.md](file:///e:/ywzj/ywzj_vehicle/TV%E5%AF%BC%E5%BC%B9%E5%AE%9E%E7%8E%B0%E6%80%9D%E6%83%B3%E4%B8%8E%E5%A4%8D%E7%94%A8%E6%8C%87%E5%8D%97_2026-05-20.md)

把 TV 弹拆成三段链路最容易移植：

- **弹体（Missile Entity）**：负责飞行/碰撞/爆炸/寿命；在“被人控”时执行“跟随控制方向”的制导。
- **控制者（Controller）**：一般是玩家；每 tick 输出一个“期望方向”（看向哪里就飞向哪里）。
- **观测层（Camera/HUD/滤镜）**：决定什么时候切到导弹相机、怎么显示 HUD、是否套电视/红外滤镜。

MCH 的一个关键选择是：TV guidance 直接用“控制者的视角方向”，而不是一堆键盘输入量，这样实现极简、适配性也强。

---

## 2. 当前 YWZJ 体系里有哪些现成能力可复用

### 2.1 输入与状态存储（本体）

- 热成像开关默认绑定 4 键：[AllKeys.TOGGLE_THERMAL_IMAGING](file:///e:/ywzj/ywzj_vehicle/src/main/java/org/ywzj/vehicle/all/AllKeys.java#L30-L35)
- 按键事件里翻转一个客户端状态 `LocalVehiclePlayer.thermalImaging`：[InputHandler](file:///e:/ywzj/ywzj_vehicle/src/main/java/org/ywzj/vehicle/vehicle/control/InputHandler.java#L101-L105)
- 客户端每 tick 把状态同步到后处理开关（热成像/CRT）：[LocalVehiclePlayer.checkState](file:///e:/ywzj/ywzj_vehicle/src/main/java/org/ywzj/vehicle/vehicle/LocalVehiclePlayer.java#L501-L530)

### 2.2 相机可被“逻辑相机”驱动（本体）

本体已经把第一人称载具相机抽象成一组可插入的变量（`LocalVehiclePlayer.cameraX/Y/Z` + `cameraAimRot*`），并在 [CameraMixin.setup](file:///e:/ywzj/ywzj_vehicle/src/main/java/org/ywzj/vehicle/mixin/client/CameraMixin.java#L32-L57) 里把相机位置/朝向设置为这些值。

这意味着：只要我们在“TV 模式”下覆盖相机的位置/朝向为“导弹的位置/朝向”，就能做到“视角绑导弹”。

### 2.3 HUD 可复用（本体）

观瞄 HUD 的渲染入口在 [VehicleScopeOverlay](file:///e:/ywzj/ywzj_vehicle/src/main/java/org/ywzj/vehicle/client/gui/VehicleScopeOverlay.java#L44-L112)：

- 触发条件非常简单：玩家在载具上，且 `LocalVehiclePlayer.viewType == SCOPE`。
- 所以 TV 模式想“复用观瞄 HUD”，最省事的路线是：TV 模式期间保持 `viewType` 处于 `SCOPE`，但把相机绑定到导弹。

### 2.4 后处理滤镜框架（本体）

本体已有后处理链路的成熟实现，可以直接借用架构：

- 热成像：渲染实体到 `thermal_buffer`，再用 post shader 混合到屏幕：[ThermalHandler](file:///e:/ywzj/ywzj_vehicle/src/main/java/org/ywzj/vehicle/client/shader/ThermalHandler.java#L23-L169)
- 对应资源：
  - [shaders/post/thermal.json](file:///e:/ywzj/ywzj_vehicle/src/main/resources/assets/ywzj_vehicle/shaders/post/thermal.json#L1-L24)
  - [shaders/program/thermal.fsh](file:///e:/ywzj/ywzj_vehicle/src/main/resources/assets/ywzj_vehicle/shaders/program/thermal.fsh#L1-L75)

---

## 3. 在本项目实现“人在回路 TV 导弹”的可行落地方案

下面方案把“功能最小闭环”优先做出来，然后再逐步增强手感与对抗性。

### 3.1 模块拆分（建议在 RVP 侧做）

1) **TV 导弹弹体（服务端权威）**

- 新增一种弹体实体（建议直接继承本体 `MissileEntity`，像 RVP 的 `AntiRadiationMissileEntity` 那样做额外制导逻辑）。
- 服务端每 tick 读取“最近一次收到的控制方向”，把导弹速度向量朝该方向调整，并同步导弹朝向。
- 退出条件建议照抄 MCH 的思想：控制者无效、距离过远、寿命结束、命中爆炸 -> 立刻结束 TV 控制。

2) **控制输入采集（客户端）**

- TV 模式开启后，客户端每 tick（或限频）采集“玩家当前视角方向”（yaw/pitch 或 forward vector）。
- 通过网络包发送给服务端，服务端只信任“发包者是谁”，不信任客户端自报 ownerId，并做乱序/过期保护（这部分思想可参考 MCH 文档第 7 节）。

3) **相机绑定（客户端）**

- TV 模式开启后，客户端把相机的位置/朝向改为导弹的插值位置/旋转。
- 这里不需要真正把玩家附身到导弹（那会破坏座位/载具体系），而是“相机单纯跟随导弹”。
- 技术落点：通过 Mixin 在 `Camera.setup` 末尾覆盖 `setPosition` 与 `setRotation`（本体已有 [CameraMixin](file:///e:/ywzj/ywzj_vehicle/src/main/java/org/ywzj/vehicle/mixin/client/CameraMixin.java#L32-L57)，RVP 侧追加一个注入即可）。

4) **HUD 复用（客户端）**

- 让 `LocalVehiclePlayer.viewType` 保持为 `SCOPE`，从而原生绘制 [VehicleScopeOverlay](file:///e:/ywzj/ywzj_vehicle/src/main/java/org/ywzj/vehicle/client/gui/VehicleScopeOverlay.java#L44-L112)。
- 注意：HUD 里一些内容（比如落点/测距）默认是“武器站/开镜视角”的数据，和“导弹相机”不完全一致。
  - 最小可用版本可以先接受这种不一致（只要准星/画面能用）。
  - 追求体验时，可以在 TV 模式下额外计算“导弹视角射线的落点”，临时写入 `LocalVehiclePlayer.weaponHitPos`，让十字/测距更像真正的导弹画面。

### 3.2 三种显示模式（电视黑白 / 电视彩色 / 红外热成像）

需求：HUD 复用观瞄 HUD，但画面滤镜有三种模式：

1) **彩色电视（正常画面）**

- 不启用任何后处理（保持原本画面），仅相机跟随导弹。

2) **黑白电视（灰黑滤镜，不叠加实体高光）**

- 推荐实现为一个“只对屏幕做灰度/噪声/暗角”的 post shader。
- 关键点：它不需要像热成像那样额外渲染 `thermal_buffer`，所以成本更低。
- 结构上可以仿照 [ThermalHandler](file:///e:/ywzj/ywzj_vehicle/src/main/java/org/ywzj/vehicle/client/shader/ThermalHandler.java#L23-L169)，但只需要 `DiffuseSampler`，不需要实体缓冲。

3) **红外成像（热成像）**

- 直接复用本体热成像：调用 `ThermalHandler.setActive(true/false)` 控制开关即可。
- 本体热成像是“实体热图叠加 + 场景冷灰底”，符合你想要的“红外成像”观感。

### 3.3 状态机（建议）

最少需要一套客户端状态：

- 当前是否处于 TV 导弹控制中
- 当前控制的导弹实体 id（或 UUID）
- 当前滤镜模式（三档）
- 是否强制退出（按键/超时/导弹死亡）

服务端状态最少需要：

- 导弹实体 -> 控制者（玩家）绑定
- 导弹实体 -> 最近一次控制方向（带序号/时间戳）

---

## 4. 网络与反作弊边界（按“只能参考思想”理解的最优解）

TV 导弹最容易被做成“客户端全权控制导弹位置”，那基本等于开挂。更稳妥的边界是：

- 客户端只发“期望方向”（或者 yaw/pitch）。
- 服务端根据这份输入来更新导弹运动，导弹的位置/速度永远由服务端模拟并广播。
- 服务端要做三类保护：
  - **身份绑定**：导弹的 controller 只能是发射者/座位操作者，客户端自报一律不信。
  - **乱序/过期**：包里带 sequence 或 tick，旧的丢弃；长时间没收到输入就退出 TV 模式（降级为惯导/直飞/自毁）。
  - **能力限制**：导弹每 tick 的转向要有限制（最大角速度/最大过载），避免“瞬间 180 度调头”的观感和玩法破坏。

---

## 5. 与现有体系的耦合点（需要提前想清楚的坑）

### 5.1 “在载具上但相机不看载具”会影响什么

- 本体很多 HUD/逻辑默认以“玩家所在载具/武器站”为参考（例如开镜测距、锁定目标等）。
- TV 模式要么接受 HUD 与画面参考系不同步（最小可用），要么补一层“TV 模式下 HUD 数据源替换”为导弹视角。

### 5.2 热成像的性能与遮挡

- 热成像会额外渲染一遍实体列表（见 [ThermalHandler.prepareAndRenderEntities](file:///e:/ywzj/ywzj_vehicle/src/main/java/org/ywzj/vehicle/client/shader/ThermalHandler.java#L94-L156)）。
- 它会尝试复制主深度缓冲来实现“墙体遮挡”，失败就可能变成“隔墙看实体”（代码里有降级逻辑）。
- 如果 TV 导弹频繁用红外模式，需要评估性能与体验是否可接受。

---

## 6. 推荐的实现顺序（从能玩到好玩）

1) 做出“能玩的闭环”：

- 发射 TV 导弹 -> 切导弹相机 -> 鼠标指挥导弹 -> 命中/超时退出回载具

2) 接入 HUD 复用：

- TV 模式期间强制 `viewType=SCOPE`，复用 [VehicleScopeOverlay](file:///e:/ywzj/ywzj_vehicle/src/main/java/org/ywzj/vehicle/client/gui/VehicleScopeOverlay.java#L44-L112)

3) 接入三档滤镜：

- 彩色（无滤镜）/ 黑白（新灰度后处理）/ 红外（复用 ThermalHandler）

4) 做“手感与对抗”：

- 服务端转向限制 + 输入平滑
- 退出条件完善（断线/死亡/切座/距离）
- 可选：加入烟幕/遮挡导致的“画面噪声/信号丢失”，形成对抗空间

---

## 8. 实施步骤（按仓库实际落地的清单）

下面以“先能玩、再做体验”的顺序列可执行步骤，写到哪个目录、改哪些类都给出建议落点。

### 8.1 第 0 步：先定“要加的注册项”

1) 新增武器类型注册
- 参照 `RvpVehicleWeaponTypes` 的 `GPS_BOMB` / `anti_radiation_missile` 注册方式
- 目标：给 TV 导弹新增一个 `type`，例如 `ywzj_rvp:tv_missile`

2) 新增导弹实体注册
- 参照 `RvpEntities` 与现有实体（如 `AntiRadiationMissileEntity`）的注册方式

### 8.2 第 1 步：先把“导弹能被人控”做出来（核心闭环）

1) 新建 TV 导弹数据类（weapon data）
- 参考 `VehicleGPSBombWeaponData` 与 `VehicleAntiRadiationMissileWeaponData`
- 放在 `org.ywzj.rvp.weapon.data`

2) 新建 TV 导弹武器类（weapon）
- 参考 `VehicleGPSBomb` 与 `VehicleAntiRadiationMissile`
- 发射时生成 TV 导弹实体并绑定 owner（服务端权威）

3) 新建 TV 导弹实体类（missile entity）
- 参考 `AntiRadiationMissileEntity` 的辐射源追踪逻辑；TV 弹通过 `MissileEntityMixin` 转发 `tickGuidance` / `tickMove`
- 服务端额外逻辑：每 tick 用“最近一次收到的控制方向”去调整导弹速度向量与朝向
- 退出逻辑：控制者无效/距离过远/超时/命中爆炸 -> 结束 TV 模式（降级为惯导或直接自毁，按你的玩法选择）

### 8.3 第 2 步：做网络输入（客户端发指令、服务端收并应用）

1) 新增 C2S 包：TV 控制输入
- 放在 `org.ywzj.rvp.network`，参考 [C2SSetGpsTarget](file:///e:/ywzj_rvp/ywzj_rvp/src/main/java/org/ywzj/rvp/network/C2SSetGpsTarget.java) 与 [RvpNetwork](file:///e:/ywzj_rvp/ywzj_rvp/src/main/java/org/ywzj/rvp/network/RvpNetwork.java) 的注册方式
- 包内容建议包含：导弹 entityId + 控制者视角（yaw/pitch 或 forward 向量）+ sequence/tick
- 服务端校验：只信 packet sender；导弹必须由该 sender 所属载具/座位发射或授权

2) 服务端存储控制输入
- 最省事：把“最后控制输入”存在导弹实体字段里（收到包时找到导弹实体并写字段）
- 需要乱序/过期保护：丢弃旧 sequence；长时间没新输入则退出 TV 模式

### 8.4 第 3 步：切导弹相机（视角绑导弹，但人仍在载具上）

1) 客户端 TV 状态管理
- 建议新增一个 `RvpClientTvState`（类似现有的 `RvpClientArmState`/`RvpClientGpsState` 风格）
- 维护：是否 TV 中、当前导弹 id、当前显示模式（三档）、退出原因

2) Camera 注入覆盖
- 在 RVP 侧加一个新的 client mixin，目标是 `net.minecraft.client.Camera#setup` 的 `TAIL`
- 当 `RvpClientTvState` 处于激活且能找到导弹实体时：
  - 相机位置 = 导弹插值位置
  - 相机朝向 = 导弹插值朝向
- 这块属于“只改相机”，不改载具/座位结构

### 8.5 第 4 步：HUD 复用观瞄（开镜 HUD 但视角来自导弹）

1) 最小实现（推荐先这样）
- TV 模式期间把 `LocalVehiclePlayer.viewType` 维持为 `SCOPE`
- 这样原生会绘制 [VehicleScopeOverlay](file:///e:/ywzj/ywzj_vehicle/src/main/java/org/ywzj/vehicle/client/gui/VehicleScopeOverlay.java#L44-L112)

2) 体验增强（可选）
- TV 模式下，把 `LocalVehiclePlayer.weaponHitPos` 改成“导弹视角射线落点”，让准星/测距与导弹画面一致

### 8.6 第 5 步：三档显示模式（彩色 / 黑白电视 / 红外热成像）

1) 彩色电视
- 不启用后处理，直接显示相机画面

2) 红外热成像
- 直接复用本体 [ThermalHandler](file:///e:/ywzj/ywzj_vehicle/src/main/java/org/ywzj/vehicle/client/shader/ThermalHandler.java) 的开关

3) 黑白电视（灰黑滤镜）
- 新增一条轻量 post chain：只对画面做灰度/噪声/暗角，不需要 `thermal_buffer`
- 结构可以完全照热成像的 Handler 写，但 shader 输入只用 `DiffuseSampler`

### 8.7 第 6 步：交互与退出（必须做）

1) 退出键
- 建议新增一个键位（例如 `ESC` 以外的独立键）用于强制退出 TV 视角

2) 自动退出条件
- 导弹死亡/爆炸
- 控制者死亡或离开载具
- 距离过远或超时

3) 退出后的清理
- 关闭热成像/黑白滤镜
- 清除 TV 状态（导弹 id 等）
- 将视角状态恢复为正常（例如回到 THIRD_PERSON 或按玩家原状态恢复）

---

## 9. 武器文件示例模板（TV 导弹）

建议放到 RVP 自带的示例包里，路径与现有示例一致：

- `ywzj_rvp/src/main/resources/rvp_vehicle/data/ywzj_vehicle/weapons/<你的武器>.json`

下面是一个“可照抄改名”的模板（字段结构对齐现有导弹 JSON，例如 [anti_radar_missile.json](file:///e:/ywzj_rvp/ywzj_rvp/src/main/resources/rvp_vehicle/data/ywzj_vehicle/weapons/anti_radar_missile.json)）。TV 导弹新增字段名以 `tv_` 开头，便于和本体导弹字段区分。

```json
{
  "type": "ywzj_rvp:tv_missile",
  "name": "TV Missile",

  "damage": 120,
  "headshot_multiplier": 1.5,
  "recoil": 0.1,

  "shoot_interval": 1000,
  "max_capacity": 2,
  "reload": {
    "time": 120,
    "ammo": "ywzj_vehicle:ammo_missile"
  },

  "x_rot_max": 90,
  "x_rot_min": -90,
  "y_rot_max": 90,
  "y_rot_min": -90,

  "explosion": {
    "explode": true,
    "damage": 450,
    "radius": 8,
    "destroy_block": true
  },

  "seeker_fov": 35,
  "mass": 0.01,
  "thrust": 0.01,
  "motor_burn_time": 300,
  "drag_coefficient": 0.005,
  "max_g": 30,
  "reference_speed": 1,
  "guidance": "PRESET",
  "homing_mode": "ELECTRO_OPTICAL",

  "tv_enabled": true,
  "tv_control_range": 2000.0,
  "tv_input_send_interval_tick": 1,
  "tv_max_turn_rate_deg_per_tick": 6.0,
  "tv_timeout_tick": 200,
  "tv_video_modes": [
    "COLOR",
    "BW",
    "THERMAL"
  ]
}
```

## 7. 结论

在 YWZJ Vehicle 的现有“相机抽象 + HUD 条件渲染 + 后处理框架”基础上，实现 TV 导弹属于“组合能力”而不是“从零造引擎”：

- TV 导弹控制：参考 MCH 思想，服务端按“玩家视角方向”驱动导弹。
- 相机绑定：复用本体 Camera 逻辑，通过注入覆盖相机到导弹。
- HUD 复用：保持 `viewType=SCOPE`，直接用现有观瞄 HUD。
- 三档滤镜：彩色无处理；黑白加一个轻量灰度后处理；红外直接复用 ThermalHandler。

整体可实现，建议先做最小闭环，再逐步把 HUD 数据源与画面参考系对齐，最后再做滤镜与对抗细节。
