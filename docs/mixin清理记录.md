# RVP Mixin 清理记录

> 配套 `docs/mixin审计与清理计划_20261025.md`。每条清理一行：日期 | 名称 | 替代方式 | 验收结果。
> 删除一律走逻辑删除：文件移入 `_recycle_bin/<时间戳>_mixin_*/`，并在 `_recycle_bin/MOVE_LOG.md` 记一行。

| 日期 | Mixin | 替代方式 | 验收结果 |
|---|---|---|---|
| 2026-10-28 | `MinecraftReloadResourcePacksMixin`（A1） | 不再挂钩 `Minecraft.reloadResourcePacks()`。改为在 `RVP_StateBoneHider` / `RVP_DistanceBoneHider` / `RVP_LodModelManager` 的 `ensureBound()` 里比对 `ClientAssetsManager.INSTANCE.getVehicleDisplays()` 的**实例引用**（本体 `DisplayManager.apply` 每次都 build 出新 ImmutableMap），变了就在渲染热路径重建规则表 | 编译 + 全量 build 通过；待实机验 F3+T |
| 2026-10-28 | `ClientAssetsManagerCustomMountMixin`（A2） | 职责拆分：不依赖 display 的部分（`clearModelCache` / `RVP_DisplayBackendUtil.clearCache` / `UIPresetManager.load(rm)`）并入已注册的公共监听器 `RVP_CustomMountReloadListener.apply`；依赖 display 的部分交给上面的实例比对 | 编译 + 全量 build 通过；待实机验 |

## 关键设计说明（为什么不用 RegisterClientReloadListenersEvent 做重建）

计划里 A1/A2 原本写的替代入口是 `RegisterClientReloadListenersEvent`，实施时发现**时机不对**：

- 该事件注册的 listener 与本体自己的 display listener 属于**同一个重载批次**，`apply` 执行先后无保证；
- 而这三个规则表是**同步构建**（`rebindAll` 内直接遍历 `getVehicleDisplays()` 建表，末尾置 `bound=true`），
  若在 display 还没刷新完时构建，就会把半新半旧的数据固化下来，之后不会自动纠正。

所以改成「实例引用比对」的惰性重建：**不清空、不提前构建**，只在渲染首次访问时发现「display 表换了新实例」才重建。
好处是彻底摆脱时机问题——无论重载来自启动、F3+T、服务器资源包还是 `/ywzj_vehicle reload`，下次渲染访问一定用的是最新数据。

## 本次改动文件

| 文件 | 改动 |
|---|---|
| `client/render/RVP_StateBoneHider.java` | 新增字段 `boundVehicleDisplays`；`rebindAll` 末尾记录；`ensureBound` 比对 |
| `client/render/RVP_DistanceBoneHider.java` | 同上 |
| `client/render/RVP_LodModelManager.java` | 同上（`rebindAll` 在 `registerLodTextures()` 后记录） |
| `client/resource/RVP_CustomMountReloadListener.java` | `apply` 增加 `RVP_DisplayBackendUtil.clearCache()` 与 `UIPresetManager.load(resourceManager)` |
| `src/main/resources/ywzj_rvp.mixins.json` | 摘除 2 条，`client` 47 → 45 |
| 删除 | `mixin/MinecraftReloadResourcePacksMixin.java`、`mixin/ClientAssetsManagerCustomMountMixin.java` |

备份：`_backups/20261028_191159_mixin_d1_reload/`（7 个原始文件）。

## 补充：A2 的 UI 预设重载有一处遗漏（2026-10-28 当天补上）

A2 原本在 `ClientAssetsManager.reload` TAIL 里做五件事：清挂架缓存、`rebindAll` ×4、`UIPresetManager.load(rm)`。
迁移时前四项都有替代（`RVP_CustomMountReloadListener` + display 表实例比对的惰性重建），**唯独 UI 预设漏了**：

- 客户端资源重载（启动 / F3+T / 服务器资源包）走 `RVP_CustomMountReloadListener.apply` ⇒ 有覆盖；
- 但本体 `/ywzj_vehicle reload` 命令**只调 `ClientAssetsManager.reload`**，不触发资源重载 ⇒ 没人重读 `ui_presets`。

补法：`RVP_ClientEvents.onClientTickDisplayReload`（ClientTick END，独立于原 `onClientTick` 的 player 判空）——
每 tick 比对 `ClientAssetsManager.getVehicleDisplays()` 实例，变化即 `UIPresetManager.load(mc.getResourceManager())`。
与三个规则表共用「display 表换了新实例」这一信号，仍然零 mixin。

验收：改 `data/rvp/ui_presets/<名字>.json` 后执行 `/ywzj_vehicle reload`，HUD 应立刻用新预设（此前需 F3+T）。
