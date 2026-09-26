# RVP 辅助设备面板 · 示例面板（2026-09-25 起，v4 定版方向）

**定版布局（用户 2026-09-25 三轮批注定稿，当前= v4）**：`O` 键打开、AUI（ApricityUI）编写。
- **左上**：俯视图（ERA/APS 扇区等骨块状态可视化，尺寸收敛不裁切）；
- **左下**：快速维修**顺序设置**——**"爆反维修顺序 / 辅助设备维修顺序"两条队列，下拉框切换，队列列表带滚动条**；**无"开始维修"按钮**——维修由玩家手持快修工具自行触发，面板只负责设置顺序；
- **右半页**：**同屏恰好四个等高栏目**（2×2，各占右列高度 47%；内容多靠卡内滚轮，栏目总数超出靠外层滚轮），方便未来扩展"引擎/履带"等部件损毁栏目；
- **行格式**：`序号`（载具数据出现顺序，从 1 起）+ `别名`（hitbox_display_name；**只显示别名，无别名才显示骨骼名**）+ `状态`；生效行绿边框、失效行红边框；
- **行排序**：**失效行置顶**（失效按序号升序在前，其余按序号升序在后）。

### 版本变更记录
- **v4**（当前）：右半页改"同屏四栏目 2×2 等高"（v2 的单列全长/v3 的固定 335px 卡均废弃）；维修顺序拆**爆反/辅助设备两条队列 + 下拉框切换**；**取消开始维修按钮**（触发权交还快修工具）。
- v3：失效置顶、仅显示别名、左下面板固定高度防裁切、快修可用性由快修模块门控。
- v2：组合布局首版（单列栏目）。v1（example1~4）：已否决。
- 旧版文件全部留档（example1~8），当前版只看 example9/10。

## 文件清单与预览

| 文件 | 说明 |
|---|---|
| `example9_t90m_v4布局.html` | **v4 当前版（t90m）**：同屏 2×2 四栏目（ERA 16 行滚动+失效置顶/ECM/烟幕/部件演示）＋俯视图＋爆反/辅助设备双维修队列 |
| `example10_m1a2sep_v4布局.html` | **v4 当前版（m1a2sep）**：APS 被毁扇区入"辅助设备维修顺序"队列演示；俯视图含 APS 扇区锥 |
| `example5~8_*_v2/v3布局.html` | 第二/三轮设计稿，已被 v4 取代，留档 |
| `example1~4_*.html` | 第一轮设计稿，**已否决，留档备查** |
| `ore.css` / `screen-common.css` | AUI 1.2.4 jar 原版主题 + 本体共享样式（预览观感≈游戏内） |

**预览**：双击 html 浏览器打开（按 `mode=browser` 的 1920 逻辑宽排版）。example5/6 内置演示脚本：**点击右侧红框（失效）行加入维修队列、✕ 移除**——正式版由 Java `addEventListener` 实现（AUI 内不执行 `<script>`）。
**演示数值均为硬编码**（正式版 Java 注入）；t90m/m1a2sep **均未配置 maintenance**，维修栏为演示态，正式版"无维修配置不显示本栏"。

## 一、数据源对照表（零新增网络包的部分）

| 面板元素 | 数据来源（客户端可直读） |
|---|---|
| 栏目清单/每栏目行（装了什么、在哪根骨） | `RVP_VehicleHitboxFactorManager.INSTANCE.resolveApsDevices / resolveEcmActiveDevices / resolveEcmDevices / resolveJammerDevices / resolveDircmDevices / resolveMaintenanceModule(vehicle)`（`S2CVehicleRvpConfig` 已下发全量 JSON） |
| 行的序号 | 载具 JSON `bone_modules` 各条目的**出现顺序**（实现需保序：解析入 LinkedHashMap，或直接用 `parts[]` 数组序——两车所有骨模块均有同名 part） |
| 骨骼别名 | 载具 JSON `hitbox_display_name`，缺省回退设备类型默认名/骨名 |
| 生效/失效 | `RVP_ClientBoneModuleState.isModuleActive / getInactiveEraBones`（`S2CBoneModuleState` 全量快照） |
| APS 扇区角/弹药 | `bone_modules.<骨>.aps` 的 `facing_yaw/scan_fov`（静态）+ `RVP_ApsHudState`（弹药/装填） |
| 整车/部件血量 | `AbstractVehicle.getHealth()`、`vehicle.getPartUnits()` 各 `PartUnit.getHealth()`（自动同步） |
| 俯视图骨块位置 | `resolveBoneObbs` 投影（`RVP_ScopeOverlay` 骨骼俯视图同源） |
| 维修栏状态 | `RVP_ClientMaintenanceState`（`S2CMaintenanceSync`：冷却/生效剩余） |

AUI 尺寸铁律、双滚动实现（右半页 `.cat-scroll{overflow-y:auto}` + 栏目 `.cat-body{overflow-y:auto}` + `grow` 栏目吃满剩余高度）、俯视图两条实现路线（HTML div 注入 vs scissor 原生；APS 扇区锥的 `transform:rotate` 需游戏内实测）——同前版 README 结论，实现时照办，此处不赘述。

## 二、快速维修功能研究结论与"维修顺序"改动设计

### 现状（源码：`RVP_MaintenanceRuntimeManager` / `BoneMaintenanceConfig` / `C2SUseMaintenance`）

- 配置：载具 JSON `bone_modules.__vehicle__.maintenance`（虚拟骨永不可毁；也可绑实体骨，被击毁即维修失效）。字段：`use_time_ticks`(默认20)、`wait_time_ticks`(默认300)、`heal_per_tick_percent`(1.0)、`heal_parts`、`require_max_altitude`、`module_repair{repairable_types(缺省=除TRACK全部), era_recover_fraction(0.25), era_recover_min(1), device_recover_chance(0.25)}`。
- 触发链：G → `C2SUseMaintenance` → 服务端 `tryStart` 校验（已配置/维修模块存活/冷却就绪/玩家在本车上/离地高度）→ 生效期逐 tick 回血（可选回部件血量）→ `recoverModules` 恢复模块 → `syncBoneModuleState` 一次广播（客户端动画/各消费端自动重生效）。
- **恢复目标当前是纯随机**：设备类逐台 25% 掷骰（HashMap 遍历序）；ERA 把已毁块 Fisher-Yates **洗牌**取 `ceil(n×0.25)`、至少 `era_recover_min` 块。玩家无法指定先修哪块——这就是要改的点。
- 冷却/生效期持久化在载具实体 NBT；HUD 每 10t 推 `S2CMaintenanceSync`。

### "维修顺序"功能改动方案（v4 定稿：面板只设顺序，触发权在快修工具）

1. **两条队列**：爆反（ERA）维修顺序、辅助设备（APS/ECM/干扰机/DIRCM 等骨模块设备）维修顺序，分开设置——对应服务端恢复逻辑的两个分支（ERA 配额 / 设备掷骰）；
2. **客户端**：面板点击失效行加入对应队列（行按模块类型自动路由），✕ 移除；按载具记忆；
3. **网络**：新增 C2S"设置维修顺序"包，**面板编辑即同步**（不绑定触发动作），服务端按载具 UUID 存侧表（车组共享：任何乘员快修都按此顺序）；
4. **触发不变**：玩家照旧手持快修工具（既有 G/`C2SUseMaintenance` 链路）触发维修，**面板无开始按钮**；
5. **服务端 `recoverModules` 改队列优先**：ERA 恢复配额公式不变（ceil(n×fraction)/min），从爆反队列头依次取、队列外仍洗牌补足；设备类掷骰概率不变，辅助设备队列内目标优先掷骰。校验：骨确实失效、类型在 `repairable_types` 白名单（防改包）；
   - **平衡性定版（默认案）**：只把"修哪些"的决定权交给玩家，恢复量/概率公式一律不动；
6. **前提**：t90m / m1a2sep 均未配置 maintenance——实车体验需补配置（载具包改动，数值你定）：
   ```json
   "__vehicle__": {
     "modules": ["maintenance"],
     "maintenance": { "use_time_ticks": 100, "wait_time_ticks": 1200, "heal_parts": true }
   }
   ```
   未配置的车面板不显示维修顺序栏（可用性由快修模块决定）。

## 三、待定版问题

1. 队列内目标是"占配额/掷骰优先"（默认案，概率/配额不动）还是"确定恢复"（会变相增强维修）？
2. `maintenance` 配置数值（use_time/wait_time/heal_parts）——两车主包各配多少？
3. 俯视图实现路线：HTML div 注入（推荐先试）还是 scissor 原生渲染？AUI 引擎对 `transform:rotate`（APS 扇区锥）与 `<select>`（队列下拉框）的支持需游戏内实测；不支持则分别退化为预计算扇区三角 / 自绘 DOM 下拉。
4. 维修顺序队列的车组共享语义：任何乘员可改（默认案，按载具存储）还是仅车长可改？
