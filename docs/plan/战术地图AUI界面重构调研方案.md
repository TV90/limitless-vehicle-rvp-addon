# 战术地图 AUI（ApricityUI）界面重构调研方案

> 状态：**纯调研，未实施**。本文档回答两个问题：
> 1. 用 AUI 重写战术地图的外围 UI（框、按钮、页面），但**不动地图本体的显示逻辑**，是否可行？
> 2. 战术地图功能到底涉及哪些前端页面/文件？
>
> 结论先行：**可行，且项目内已有成功先例**（`RVP_AuiVariantScreen`）。地图本体渲染约 3000 行可零改动迁移；需要重写的只有边框绘制、10 个自绘按钮、3 个输入框、两套右键菜单、武器下拉和 GPS/雷达侧栏。

---

## 一、现状：战术地图涉及哪些前端"页面"

### 1.1 页面拓扑（比预想的简单）

战术地图**只有一个 Screen 类**，不存在多个独立页面。用户感知到的"多页面"其实是同一个 `RVP_TacticalMapScreen` 内的两种**模式** × 三种**侧栏抽屉状态**：

```
K 键（key.ywzj_rvp.open_gps_panel）
   └─ RVP_ClientEvents.onClientTick → mc.setScreen(new RVP_TacticalMapScreen(mode))
        mode = TACTICAL（常规战术地图） | ARTILLERY（炮兵精简界面）
        侧栏抽屉 SidebarMode = NONE | GPS（航点管理） | RADAR（雷达接触列表）
```

| 文件（均在 `src/main/java/org/ywzj/rvp/client/` 下） | 行数 | 角色 |
| --- | --- | --- |
| `screen/RVP_TacticalMapScreen.java` | **4321** | **全部 UI 所在**：地图本体渲染 + 外围框/按钮/菜单/侧栏 |
| `screen/RVP_GPSPanelScreen.java` | 149 | 旧版 GPS 面板，全项目**无引用，死代码**（重构时可顺手删除） |
| `map/RVP_TacticalMapCache.java` | 446 | 瓦片缓存管线（区块采样→NativeImage→DynamicTexture→磁盘持久化），**纯数据，不含 UI** |
| `map/RVP_TacticalMapChunkListener.java` | 40 | Forge 区块事件喂给 Cache，不含 UI |
| `RVP_Keys.java` | 90 | K 键 KeyMapping 注册 |
| `RVP_ClientEvents.java` | 543 | K 键消费打开地图；每 tick 驱动瓦片处理；炮兵模式空格开火透传 |

### 1.2 与地图联动、但本次重构基本不用动的客户端状态类

这些类是地图各图层的**数据源**，与 UI 框架无关：

- `state/RVP_ArtilleryFireControlState.java` —— 炮兵火控解算（⚠️ 内含 2 处 `instanceof RVP_TacticalMapScreen` 判断，见 §四.3）
- `state/RVP_ClientRadarLockState.java` —— ARM 目标循环选择（⚠️ 含 1 处 instanceof，且放行炮兵地图的输入透传判断）
- `state/RVP_ClientGPSState` / `RVP_ClientGPSUtil` —— GPS 航点装订
- `state/RVP_ClientTacticalRevealState` / `RVP_ClientMarkedBlockState` / `RVP_ClientLoiterState` / `RVP_ClientRemoteAmmoState` / `RVP_ClientExternalRadarState` / `RVP_ClientHbmMissileState` —— 各图层缓存

### 1.3 涉及的网络包（重构不需要触碰）

没有专门的"打开地图"网络包——地图是纯客户端 Screen。以下包是图层数据源或地图操作出口，全部与 UI 框架解耦：

| 包 | 方向 | 用途 |
| --- | --- | --- |
| `C2SSetLoiterCenter` | C2S | 右键菜单"设为盘旋圆心" |
| `S2CLoiterStateSync` | S2C | 盘旋圆图层 |
| `S2CRemoteAmmoSnapshot` | S2C | 远程弹药图标/GPS 虚线 |
| `S2CExternalRadarSnapshot` | S2C | 外置雷达黄色扇面 |
| `S2CTacticalRevealSnapshot` | S2C | 开火点亮/吊舱 IFF 探测门控 |
| `S2CMarkedBlockSync` | S2C | 吊舱 "TGT" 标记 |
| 本体通道 `ClientRadarAction` / `ClientVehicleSwitchWeapon` | C2S | 双击锁定雷达 / 武器下拉切弹种 |

### 1.4 资产与其他

- 语言文件：`zh_cn.json` / `en_us.json` 各约 73 条 `gui.ywzj_rvp.tactical_map.*` 词条（HTML 模板可直接复用这些 key 的翻译值）。
- 图标：`assets/rvp/textures/gui/map_icon/*.png` 共 23 个（player/jet/atkheli/mbt/msl/cruise_msl/jdam/gps/uav…），由原版 blit 绘制，**AUI 化后仍由原生渲染使用，无需转格式**。
- ⚠️ 根目录 `UI_POSITIONS_CONFIG.md` 与战术地图**无关**（是已废弃的雷达 HUD 位置配置旧案），调研时不要被误导。

---

## 二、`RVP_TacticalMapScreen` 内部结构：哪里是"本体"，哪里是"外围"

当前该类是 **100% 原版 `GuiGraphics` 手绘**（fill/blit/drawString/Tesselator 三角扇形），自带两个手搓控件：内部类 `TerminalButton`（2008–2064 行，4 次 fill 画边框）与 `TerminalTextField`（2066–2089 行）。未用任何 GUI 库。

### 2.1 地图本体显示（**保留，不重写**）

| 内容 | 位置（行号） | 说明 |
| --- | --- | --- |
| 坐标系字段与方法 | ~981–1019 | `mapLeft/mapTop/mapRight/mapBottom`、`viewWorldX/viewWorldZ`、`blocksPerPixel`、`screenToWorld*`/`worldToScreen*`、`pickMapPoint` |
| 渲染总编排 `renderMap(...)` | 1117–1144 | 只依赖上述几个状态量 + `GuiGraphics`，**不依赖 Screen 布局体系** |
| 地形瓦片 `renderTerrainTiles` | 1344 | scissor + pose 缩放 blit 瓦片 |
| 网格/雷达扇面/扫描线 | 1387 / 1590 / 4200 / 4231 | TRIANGLE_FAN 扇形、QUADS 扫描线 |
| 弹药连线、各类实体标记 | 1146–1716 | GPS/ARH 虚线、远程弹药、载具、玩家、CCIP 等 |
| 地图 HUD `renderMapHud` | 1882 | 标题/比例尺/光标坐标（可保留原生，也可迁 HTML，二选一） |
| 图标绘制原语 | 3230–3282 | `setShaderColor` 染色 + 旋转 + 32×32 blit |

### 2.2 外围 UI（**用 AUI 重写**）

| 内容 | 位置（行号） | AUI 对应物 |
| --- | --- | --- |
| 布局引擎 `refreshLayout()` / `refreshSidebarWidgets()` | 535 / 603 | 改为读 HTML 占位元素的 `getBoundingClientRect() × renderScale()` 反算地图矩形 |
| 顶部工具条：10 个 `TerminalButton` + x/y/z 3 个 `TerminalTextField` | init() 326–452 | HTML `<button>` / `<input>`，CSS 终端风主题 |
| 边框 `drawFrame()` | 1097 | HTML 外框容器（border + 半透明背景） |
| GPS 侧栏 `renderGpsSidePanel` | 2292 | HTML 分区卡片 |
| 雷达侧栏表格 `collectRadarContacts` / `renderRadarContactTable` / 滚动条 | 2367 / 2468 / 2538 | HTML 列表 + overflow-y 滚动（**可大幅删掉手写滚动条/行命中测试代码**） |
| 右键菜单 ×2（地图菜单 / 实体菜单） | 2114–2282 | HTML 浮层（绝对定位 div），点击事件走 DOM |
| 武器下拉 `renderWeaponDropdown` / `handleWeaponDropdownClick` | 1067 / 4099 | HTML `<select>` 风格下拉 |
| 选中信息条 `renderSelectedMarkerInfo` | 1941 | HTML 浮动面板 |
| 自绘控件 `TerminalButton` / `TerminalTextField` | 2008–2089 | 整体废弃 |

### 2.3 输入交互（迁到 ApricityScreen 的覆写里）

`mouseClicked/mouseReleased/mouseDragged/mouseScrolled`（771–975 行）：拖拽平移、滚轮以光标为锚缩放、右键"短按弹菜单/长按拖动"阈值、双击检测（350ms 窗口）、左双击锁定 `tryLockRadarTarget`（2648）/ 右双击速射 `tryQuickFireOnTarget`（2682）。这些**逻辑本身可复用**，只是挂载点从普通 Screen 换成 `ApricityScreen` 的覆写——先例 `RVP_AuiVariantScreen` 已成功覆写全套鼠标方法做 3D 预览拖拽。

---

## 三、AUI 在本项目中的现状与 API

### 3.1 已就绪的证据

| 项 | 证据 |
| --- | --- |
| 编译依赖 | `build.gradle:130-131`：`compileOnly 'com.sighs:ApricityUI-forge-1.20.1:1.2.3'` |
| 运行时依赖 | 本体 `ywzj_vehicle` 的改造工具屏 `VehicleModdingToolScreen` 已继承 `ApricityScreen`，运行环境必装 ApricityUI |
| 项目先例 | ① `screen/RVP_AuiVariantScreen.java`（423 行）：继承 `ApricityScreen`，HTML 做 chrome + 原生 GuiGraphics 叠加 3D 载具预览；② `handler/RVP_ModdingToolOverlay.java`：用 `Document` API 向本体 HTML 动态插按钮 |
| 模板资产 | `assets/apricityui/apricity/screens/rvp_variants.html` 已存在，新模板照此放置 |

### 3.2 核心 API（javap 自 1.2.3 jar 确认）

```java
// Screen 基类：com.sighs.apricityui.screen.ApricityScreen
new 子类构造里 super("screens/tactical_map.html"); // 模板路径相对 assets/apricityui/apricity/
setPauseGame(false);
setShowDefaultBackground(false);   // 不画原版暗背景 → 地图不被遮暗（变体屏已验证）
getLinkedDocument(): Document      // init() 里拿 DOM

// Document：getElementById / createElement / getViewport().renderScale()
//          hitTest(Position) / interceptsMouseEventsAt(Position) ← 关键，见风险§五.2

// Element：setTextContent / setClassName / setInlineStyleProperty
//          addEventListener("click", Consumer<Event>)
//          getBoundingClientRect(): DOMRect  ← HTML 占位元素 → 屏幕像素矩形
//          appendChild / removeChild / querySelectorAll ...

// Event：MouseEvent/KeyEvent；另有 CanvasRenderingContext2D（2D 画布，备用方案）
```

### 3.3 先例给出的混合渲染套路（变体屏验证过）

```java
public void render(GuiGraphics g, int mx, int my, float pt) {
    super.render(g, mx, my, pt);        // AUI 画 HTML chrome
    // 取占位元素 rect × renderScale 得屏幕像素矩形
    // enableScissor(rect) + pose 平移缩放 → 在其中跑现有 renderMap()
}
```

把"3D 载具预览"换成"现有 renderMap()"即是本方案的骨架。

---

## 四、重构方案设想

### 4.1 总原则

- **新增** `RVP_AuiTacticalMapScreen extends ApricityScreen`，把 `renderMap` 全链（约 3000 行）与交互逻辑整体搬入或抽出到协作类；
- 旧 `RVP_TacticalMapScreen` 可先保留为回退开关（配置项切换新旧界面），验证期后再删；
- `RVP_TacticalMapCache`、ChunkListener、所有 S2C/C2S 包、全部 state 类：**零改动**。

### 4.2 HTML 模板结构草案（`assets/apricityui/apricity/screens/tactical_map.html`）

```
#tm-root（全屏透明容器，pointer-events:none）
 ├─ #tm-frame        地图外框（border/半透明底），内部留空给原生渲染
 │   └─ #tm-map-placeholder   占位元素 → rect 反算 mapLeft/mapTop/... 
 ├─ #tm-toolbar      顶部工具条（居中/跟随/弹道/武器下拉/雷达/GPS…10 按钮 + xyz 输入）
 ├─ #tm-sidebar-gps  GPS 抽屉（航点列表/坐标输入/快速标记）
 ├─ #tm-sidebar-radar 雷达抽屉（接触表 + 原生滚动）
 ├─ #tm-context-menu 右键浮层（动态填充条目）
 ├─ #tm-weapon-dropdown
 ├─ #tm-selected-info 选中标记浮动信息条
 └─ #tm-hud（可选：标题/比例尺/光标坐标——也可留在原生 renderMapHud）
```

关键手法：地图矩形区域对应的 HTML 元素设 `pointer-events:none`（或用 `Document.interceptsMouseEventsAt` 判定放行），让鼠标事件穿透到 Screen 的 `mouseClicked/Dragged/Scrolled` 覆写里驱动拖拽/缩放/hit-test。

### 4.3 需要同步修改的外部耦合点（完整清单）

`mc.screen instanceof RVP_TacticalMapScreen` 共 5 处，新类要么继承旧类、要么抽公共接口统一替换：

| 文件:行 | 用途 |
| --- | --- |
| `RVP_ClientEvents.java:188` | 炮兵地图输入透传判定 |
| `RVP_ClientEvents.java:194` | 空格开火键判定 |
| `RVP_ClientEvents.java:212` | **打开地图的 setScreen 出口**（切到新类即可） |
| `RVP_ArtilleryFireControlState.java:118` | 火控解算仅作用于地图屏 |
| `RVP_ArtilleryFireControlState.java:175` | 同上 |
| `RVP_ClientRadarLockState.java:43` | ARM 循环选择放行地图屏 |

建议：引入 `RVP_TacticalMapView` 接口暴露 `isArtilleryMode()` / `allowsVehicleInputPassthrough()`，新旧 Screen 都实现，5 处 instanceof 改为接口判断，一次性消除耦合（也符合 Mixin 纪律中"组合优先"的项目风格，全程无需 Mixin）。

---

## 五、风险与技术要点

| # | 风险 | 应对 |
| --- | --- | --- |
| 1 | **每 tick 动态文案**（跟随按钮文案、武器当前弹种、炮兵仰角/解算/残弹实时刷新）改为写 DOM 后的更新开销 | AUI 有 runtime cache/dirty-element 机制；仍应只在值变化时 `setTextContent`，避免每帧刷 |
| 2 | **地图区域鼠标拦截**：HTML 若在地图上方有可命中元素会吃掉拖拽/滚轮 | 地图占位区 `pointer-events:none`；必要时用 `interceptsMouseEventsAt` 显式放行 |
| 3 | **坐标系换算**：GUI scaled pixel vs HTML viewport scale | 沿用变体屏 `getBoundingClientRect() × getViewport().renderScale()` 公式；rect 只在 init/resize 时算一次并缓存，勿每帧取 |
| 4 | **炮兵模式输入透传**（载具操控键 + 空格开火轮询在地图打开时继续生效） | 与 Screen 类型无关，靠 §四.3 的接口化解决；`RVP_ClientEvents` 透传逻辑不变 |
| 5 | **双击/长按语义**：350ms 双击窗口、"短按弹菜单/长按拖动"阈值目前写在 Screen 里 | 逻辑平移到新 Screen 的鼠标覆写；若侧栏行点击走 DOM，则在 DOM click 上再叠计时器（可复用现有窗口常量） |
| 6 | **绘制顺序遮挡**：HTML chrome 与原生地图谁先谁后决定浮层能否盖住地图 | 变体屏模式：`super.render()`（HTML 底层）之后画地图，菜单/下拉等浮层如需压在地图上，则改为先画地图再手动二次刷新局部 DOM 或将浮层做成原生绘制（按实测取舍） |
| 7 | **compileOnly 无运行时混淆映射风险** | 与变体屏同款依赖，运行时由本体携带，无新增打包动作 |
| 8 | 回归面大（4321 行单类的搬迁） | 新旧并存 + 配置开关灰度；瓦片缓存/网络包/state 不动使回归范围收敛在纯 UI 层 |

---

## 六、可行性结论与工作量预估

**结论：可行。** 三个支撑点：
1. 项目内 `RVP_AuiVariantScreen` 已跑通"AUI HTML chrome + 原生 GuiGraphics 区域渲染"混合模式；
2. `renderMap` 全链只依赖 4 个边界值 + 视图中心 + 比例尺和一个 `GuiGraphics`，与 Screen 框架天然解耦，由 HTML 占位元素反算矩形即可原样复用；
3. 数据层（Cache/网络包/state）完全解耦，重构收敛在单文件的 UI 层。

**工作量粗估**（供排期参考，非承诺）：

| 阶段 | 内容 | 预估 |
| --- | --- | --- |
| P1 骨架 | 新 Screen + HTML 框架 + 地图占位 rect 反算 + renderMap 复用 + 拖拽/滚轮/缩放 | 大头，约 40% |
| P2 控件迁移 | 工具条按钮组、xyz 输入、武器下拉、两套右键菜单、选中信息条 | 约 30% |
| P3 侧栏 | GPS 抽屉 + 雷达接触表（含滚动/行点击/双击锁定速射语义） | 约 20% |
| P4 收尾 | instanceof 接口化、炮兵模式回归、新旧切换开关、删除死代码 `RVP_GPSPanelScreen` | 约 10% |

## 七、已拍板结论（2026-08-26 批示）

| # | 问题 | 决定 |
| --- | --- | --- |
| 1 | 旧版 `RVP_TacticalMapScreen` 是否保留回退开关 | **直接替换**，不保留旧版 |
| 2 | 地图 HUD（标题/比例尺/光标坐标）去留 | **留在原生绘制**（`renderMapHud` 与地图坐标系强绑定） |
| 3 | 雷达/GPS 侧栏视觉 | **借 HTML 化机会重新设计视觉**，不照搬现布局 |
| 4 | `RVP_GPSPanelScreen` 处置 | 见下说明，**确认删除** |

### 7.1 `RVP_GPSPanelScreen` 是什么

它是战术地图诞生**之前**的旧版独立 GPS 装订面板（149 行）：屏幕中央摆 x/y/z 三个输入框 + 装订 / SINGLE·MULTI 模式切换 / 清空 / 取消 四个原版按钮。其全部功能（坐标装订、单点多点模式、清空）已被战术地图的 GPS 侧栏抽屉完整覆盖，且全项目**无任何代码引用它**（已 grep 确认零引用），属于死代码，本次重构直接删除。

### 7.2 设计稿（三版候选视觉）

> 2026-08-26 二次批示补充决定：
> - **战术地图与炮兵地图是两个独立界面，分别出稿**，不允许把两边的面板堆在同一张图里；
> - **按钮严格按后端现有功能设计**：弹道档位后端只有 `TrajectoryMode.HIGH / LOW`（高弹道/低弹道），无直射；且弹道按钮**仅在炮兵地图出现**（`refreshSidebarWidgets` 中战术模式强制隐藏）。

已生成可交互静态稿（模拟数据），位于 `docs/plan/tactical_map_aui_mockups/`。每版文件内含**战术/炮兵两个完全独立的界面**，顶部灰色预览切换条仅供看稿用、非游戏 UI：

| 文件 | 风格 | 视觉语言 |
| --- | --- | --- |
| `v1_军用终端风.html` | 军用终端风 | 绿色磷光 CRT：扫描线、方括号按钮 `[ 居中 ]`、`>` 提示符前缀、闪烁光标、等宽字体 |
| `v2_北约指挥沙盘风.html` | 指挥所纸质沙盘风 | 米白纸卡+硬投影、红色印章状态签、NATO 符号（敌=红菱形/友=蓝矩形）、红铅笔航线、实体按键牌 |
| `v3_全息作战网络风.html` | 全息作战网络风 | 近黑底霓虹青细线、四角括号面板（无整框）、扫光动画、脉冲光圈、发光大读数、LED 状态灯 |

三版共同的功能对齐基线（与代码逐一核对）：

- **战术地图**：工具条 = 武器▾ / 居中 / 跟随 / 雷达面板 / GPS面板 + 常显组 单点·多点 / 清空 / 快标；单一抽屉（GPS 与雷达互斥）；GPS 抽屉内 X/Y/Z 输入+装订+清空；雷达抽屉内 显示友方开关+接触表（# 方位 距离 高度 速度 NCTR 敌我 锁）+ 双击锁定/双击发射；两套右键菜单条目与 `zh_cn.json` 一致。
- **炮兵地图**：仅 居中 / 跟随 / 高弹道↔低弹道 三钮；火控读数（仰角、落点距离、解算 可命中/无解、残弹 n/max）；光标悬停指定落点 + 弹道弧示意；空格开火提示（输入透传）。无侧栏、无右键菜单、无武器下拉——与 `refreshSidebarWidgets` 行为一致。

选定风格后按该风格产出正式 `tactical_map.html`（战术）与炮兵模式态（同一模板的模式分支或独立模板，实施时定）。

### 7.3 最终拍板与 V3 定稿修改（2026-08-26）

**风格敲定：V3 全息作战网络风**。`v3_全息作战网络风.html` 已按以下批示优化：

| # | 批示 | 落实 |
| --- | --- | --- |
| 1 | 侧栏做大点 | 抽屉宽度 304px → **384px** |
| 2 | 上半部分会被命中反馈 UI 遮挡，雷达目标列表需要鼠标交互，放到下半部分；上下高度比 **1:2** | 抽屉改为上下分区（`flex:1 / flex:2`）：上区=只读信息卡，下区=雷达目标列表（可滚动、可点击） |
| 3 | 雷达面板还要显示锁定/选中的目标；上半部分放锁定目标等其它信息 | 上区=**锁定/选中目标卡**（名称、LOCKED/SELECTED 标签、方位/距离/高度/速度/NCTR/敌我 六格读数）+ 选中标记行 + GPS 当前点摘要行；下区顶行点击可同步上区目标卡 |
| 4 | 隐藏友方、快标按钮不放侧栏，放主栏 | 两枚 chip 移入顶部工具条主栏 |

GPS 态抽屉同样遵循 1:2 分区：上区=当前 GPS 目标摘要（坐标/模式/快标状态），下区=航点列表。

### 7.4 实施记录（2026-08-26 完成，`./gradlew build` 通过）

**改动清单：**

| 文件 | 改动 |
| --- | --- |
| `screen/RVP_TacticalMapScreen.java` | **类名保持不变，基类改为 `ApricityScreen`**——5 处 `instanceof` 判断零修改；删除 TerminalButton/TerminalTextField/原生框·侧栏·右键菜单·武器下拉（约 1100 行），新增 AUI DOM 同步层（工具条胶囊、抽屉目标卡/雷达列表/GPS 航点、统一浮层菜单）；地图本体渲染管线与双击锁定/速射、右键拖拽阈值等交互逻辑原样保留 |
| `assets/apricityui/apricity/screens/rvp_tactical_map.html` | 新增模板（V3 全息风），战术/炮兵同模板经 body class 切换显隐 |
| `screen/RVP_GPSPanelScreen.java` | 已删除（死代码） |
| lang `zh_cn/en_us` | 新增 `tactical_map.tag.locked / tag.selected` 两个键 |

**关键技术决策（避坑记录）：**

1. **缩放问题根治**：模板 meta 用 `<meta name="aui-viewport" content="mode=gui">`。查 AUI 源码（github.com/Tower-of-Sighs/AUI）`ApricityViewport.gui()` 确认该模式下 **1 文档像素 = 1 MC GUI 像素**（renderScale = guiScale/guiScale 截断逻辑），因此 CSS px 与原版 HUD 尺寸在任意 GUI 缩放下完全一致；地图矩形由 `#tm-hole` 占位元素 `getBoundingClientRect()×renderScale` 反算，无任何漂移。若误用变体屏的 `mode=browser`，元素会随显示器内容缩放系数变化——正是用户担心的"游戏里异常大"，已规避。
2. **零 Mixin**：AUI 的 DOM 鼠标事件由其自身 Forge 事件（`Client.java` 订阅 `ScreenEvent`/`InputEvent`）分发，不经 Screen 方法覆写，本模组侧无需任何 Mixin。
3. **DOM 不吞点击的判定**：未启用 intercept meta 时，AUI 命中元素不会取消原版鼠标事件 → Java 侧每帧缓存工具条/抽屉/浮层的屏幕矩形，`mouseClicked/Dragged/Scrolled` 先做 `overInteractiveDom()` 守卫，命中即交还 DOM，否则走地图交互。
4. **浮层统一**：地图右键菜单/实体右键菜单/武器下拉共用一个 `#tm-pop` 容器（Java 动态填充条目+定位），条目回调在打开时捕获数据副本，关闭浮层不影响执行。
5. **雷达行交互**：行监听 `mouseup` 取 `MouseEvent.button`，配合保留的 350ms 双击窗口状态字段实现 单击选中/双左击锁定/双右击速射，语义与旧表格逐一对齐。
6. **DOM 写入节流**：所有文案/class 更新带值比对（setText/setOn/showIf），列表按签名重建，避免每帧触发样式重算。

**待实机验证项**：GUI scale 1/2/auto 下视觉一致性、抽屉滚动穿透手感、炮兵模式输入透传回归、高 DPI 窗口化场景。

### 7.5 缩放事故修复（2026-08-26 实机反馈）

**现象**：游戏内 chrome 巨大化——工具条溢出整屏、抽屉占比 80%，地图区域可看穿到游戏世界。

**根因**：`mode=gui` 的文档宽度 = 屏幕物理宽 ÷ GUI 倍数（scale 4 时仅 480px），而模板按 1920px 设计稿写绝对 px → 比例放大 4 倍；且重构时误删了旧 `drawFrame` 的地图暗色底填充，导致瓦片未覆盖区透出世界。

**修复**：
1. 模板全部尺寸改 **em 相对单位**（1em=13 设计像素），抽屉上下分区改 flex `1:2`（去掉 calc 依赖）；
2. Java 每帧 `syncViewportScale()`：`body.font-size = 13 × (viewport.layoutWidth()/1920) px`，值变化才写入；init 重建文档后强制刷新；
3. `render()` 恢复地图暗色底 `fill(mapRect, 0xB010151C)`；
4. 浮层定位/钳制、武器下拉锚点同步乘 k。

效果：任意 GUI 缩放下界面比例恒等于 1920 设计稿，且与原生 HUD（同为 GUI 像素系）视觉一致。

### 7.6 缩放最终修复（2026-08-26 第二次实机反馈）

em+根字号方案实机仍巨大。**对照本体 `vehicle_modding_tool.html` 与 `rvp_variants.html`（两者实机正常）**：它们统一使用 `<meta name="aui-viewport" content="mode=browser">` + 绝对 px 字号（10-18px）+ 百分比栏宽。`mode=gui` 的文档宽=屏幕宽/GUI 倍数（scale4 时仅 480px），是巨大化的真正根源；且 AUI CSS 引擎对 em 相对单位的实际表现不可靠。

**最终方案**：模板改回与现有 AUI 界面完全一致的 `mode=browser` + px 设计（13px 基准字号，与本体 11-16px 同级）；删除 Java 根字号 hack；地图矩形换算公式 `rect×renderScale` 对任意视口模式均成立，无需改动。地图暗色底填充保留。

### 7.7 第三轮实机修复（2026-08-26 晚）

用户反馈"雷达目标列表什么都没有"及网页稿与游戏实现不一致。三处缺陷与修复：

1. **雷达列表空**（功能性失误）：`collectRadarContacts()` 原调用点在已删除的原生侧栏绘制里，迁移时丢失，列表数据源从未被喂。→ 改为 tick 驱动：雷达抽屉打开期间每 tick 收集 + 切入抽屉时立即收集一次。
2. **目标卡六格只显示两格**：AUI 引擎对 `display:grid` 支持不可靠（3 列网格仅第 1 列可见）。→ 改 flex-wrap 三列（每格 33.33%），与已验证可用的 flex 行（表头/工具条）同一机制。
3. **地图区"看穿"世界、观感与网页稿不符**：底色透明度过低（0xB0）且无边框。→ 恢复旧版不透明度 0xCC + 1px 边框；航点行补方位角列。

经验教训：**迁移原生 UI 时，"绘制即数据收集"的隐式耦合必须显式接管**；AUI CSS 特性以"已在其生态内验证过的子集"为准（flex/overflow/百分比可用，grid 慎用）。
