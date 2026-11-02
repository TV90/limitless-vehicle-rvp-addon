# RVP 弹体仿真

Java 测试进程直接构造游戏使用的 RVP 弹体对象，调用它们的制导执行器和运动方法，Three.js 只回放记录。**弹体气动和运动不使用虚拟飞行段积分器，也不在工具里另写一套弹道公式。**

武器只读项目根 `limitless_vehicle/rvp/data/rvp/weapons/`。游戏武器和模型资产不被修改。

## 使用

项目根 PowerShell：

```powershell

./gradlew projectileSimulation
```

输出到 `build/projectile-simulation/`。工作台已启动时刷新页面和目录，选择“RVP 弹体仿真”。首次启动：

```powershell
cd tools/three-test-workbench
npm ci
npm start
```

打开 <http://127.0.0.1:4174>。默认包含六个通用场景：XM829A5 炮弹、Hydra-70 火箭弹、MK-84 炸弹、风暴阴影 GPS 导弹、鱼-7 入水过渡、鹰击-20 冷发射与二脉冲；另有 [200 个 052D 专项场景与逐例索引](../../../../src/test/resources/projectile-simulation/README.md)，覆盖当前挂载的七种武器及尚未挂载的鱼-7 配置，共 206 个场景。

快速观察另一把武器：

```powershell
./gradlew projectileSimulation "-PprojectileWeapon=rvp:ah64_hydra70" "-PprojectileOutput=build/my-projectile-test"
```

这个快捷选项生成默认单发初态，不使用 `projectileScenario`。需要调整初态时写场景文件：

```powershell
./gradlew projectileSimulation "-PprojectileScenario=D:/my-tests/test.json" "-PprojectileOutput=build/my-projectile-test"
```

输入可以是文件或目录；目录只读直接子文件。索引只列本次成功生成的记录。`RVP_PROJECTILE_REPLAY_DIR` 可以指定服务读取另一输出目录，也支持手动导入单个回放。

**计算记录当前为 `schemaVersion: 2`，并带 `physics.implementation: "rvp-entity-flight"`。** 第一版使用虚拟段计算的旧回放需要重新生成，插件不会把它解释成实体计算结果。场景输入仍为 `schemaVersion: 1`。

## 真正执行了什么

“实体”就是游戏里实际飞行的弹体对象；“刻 / Tick”是 1/20 秒的一次游戏更新。

| 内容 | 实际生产入口 |
| --- | --- |
| 六类实体的构造与物理默认值 | `RVP_ProjectileEntityFactory`、真实弹体构造器、`RVP_WeaponData` 构造器及生产 Gson 字段适配器 |
| 发射初态 | `initFromWeapon` → 平台速度快照与叠加 → `initColdLaunch` → `finalizeSpawnOrientation`；高初速火箭初始化规则也由真实实体执行 |
| 普通弹体、导弹、火箭和布撒弹 | 真实对象的 `tickMotion`，包含各自动态分派 |
| 炮弹 | `RVP_BulletEntity.tickBulletMotionAndFacing`；保留先移动、后摩擦/重力、旋转插值及水中分支 |
| 炸弹 | `RVP_BombEntity.tickMotion`；保留默认重力补偿和实际父类调用顺序 |
| 鱼雷 | `RVP_TorpedoEntity.tickMotion`；包含有效入水深度、入水速率过渡、水中深度阻尼与出水回退 |
| 推进、气动与姿态 | 实体实际调用的 `RVP_ProjectileMotion`；包含推力曲线、变质量、冷发射、二脉冲四种触发、阻力、重力、恒速、速度限制、转向能耗、机头角速度与姿态 |
| 制导执行器 | `RVP_GuidanceRuntimeController`、`RVP_GuidanceRuntimeMath`；保留时间/距离/角度门控、GPS 巡航/PRESET、预测拦截、攻角、直控运动和末端相位交接 |
| 风漂和部署 | 实体 `captureWindDirectionFromParent`、`initializeSubmunitionDeploymentMotion` 和实际运动分支；保留固定北向风、母弹反向风、扰动、独立水平/垂直半衰期 |
| 已激活的智能引信运动 | 显式提供接管初态时，执行实体 `tickSmartFuseGuidance` 与对应运动分支 |

攻角是“机头朝向和速度方向之间的夹角”；二脉冲是主发动机燃尽后按条件再次提供推力；PRESET 是预设上升、巡航、俯冲路线。PIP 指预测拦截点，也就是瞄准目标预计会到的位置；HITL 指由玩家持续参与操控的制导。它们的状态和计算都保留在真实实体中。

记录直接读取实体的位置、速度、Java 原始姿态、机头角速度、气动载荷、寿命、航程、发动机状态、水状态和制导相位。炮弹原始角度与导弹符号不同，绘图时按生产含义换成工作台统一角度，原始值仍可查看。

## 外部环境边界

目标观测、平台初态、平地和水面是场景输入。**GPS 使用生产坐标制导源；其他类型由场景提供理想目标意图。** 后续的生产制导执行器和实体运动不被替换。这里不验证真实雷达/红外/激光搜索、锁定、反制、遥控链路，也不运行制导源内部如何从传感器生成意图的流程。

环境不启动 Minecraft 客户端或完整服务器循环：只提供固定地面、水平水面、目标脚本与“区块已就绪”的查询。没有真实流场、坡道、区块加载等待或载具驾驶。平台主轴只用于发射时的冷发射局部坐标转换。

未运行碰撞伤害、穿透、跳弹、引信探测与触发、爆炸或子母弹释放。可以显式输入子体初速度、母弹释放姿态、部署分量，或已经激活的智能引信接管点，观察这些初态之后的实体运动。

地面和目标球只决定试验何时停止：对完整运动段检测，**不裁剪最后一帧的位置，不修改实体速度或航程**。因此末帧可能已越过地面或目标球；事件说明这一刻的运动段曾接触它们，不等同于真实游戏命中。

支持 `missile`、`rocket`、`machinegun`、`bomb`、`torpedo`、`dispenser` 六类实体。激光和吊舱没有飞行弹体，不进入这个计算器。

## 场景初态

默认场景在 `src/test/resources/projectile-simulation/`。新增普通测试只增加场景 JSON：

```json
{
  "schemaVersion": 1,
  "id": "my_missile",
  "name": "我的实体运动测试",
  "description": "相同初态观察当前权威配置",
  "weapon": "rvp:rafale_storm_shadow",
  "ticks": 600,
  "position": [0, 164, 0],
  "yaw": 0,
  "pitch": 5,
  "vehicleVelocity": [0, 0, 40],
  "groundY": 64,
  "target": [100, 70, 1200],
  "targetRadius": 2,
  "guidance": true,
  "seed": 721
}
```

| 字段 | 单位 / 默认 | 含义 |
| --- | --- | --- |
| `schemaVersion` | 1 | 当前场景格式 |
| `id`、`name`、`weapon` | 必填 | 稳定文件标识、显示名、`rvp:文件名`；不接受跳转或备份目录 |
| `description` | 默认测试说明 | 希望观察什么 |
| `ticks` | 刻；600，范围 1..20000 | 最长步数，20 刻/秒 |
| `position` | 格；`[0,164,0]` | 发射世界坐标，必须高于地面 |
| `yaw`、`pitch` | 度；0 | 航向 0 朝 +Z、90 朝 -X；正俯仰抬头 |
| `vehicleVelocity` | 格/秒；`[0,0,0]` | 按武器 `inherit_vehicle_velocity` 生效 |
| `initialVelocity` | 格/秒；null | 可选的生成上下文初速度；仍由真实 `initFromWeapon` 处理，不是强制覆盖初始化后的实体速度 |
| `carrierRotation` | 度；`[0,0,0]` | 平台航向/俯仰/滚转，用于冷发射轴 |
| `coldLaunchTicks` | 刻；0 | 挂架冷发射时长，不覆盖武器点火延迟；真实实体取两者最大值 |
| `coldLaunchVelocity` | 格/秒；`[0,-20,0]` | 挂架本地冷发射速度，沿用本体局部轴 |
| `parentRotation` | 度；null | 母弹释放时的航向/俯仰/滚转观测，用于捕获母弹反向风 |
| `parentVelocity` | 格/秒；`[0,0,0]` | 母弹释放速度，供生产风向解析的退化回退使用 |
| `deploymentVelocity` | 格/秒；null | 布撒生成的初始分量；半衰期仍读取武器 JSON |
| `smartFuseTarget` | 格；null | 显式提供已接管的智能引信目标点；不模拟它如何被触发 |
| `groundY` | 世界 Y，格；64 | 平地高度 |
| `waterY` | 世界 Y，格；null | 可选水平水面，必须高于地面；鱼雷使用真实入水/出水判定 |
| `target` | 格；null | 初始目标中心，可选 |
| `targetVelocity` | 格/秒；`[0,0,0]` | 目标脚本初速度 |
| `targetAcceleration` | 格/秒²；`[0,0,0]` | 目标脚本加速度，生产拦截器读取同一 Entity 的观测 |
| `targetRadius` | 格；2，正数 | 试验停止球半径，不是武器引信半径 |
| `guidance` | true | 把场景目标观测交给真实制导执行器；false 只运行实体自由运动 |
| `seed` | 721 | 固定真实实体随机源和身份；GPS 误差继续遵守生产配置 |

第一帧是初始化完成的实体初态，第 t 帧是 t 次实体步骤后的原值。速度面板统一显示格/秒。每份记录保存输入场景、原始武器 JSON 和 SHA-256 文件指纹：相同文件内容得到相同指纹。修改武器配置后重新运行任务，旧回放不会自动改变。

## 维护与验证

主体的播放、时间轴、相机和服务无需修改。实体运行与环境在测试侧；生产代码只抽出原有的单刻临时状态准备，并增加可传入外部目标观测的制导入口，游戏默认入口继续使用生产制导源。

```powershell
# 项目根，使用上面的 Java 环境
./gradlew build projectileSimulation

# 工作台目录
npm run check
npm test
```

`RVP_EntityFlightParityTest` 对六种参考实体直接调用完整生产 `tick()`。参考子类仅隔离世界、网络和目标观测，没有覆写 `tickMotion` 或炮弹专属运动。每刻与独立调度严格比较位置、速度、原始姿态、机头角速度、航程、寿命、发动机与水状态。覆盖空气/水中、推进/无推进、恒速、旋转开关、GPS/PIP/攻角/直控、冷发射、四种二脉冲触发、部署、母弹风漂、小质量阻力与智能引信接管。特殊分支必须实际发生，不能只比较未触发路径。

另有当前权威武器逐个创建/步进、文件变化与摘要、输入边界、停止条件不改物理状态、重复运行一致性，以及禁止工作台导入虚拟段积分器的回归。Node 测试验证第 2 版格式、真实 Java 记录、自动目录、图形与倒拖恢复。

2026-11-02 验证：全量构建通过，1308 项 Java 测试全部通过（含 46 组完整实体 Tick 对照），36 项工作台测试通过；六个默认场景跨 Java 进程重新生成的 JSON 完全一致。浏览器核对了二脉冲燃烧、鱼雷入水和时间轴倒拖，控制台无警告或错误。

同日扩充 052D 场景后：`build projectileSimulation` 通过，1354 项 Java 测试通过（另增 46 项 052D 专项断言），206 份回放均成功生成；200 个新增场景逐例重复运行一致，36 项工作台测试覆盖全部新记录。当前舰上七种武器及未挂载的鱼-7 配置、场景逐例目的和已知行为边界见 [052D 场景索引](../../../../src/test/resources/projectile-simulation/README.md)。
