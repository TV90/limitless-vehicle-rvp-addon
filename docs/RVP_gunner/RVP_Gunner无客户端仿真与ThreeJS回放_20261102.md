# Gunner 无客户端仿真与 Three.js 回放（2026-11-02）

状态：**已实现。** Java 按刻执行当前生产算法，导出确定的 JSON，浏览器按同一份记录回放。无需启动 Minecraft 客户端、专用服务端或 Forge 世界。首次构建仍须准备项目的 Gradle 依赖。

关联：[当前 A* 规划与现状](RVP_Gunner通用AStar寻路设计计划_20261102.md)、[公共固定翼控制器](RVP通用固定翼飞行控制器与执行器设计计划_20261102.md)。

## 1. 哪些是真实算法，哪些是模拟环境

“无客户端”是指 Java 测试进程直接向算法提供数值观察，再把操作交给测试环境计算下一刻的位置。它不创建游戏世界，不调用渲染器。

| 部分 | 本次实际接入 | 使用边界 |
| --- | --- | --- |
| 地面移动 | `RVP_GunnerGroundPolicy`：巡逻、大转弯、接敌、停车、侧移、发射架定位、卡住检测与倒车 | 从 `RVP_BuiltinGunnerBehaviors` 原算法抽取；游戏行为也转发到同一个类，保留独立行为状态、Profile 参数和原动作边界 |
| 移动意图仲裁 | `RVP_GunnerIntentArbiter` | 同一刻多个行为提出操作时，用生产仲裁器确定唯一胜者；仿真使用注册表当前默认优先级与固定顺序，没有执行完整 Profile 计划管理器 |
| 目标评分 | `RVP_GunnerTargetScore` | 与 `GunnerTargeting` 共用“距离 + 夹角弧度 × 32”的同层评分，发射架只比较距离；完整敌我、雷达、隐身、组网和目标优先层级没有在仿真里运行 |
| 公共固定翼 | `RVP_GunnerFlightPolicy` → `RVP_FixedWingFlightController` → `RVP_FlightExecutor` | 仿真显式使用新公共飞控分支，**没有打开游戏中默认关闭的开关**；旧固定翼驾驶与旋翼不在此次覆盖内 |
| 感知 | 场景阵营、感知半径、可选视线、定期扫描 | 用于构造遇敌条件；不能据此判定真实雷达识别距离或隐身效果 |
| 地面物理 | 平地、有限加减速、随前后档改变方向的转向、保守方盒碰撞 | 不含本体实体物理、部件模型、坡道、水面、侧滑、损伤、多车互撞 |
| 固定翼物理 | 沿用已有固定翼回归的试验机参数与气动积分顺序 | 是数值测试模型，不是本体真实实体；不含滑跑、部件损伤、风或实际区块 |
| 武器 | 模拟距离/车头角度/间隔/视线门控，记录 `FIRE_ATTEMPT` | 不运行真实瞄准、锁定、制导、弹体、伤害和补给；“开火尝试”不代表游戏中实际发射或命中 |

**当前地面 Gunner 没有 A* 绕障搜索。** 薄墙场景用于观察按方向行驶与倒车的现有结果，不以“能够绕墙成功”作为验收条件。新增真正寻路后，应接到这个逐刻环境中进行同初态比较。

此次没有修改本体源码、载具包资产、结构模型、Mixin 或游戏飞控开关。代码中新增的仿真环境、场景和导出入口都放在测试侧，不进入发布 jar。

## 2. 运行

在项目根目录的 PowerShell 中运行：

```powershell
$env:JAVA_HOME='C:\Users\FishKing0721\.jdks\ms-17.0.16'
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=D:\WgameProject'
./gradlew gunnerSimulation
```

输出位于 `build/gunner-simulation/`：五个场景回放 JSON，以及页面自动读取的 `index.json`。这一步只有 Java 控制台进程，不需要图形窗口。

启动回放工具（Node.js 22+）：

```powershell
cd tools/gunner-replay
npm ci
npm start
```

打开 <http://127.0.0.1:4173>。首次 `npm ci` 安装固定版本的 Three.js；后续只需 `npm start`。网页与依赖都由本地服务提供，不依赖外部 CDN（在线脚本分发服务）。`Ctrl+C` 停止服务。不要直接双击 HTML；浏览器模块与自动读取 JSON 需要 HTTP 服务。

仅生成某个场景：

```powershell
./gradlew gunnerSimulation "-PgunnerScenario=src/test/resources/gunner-simulation/03_blocked.json"
```

自定义输入、输出：

```powershell
./gradlew gunnerSimulation "-PgunnerScenario=D:/my-scenarios/test.json" "-PgunnerOutput=build/my-gunner-recording"
```

`gunnerScenario` 接受一个场景文件或目录。目录中的 `.json` 按文件名排序；重复场景 id 会报错，不能静默覆盖。每次 `index.json` 列出**本次生成**的回放，不自动删除输出目录中的其他文件。另一路输出可在网页手动导入，或通过 `$env:RVP_REPLAY_DIR='D:/完整输出路径'` 指定服务读取目录。

## 3. 默认场景

| 文件 | 时长 | 观察重点 |
| --- | --- | --- |
| `01_patrol.json` | 800 刻 / 40 秒 | 无敌巡逻，大转弯时停车旋转，恢复前进 |
| `02_encounter.json` | 1000 刻 / 50 秒 | 敌车第 160 刻出场、第 700 刻离场；发现、接近、停车侧移、恢复巡逻；友军不成为目标 |
| `03_blocked.json` | 1200 刻 / 60 秒 | 薄墙前受阻和倒车抢占；该场景允许模拟感知看到墙后目标，开火仍要求无遮挡 |
| `04_launcher.json` | 800 刻 / 40 秒 | 第 120..519 刻模拟缺弹走位，其他时间有弹停车；恢复过程仍按生产优先级仲裁 |
| `05_fixed_wing.json` | 2400 刻 / 120 秒 | 新公共飞控分支的巡逻、遇敌、攻击、脱离、越界返回；包含有限目标记忆 |

## 4. 修改场景

源文件位于 `src/test/resources/gunner-simulation/`。修改测试条件后重新运行 `gunnerSimulation`，不要手工改导出轨迹来伪造算法结果。

一个最小场景：

```json
{
  "schemaVersion": 1,
  "id": "my_encounter",
  "name": "我的遇敌测试",
  "description": "敌车 5 秒后出现",
  "seed": 721,
  "ticks": 1000,
  "groundY": 64,
  "obstacles": [],
  "actors": [
    {"id": "blue", "name": "受测车辆", "position": [0, 64, 0]},
    {"id": "enemy", "name": "敌方车辆", "team": "red", "mode": "scripted",
     "position": [0, 64, 80], "spawnTick": 100, "despawnTick": 800}
  ]
}
```

常用对象字段：

| 字段 | 单位 / 默认 | 含义 |
| --- | --- | --- |
| `mode` | `ground` | `ground` 地面 Gunner；`launcher` 发射架；`fixed_wing` 公共固定翼；`scripted` 按脚本运动的车辆目标 |
| `team` | `blue` | 相同阵营互不作为模拟敌人 |
| `position` / `velocity` | 格 / 格每秒 | X/Y/Z 位置与脚本目标速度；脚本位移从出场刻起计算 |
| `yaw` / `speed` | 度 / 格每秒，默认 0 | Minecraft 航向：0 朝 +Z，90 朝 -X；固定翼场景须提供合理初速 |
| `spawnTick` / `despawnTick` | 刻，默认 0 / 不离场 | 出场包含端点，离场不包含端点；尚未在场的目标不会被感知 |
| `radius` / `height` | 格，默认 1.5 / 3 | 测试碰撞体宽度的一半和高度；按保守方盒检查整段运动，避免跨过薄墙 |
| `maxSpeed` / `acceleration` | 格每秒 / 格每秒²，默认 8 / 5 | 地面测试物理参数；后退上限是前进的 0.5 倍，属于夹具参数 |
| `turnDegPerTick` | 度每刻，默认 2 | 地面测试转向能力；左右输入同时为真时互相抵消 |
| `searchRadius` / `scanInterval` | 格 / 刻，默认 200 / 10 | 简化感知半径和扫描节奏；扫描从出场刻开始 |
| `requireLineOfSight` | 默认 false | true 时感知也要求方盒视线无阻；模拟开火始终要求视线无阻 |
| `reloadFrom` / `reloadUntil` | 刻，默认不装填 | 模拟缺弹时间段，左包含右不包含；用于发射架定位与开火门控 |
| `fireRange` / `fireWindowDeg` / `fireInterval` | 格 / 度 / 刻，默认 100 / 6 / 20 | 模拟开火条件，不代表本体武器数据 |
| `altitudeMin` / `altitudeMax` / `combatRadius` | 格，默认 150 / 350 / 550 | 固定翼最低/最高离地高度与作战半径；不是修改游戏开关 |
| `ground` | 见下表 | 共享地面策略参数；完整记录会保存所有默认值 |

`ground` 若省略，使用下面这一整组测试默认值。要覆盖时应提供完整对象；它不读载具包的 Gunner Profile，不宣称正在测试某份已部署 JSON。

```json
{
  "stopDistance": 12,
  "holdTick": 100,
  "evadeMin": 140,
  "evadeMax": 280,
  "evadeYaw": 55,
  "stuckCheckTick": 20,
  "stuckDistance": 1,
  "recoveryTick": 20,
  "turnIntervalMin": 300,
  "turnIntervalMax": 600,
  "turnAngleMin": 120,
  "turnAngleMax": 180,
  "turnDuration": 40
}
```

以上分别对应停车距离、停车时间、侧移时间范围/偏角、卡住检查间隔/最小位移/倒车时间、巡逻大转弯间隔/角度/持续时间。时间单位刻，距离单位格，角度单位度。生产原有的停车计时与侧移计时**同时递减**，本次没有改成先停完再完整计一次侧移。

障碍写作 `{"name":"墙", "min":[-30,64,35], "max":[30,74,36]}`，两个角都是世界坐标。场景仅接受当前 `schemaVersion=1`，非法坐标、重复对象、零扫描周期等会报出直接可读的错误。

## 5. 记录与回放的时序

每秒 20 刻。记录第 0 刻到第 `ticks` 刻，因此 1200 刻积分产生 1201 帧。

- 第 t 帧的坐标是 t/20 秒时的观察，命令用于接下来的 `[t,t+1)` 区间；最后一帧只观察/决策，不再积分。
- 地面策略的实体年龄从 1 开始，即回放第 0 刻对应 `tickCount=1`，避免构造出实体年龄 0 的额外卡住检查。晚出场对象同样从年龄 1 开始。
- 每刻先更新所有脚本目标并为全部受控对象规划，之后统一积分；后一个对象不会读取到前一个对象的下一刻位置。
- `blocked` 表示上一次积分被阻挡。对象已离场时保留数值快照，但 `active=false`，三维画面不显示它。
- `seed` 加对象 id 派生独立随机源；没有当前时间或机器耗时进入 JSON。同场景、同种子重复运行可逐字节比较。这里固定的是测试随机序列，不声称与某个真实实体的随机种子相同。
- 浏览器平滑插值位置、速度和姿态；目标、行为、按键与事件取当前整数刻，倒拖时不会残留未来状态。已走轨迹只画到当前刻，时间轴上的事件刻线和全场汇总明确表示整场信息。

回放顶层包含：`schemaVersion`、`tickRate`、完整 `scene`、真实算法范围 `coverage`、限制 `limitations`、连续 `frames` 和 `summary`。每帧对象含坐标、航向/俯仰/倾斜、速度、目标 id、胜出行为、阶段、完整运动输入、受阻/弹药/模拟开火标志，以及落选原因或飞控反馈。事件类型包括出场、离场、目标变化、阶段变化、受阻、装填与模拟开火。

前端只接受当前版本，导入前校验完整记录。文件损坏时显示错误并保留原有回放；单个文件上限 50 MB。此工具不上传 JSON，导入文件只在本地浏览器中读取。

## 6. 开发入口与验收

| 入口 | 作用 |
| --- | --- |
| `src/main/java/.../entity/gunner/ai/RVP_GunnerGroundPolicy.java` | 游戏与仿真共享的地面数值策略 |
| `src/main/java/.../entity/gunner/ai/RVP_GunnerTargetScore.java` | 游戏与仿真共享的同层评分公式 |
| `RVP_BuiltinGunnerBehaviors` 地面适配方法 | 从真实载具/行为参数复制观察，转发策略结果给原移动意图 |
| `src/test/java/.../entity/gunner/simulation/` | 场景、逐刻驱动、测试世界、测试飞机、JSON 入口与集成回归 |
| `tools/gunner-replay/` | 本地 Three.js 回放网页、静态服务和数据合同测试 |

```powershell
# 项目完整构建与 Java 自动测试
$env:JAVA_HOME='C:\Users\FishKing0721\.jdks\ms-17.0.16'
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=D:\WgameProject'
./gradlew build gunnerSimulation

# 浏览器侧记录合同、时间采样和服务测试
cd tools/gunner-replay
npm run check
npm test
```

自动回归验证：停车与侧移的原计时；严格角度/距离边界；倒车持续刻数；有弹发射架不新触发倒车；固定种子完整输出一致；敌车出场/离场与友军过滤；薄墙不穿透且真实出现脱困抢占；两分钟公共固定翼记录有限且任务变化；命令行输出可读；浏览器能读取 Java 的实际记录；时间轴前后采样、角度跨 ±180°、离场边界和坏数据拒绝。

2026-11-02 验证：项目完整 `build gunnerSimulation` 成功，JUnit 1254 项通过；回放工具 5 项 Node 测试通过。浏览器实测已加载 Java 记录，验证拖到遇敌阶段、倒拖恢复无目标、逐刻后退、场景切换、俯视/跟随和事件跳转。运行结果不是对完整 Minecraft 物理或真实武器命中的保证，实机调参仍需另行验收。

Three.js 通过本地 npm 依赖提供；安装方式与控制器参考：[官方安装说明](https://threejs.org/manual/en/installation.html)、[OrbitControls](https://threejs.org/docs/pages/OrbitControls.html)（鼠标旋转、缩放与平移相机的组件）。
