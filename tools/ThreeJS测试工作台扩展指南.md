# Three.js 测试工作台与扩展指南

2026-11-02。入口：[tools/three-test-workbench](../tools/three-test-workbench/README.md)。旧工具 `tools/gunner-replay/` 保持原样。

## 1. 设计目标

**开闭原则**在这里的意思是：需要新能力时，加一个独立模块；已经稳定的播放、相机和时间轴代码通常不用修改。

工作台把职责分成三个部分：

| 部分 | 负责什么 | 扩展时怎么做 |
| --- | --- | --- |
| 主体 `core/`、`main.js` | 校验统一记录、采样、播放、相机、对象选择、通用图形、事件与结果显示 | 普通测试不用改 |
| 插件 `plugins/<id>/` | 解释一种测试格式、提供专用模型和观察值说明 | 新领域增加目录，自动发现 |
| 测试进程与结果 JSON | 执行算法、记录逐刻状态、生成统计和断言 | Java、Node 或其他程序都可生成 |

**适配器**就是把测试自己的字段翻译成工作台认识的“对象、位置、观察值和事件”的小模块。主体没有 `if (pluginId === 'gunner')` 之类的业务分支，新增领域不需要把所有类型继续塞到大文件里。

本次只新增工具和文档；没有改动旧工具、游戏逻辑、本体源码、Mixin 或载具包。Gunner 插件直接读取当前 `gunnerSimulation` 输出；解析抛体插件入口则读取统一格式，展示另一领域如何独立接入。

## 2. 运行

需要 Node.js 22+。在项目根目录：

```powershell
cd tools/three-test-workbench
npm ci
npm start
```

浏览器打开 <http://127.0.0.1:4174>。只监听本机，安装依赖后无需外部脚本服务。默认端口与旧工具的 4173 分开；可在启动前设置 `$env:PORT='4175'`。

自带的解析抛体示例无需 Java 构建。要生成 Gunner 数据，回到项目根目录：

```powershell
$env:JAVA_HOME='C:\Users\FishKing0721\.jdks\ms-17.0.16'
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=D:\WgameProject'
./gradlew gunnerSimulation
```

点“刷新目录”后就能选择新记录。Gunner 插件清单声明 `rootEnv: "RVP_REPLAY_DIR"`，因此也能在启动前设置 `$env:RVP_REPLAY_DIR='D:/my-recordings'`。其他插件是否提供环境变量入口由自己的清单决定，服务没有写死这些变量。

## 3. 最常见的新增测试：只加数据

对于同一种算法的新场景，继续使用已有测试进程生成记录，再把记录加入该数据源的 `index.json`。以 Gunner 为例，新增 `src/test/resources/gunner-simulation/*.json` 后运行原 `gunnerSimulation` 即可，它会生成索引。

对其他测试，建议直接输出第 5 节的统一格式。可以把新文件放到 `plugins/standard/records/`，并在其 `index.json` 的 `replays` 中增加：

```json
{"id":"my_test","name":"我的测试","file":"my_test.json"}
```

也可以在 `plugins/standard/plugin.json` 的 `sources` 增加另一个输出目录。这两种方式只改数据或配置，`core/`、页面、服务逻辑都不用改。生成的记录也支持通过“导入 JSON”单独打开。

`index.json` 只接受当前版本：

```json
{
  "schemaVersion": 1,
  "replays": [{"id":"my_test","name":"我的测试","file":"my_test.json"}]
}
```

`id` 在同一来源内唯一；`file` 相对于该数据源目录，允许子目录，不允许逃出目录。大批生成结果建议放在 `build/<your-test>/`，避免把运行产物提交进仓库。

## 4. 特殊格式、模型或面板：新增插件

在 `tools/three-test-workbench/plugins/my-test/` 中新增 `plugin.json` 和 `adapter.js`。刷新目录即可发现，不用修改一个中央注册列表。修改已加载的 JavaScript 后刷新浏览器页面，使浏览器重新载入模块。

清单示例：

```json
{
  "schemaVersion": 1,
  "id": "my-test",
  "name": "我的独立测试",
  "module": "adapter.js",
  "sources": [{
    "id": "results",
    "root": "build/my-test",
    "index": "index.json",
    "hint": "请先运行本测试的数据生成命令"
  }]
}
```

| 清单字段 | 含义 |
| --- | --- |
| `schemaVersion` | 清单格式版本，当前为 1 |
| `id` | 与插件目录同名；小写字母、数字、下划线和连字符，首位为字母或数字 |
| `name` | 页面中的分组名称 |
| `module` | 相对于插件目录的 `.js` 模块，默认导出插件对象 |
| `sources` | 数据源数组；只提供手动导入时可以为空 |
| `sources[].id` | 插件内唯一的数据源 id |
| `sources[].root` | 相对于项目根目录的数据目录，不能用 `..` 逃出项目 |
| `sources[].index` | 相对于数据目录的索引 JSON |
| `sources[].rootEnv` | 可选；主动声明允许哪个环境变量覆盖目录，覆盖后可指向项目外 |
| `sources[].hint` | 可选；数据未生成时给使用者的说明 |

插件默认导出接口：

| 字段/方法 | 必需 | 作用 |
| --- | --- | --- |
| `apiVersion: 1`、`id` | 是 | 插件接口版本和与清单相同的标识 |
| `accepts(data)` | 是 | 同步返回是否能读取这份原始数据；建议用明确的格式标识判断 |
| `adapt(data)` | 是 | 返回第 5 节统一格式，可以返回 Promise，即异步处理完成后的结果 |
| `inspect({sample, object, replay})` | 否 | 返回 `[{label, value}]` 中文观察值；默认显示 `sample.values` |
| `renderers` | 否 | 专用图形工厂表，例如 `{vehicle: createVehicle}` |

`sample` 是当前已发生的原始采样快照，`object` 是对象定义，`replay` 是统一记录。面板不接收 HTML。适配器应只解释和校验数据，不在浏览器里重写 Java 生产算法来假装已经运行过测试。

图形工厂写法可直接参考 [Gunner 模型](../tools/three-test-workbench/plugins/gunner/vehicle.js)：

```javascript
/** 返回局部图形，位置与姿态由工作台统一施加。 */
export function createProbe({ THREE, object }) {
  const root = new THREE.Group();
  const mesh = new THREE.Mesh(
    new THREE.BoxGeometry(...object.size),
    new THREE.MeshStandardMaterial({ color: object.color }),
  );
  root.add(mesh);
  return {
    /** 必须提供的局部图形树。 */
    root,
    /** 可选：只按当前快照更新，倒拖也必须得到同样外观。 */
    update({ sample, selected, tick }) {},
    /** 可选：清理插件自己的计时器、监听器等非图形资源。 */
    dispose() {},
  };
}
```

在插件中注册 `renderers: {probe: createProbe}`，记录中的 `renderer` 填 `my-test:probe`。主体传入自己的 `THREE`，无需从旧工具或外部脚本服务取依赖。主体统一释放返回图形树的几何、材质和纹理；工厂返回前如果抛错，由工厂清理自己尚未返回的资源。不要把插件状态存到通用主体里。

插件 id、绘制器名称与接口不合法会给出提示。某个插件或数据源失败时，其他可用插件继续工作。同一个导入文件被多个插件接管时，用户可以在“导入格式”里明确选择，不按扫描顺序猜测。

## 5. 统一回放格式

`schema` 就是数据格式约定。统一记录只接受 `format: "rvp-test-replay"`、`schemaVersion: 1`，不提供历史键别名或迁移代码。最小的单帧记录如下，可直接保存为 JSON 后导入：

```json
{
  "format": "rvp-test-replay",
  "schemaVersion": 1,
  "id": "minimal",
  "name": "最小观察测试",
  "description": "静态对象与一个观察值",
  "tickRate": 20,
  "groundY": 0,
  "objects": [{
    "id": "probe", "name": "观察点", "renderer": "sphere",
    "color": "#efc77a", "size": [2, 2, 2]
  }],
  "obstacles": [],
  "frames": [{
    "tick": 0,
    "objects": [{
      "id": "probe", "active": true, "position": [0, 5, 0],
      "yaw": 0, "pitch": 0, "roll": 0,
      "values": {"高度说明": "对象原点离地 5 格"}
    }]
  }],
  "events": [],
  "summary": [],
  "checks": [],
  "coverage": ["静态数据导入示例"],
  "limitations": ["未执行游戏算法"]
}
```

| 字段 | 单位、约束与作用 |
| --- | --- |
| `id` / `name` / `description` | 稳定标识、显示名称和测试说明；id 规则同插件 |
| `tickRate` | 每秒刻数，有限正数且不大于 1000；没有隐式默认值 |
| `groundY` | 世界地面高度，格；显示时只平移 Y，原始坐标仍按世界坐标展示 |
| `objects` | 1..256 个对象定义，每个 id 唯一 |
| `objects[].renderer` | 内置 `box`、`sphere`，或 `插件id:绘制器名称`；找不到专用绘制器会报错 |
| `objects[].color` / `size` | `#RRGGBB` 六位颜色；正数 X/Y/Z 尺寸，格；内置图形底部中心对齐对象原点 |
| `objects[].range` | 可选，默认不画；非负范围半径，格；勾选对象范围时显示 |
| `objects[].attributes` | 可选静态扩展信息，例如阵营、模型类型，有限 JSON 数据 |
| `obstacles` | 静态方盒数组，每项 `name`、`min`、`max`、`color`；两个三元组是世界坐标，各轴 min 小于 max |
| `frames` | 1..100001 帧，第一帧 tick=0，之后刻号严格递增；**支持稀疏采样**，最后一帧决定时长 |
| `frames[].objects` | 每帧每个已定义对象恰好一个快照，可改变排列顺序；未出场/已离场用 `active=false` 保留快照 |
| 快照 `position` | 世界 X/Y/Z，格；是对象的局部原点 |
| 快照 `yaw` / `pitch` / `roll` | 角度，单位度；Minecraft 航向 0 朝 +Z、90 朝 -X；正俯仰抬头，正滚转右倾 |
| 快照 `targetId` | 可选，默认无目标；null 或已有对象 id，供通用目标连线使用 |
| 快照 `values` | 必填 JSON 对象，可包含数值、布尔、文字、数组或嵌套对象；所有数值有限，嵌套不超过 12 层 |
| `events` | 按 tick 非递减排列，每项有 `tick`、`type`、`message`；可选 `objectId`，默认表示场景事件；允许发生在两个采样刻之间 |
| `summary` | 整场统计，`[{label, value}]`；值为文字、布尔或有限数值 |
| `checks` | 自动断言，`[{name,status,message}]`；status 为 `passed`、`failed`、`info`；空数组表示未提供自动判定 |
| `coverage` / `limitations` | 必填文字数组，说明真正执行的算法和简化环境边界 |

位置与姿态在相邻且均在场的快照间平滑插值；姿态采用最短转角。业务观察值、目标和在场状态使用不晚于播放头的采样值，不把下一帧的行为提前显示。对象出场或离场边界不会跨状态插值，轨迹也不会把两次出场接成一条直线。

事件面板只显示播放头之前发生的事件，最多显示最近 60 条；时间轴刻线、统计和自动断言代表整场，界面有明确说明。轨迹只画到当前时间，倒拖会同步收回。失败的导入或图形工厂不会替换上一份成功回放；快速切换场景时过期请求不能覆盖较新的选择。

## 6. 已接入内容与边界

| 插件 | 数据来源 | 特殊能力 / 边界 |
| --- | --- | --- |
| `gunner` | `build/gunner-simulation/index.json` 与原始场景记录 | 专用载具/飞机图形、阶段与操纵输入面板；接入范围沿用原记录，未额外运行游戏物理或真实武器 |
| `standard` | `plugins/standard/records/`；也可配置其他来源 | 直接读取统一格式，通用图形与观察值；解析示例只是数学公式演示，没有调用 RVP 弹道 |

工作台读取测试进程已经生成的结果。新增 Java 算法测试仍需在测试侧调用生产代码并导出观察值；测试入口、场景生成与断言由该测试自身维护，不要求统一塞回 Gunner 仿真类。Gunner 原始记录没有断言字段，因此新工具只展示原始统计，不自行声称测试“通过”。

回放 JSON 在本机读取，不上传；可执行模块只来自本地 `plugins/`，记录自身不能指定 JS 模块。HTTP 服务只提供页面、插件模块/图像、Three.js 模块和清单中列出的记录，不提供项目其他文件。路径检查同时覆盖 `..`、Windows 分隔符和符号链接，文件上限 50 MB。WebGL 不可用时仍可看时间轴、数据和事件。

## 7. 验证与维护入口

```powershell
# 在新工具目录
npm run check
npm test

# 在项目根目录
$env:JAVA_HOME='C:\Users\FishKing0721\.jdks\ms-17.0.16'
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=D:\WgameProject'
./gradlew build gunnerSimulation
```

| 文件 | 用途 |
| --- | --- |
| `core/replay.js` | 统一格式校验、一次性索引、时间采样与角度插值 |
| `core/plugins.js` | 插件接口、识别冲突与绘制器命名空间 |
| `core/view.js` | 通用三维画面、资源生命周期、轨迹、相机与选择 |
| `lib/catalog.mjs` / `server.mjs` | 自动发现、本地数据来源与 HTTP 边界 |
| `plugins/gunner/` | 当前 Gunner 格式适配、专用模型和观察面板 |
| `plugins/standard/` / `scripts/generate-demo.mjs` | 通用格式入口与可重复生成的独立示例 |
| `test/` | 输入、采样、动态扩展、实际 Java 记录和 HTTP 验证 |

自动扩展回归会在临时目录中启动服务，再新增一个独立插件和测试条目，验证刷新后自动发现并能通过插件读取。测试不改主体文件，也不依赖提前注册插件，直接覆盖本工具的核心设计目标。

2026-11-02 验证：新工具 33 项 Node 测试全部通过，无跳过；`npm ci`、全部 JavaScript 语法检查及项目 `build gunnerSimulation` 成功。浏览器验证了 Gunner 场景切换、遇敌后倒拖恢复、固定翼模型与跟随，以及独立解析示例的播放、事件跳转和稀疏采样；浏览器无警告或错误。旧工具 11 个源码/配置/测试文件的 SHA-256 校验值与修改前一致。
