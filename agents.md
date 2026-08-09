docs目录下为项目文档，入口见 docs/README.md；docs/plan 下放计划文档
命名时武器名称要使用AntiRadiation、TVMissile这种正规写法，并且如GPS等缩写不要写成Gps

**禁止硬编码武器 ID**：不得在实体/渲染类里用 `weaponId.getPath().equals("某武器名")` 区分弹种；用 `fire_data.canister_count` 等 JSON 字段。弹体须用 RVP 类型化 `EntityRenderer`（不可直接注册本体 `BulletEntityRenderer`），绘制逻辑见 `VehicleProjectileRenderLogic`。

**禁止旧版 JSON 迁移**：`RVP_WeaponTypes` 等加载器只接受当前 schema；勿写 `legacy*`、`migrate*` 或旧键别名。改历史 JSON 用 `scripts/`，不要塞进 Java。

**Bedrock 模型**：只在载具包 `assets/rvp/models/bedrock/` 与 display JSON 的 `model` 配置；禁止在 Java 里维护模型 ID 白名单（已删除 `RVP_BedrockModels`）。

**`RVP_*Data` JavaDoc**：每个 `@SerializedName` 字段须有与 `RVP_FireData` 同级的说明（单位、默认、生效条件）；规范见仓库 `.cursor/skills/mcheli-rvp-port/data-class-javadoc.md`。

**Mixin 使用纪律（非必要禁止，默认拒绝）**：
- 默认禁止新增任何 Mixin 类或注入点；论证责任在提出方，未给出充分理由前一律拒绝。
- 新增 Mixin 前必须逐级排除替代方案（有任一可行即不得用 Mixin）：
  1. JSON 数据字段（`RVP_*Data` 数据模型可表达的行为，优先在数据层配置）
  2. 继承/接口/组合（`ywzj_vehicle` 的可覆写方法、接口实现、`DistExecutor` 分端）
  3. Forge 事件总线（`MinecraftForge`/`ModBus`，含渲染/刻/Tick/网络注册事件）
  4. 网络包 + 独立管理类（S2C/C2S 包 + `RVP_*` 独立系统）
  5. `@Accessor`/`@Invoker` 访问器（仅需读私有字段/调 protected 方法时，放 `org.ywzj.rvp.accessor`，不新建 mixin 类）
- 只有确需修改本体/原版字节码且上述全不可行时才允许 Mixin，且：
  - 改动面必须最小化：只允许最小 `@Inject`（HEAD/RETURN 转发），**禁止 `@Overwrite` 整方法、禁止整段 `@Redirect`**；确需改写核心逻辑时先向用户提出非 Mixin 替代。
  - Mixin 只做转发/最小拦截，业务逻辑一律放非 `mixin` 包的辅助类，禁止把共享状态放进 Mixin 类。
  - 新增前先检查现有 Mixin 是否已覆盖/可合并，避免重复注入或叠加改动同一方法。
- 新 Mixin（含新注入点、`@Accessor` 扩展）必须先向用户说明：目标类/方法、注入方式、为何无可替代，**经用户确认后才可编写**。
- 公共数组（`mixins`）的 Mixin 会让目标类在服务端变 dirty → Forge 重算字节码帧 → 目标类方法中若有 `@OnlyIn(CLIENT)` 类型引用（`LocalVehiclePlayer`、`Minecraft`、客户端粒子等）会触发服务端加载崩溃。
- Mixin 及任何共享/公共代码中**禁止直接引用 `@OnlyIn(CLIENT)` 类型**；必须经 `org.ywzj.rvp.client.bridge.RVP_ClientActionsAccess` 桥接（服务端 NOOP、客户端真实现），新增桥接方法见 `RVP_IClientActions`。
- 公共数组 Mixin 的目标类必须是双端安全的；纯客户端行为放 `client` 数组。