# RVP 机枪预瞄圈开发计划

## 目标

- 仅对 `rvp:machinegun` 启用预瞄圈。
- HUD 形态为一个小的绿色圆圈。
- 预瞄圈与当前锁定目标之间用绿色虚线连接。
- 当当前武器站为 `rvp_rf` 且当前武器为 `rvp:machinegun` 时，火控辅助中心从目标中心切换为预瞄圈。
- `rvp_rf` 的软限位手感保留，但围绕预瞄圈生效，而不是围绕敌机中心生效。

## 已确认真值

### 机枪弹发射链

- `RVP_ProjectileSpawner.spawn(...)` 用 `RVP_WeaponData.resolveMuzzleSpeed(MACHINEGUN)` 计算初速。
- 初始方向来自 `AimContext.direction`，并叠加散布。
- 弹丸生成位置优先使用 `AimContext.from`，即炮口位置。
- 如果武器配置了 `inherit_vehicle_velocity`，弹丸会额外叠加载具速度。

### 机枪弹运动链

- `RVP_BulletEntity.tickBulletMotionAndFacing()` 每 tick 执行一次运动积分。
- 当前运动学是：
  - 先按当前速度更新位置。
  - 再按 `setDeltaMovement(getDeltaMovement().scale(1 - friction))` 施加线性摩擦。
  - 最后按 `add(0, -gravity, 0)` 施加重力。
- 这里的 `friction` 来自 `RVP_WeaponData.getCannonFriction()`。
- 这里的 `gravity` 来自 `RVP_WeaponData.getCannonGravity()`。

### 预瞄圈第一版需要匹配的物理量

- 炮口位置：`RVP_AimContexts.muzzle(aim)`
- 初速：`RVP_WeaponData.resolveMuzzleSpeed(MACHINEGUN)`
- 重力：`RVP_WeaponData.getCannonGravity()`
- 阻力：`RVP_WeaponData.getCannonFriction()`
- 载具速度继承：`RVP_WeaponData.isInheritVehicleVelocity()`
- 锁定目标位置和速度：从当前 `WeaponUnit` / 主雷达锁定目标读取

## 推荐架构

### 武器数据层

- 保持由 `RVP_WeaponData` 提供弹道参数。
- 第一版不强制新增复杂 JSON 开关，先只按 `weaponKind == MACHINEGUN` 启用。
- 如果后面要细分，再补 `lead_circle_enabled` 之类的字段。

### 公共解算层

- 新增 `RVP_LeadSolver`
- 新增 `RVP_LeadSolution`
- 负责将炮口、目标、速度、重力、摩擦整合成预瞄点世界坐标。

### HUD 层

- 新增 `RVP_MachinegunLeadOverlay`
- 只负责：
  - 判断当前是否满足显示条件
  - 世界坐标投影到屏幕
  - 画绿色小圆圈
  - 画锁定目标到预瞄圈的绿色虚线

### 火控层

- 复用现有 `WeaponUnitSoftRfMixin`
- 在 `rvp_rf + machinegun` 情况下，把软限位目标点从目标中心切到 `leadWorldPos`
- 其余武器继续保持当前 `rvp_rf` 逻辑

## 执行顺序

### 1. 运动层调研

- 核对 `RVP_BulletEntity` 真实积分顺序
- 核对 `RVP_ProjectileSpawner` 的初速和载具速度继承
- 核对目标速度应取值方式
- 核对机枪预瞄是否必须考虑阻力

### 2. 设计解算接口

- 定义 `RVP_LeadSolution`
- 定义 `RVP_LeadSolver.solveMachinegunLead(...)`
- 约定输入输出字段

### 3. 实现预瞄解算

- 第一版做客户端本地解算
- 优先保证与真实弹道趋势一致
- 如果解析解不足，再使用数值迭代

### 4. 实现 HUD

- 新建独立 overlay
- 仅在 `rvp:machinegun` 且当前有锁定目标时显示
- 圆圈为小绿色圆圈
- 目标点到预瞄点之间绘制绿色虚线

### 5. 接入火控

- 在 `rvp_rf + machinegun` 下将火控目标点切到预瞄点
- 不改变其他传感器类型
- 不改变非机枪武器行为

### 6. 验证

- 静止目标
- 横向匀速目标
- 己方高速运动
- 双方同时运动
- HUD 位置与实际落点的一致性
- `rvp_rf` 火控手感是否仍可接受

## 当前执行状态

- [x] 写计划文档
- [x] 运动层调研
- [x] 解算接口设计
- [x] 预瞄解算实现
- [x] HUD 实现
- [x] 火控接入
- [x] 构建验证
