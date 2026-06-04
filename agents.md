docs目录下为项目文档，入口见 docs/README.md；docs/plan 下放计划文档
命名时武器名称要使用AntiRadiation、TVMissile这种正规写法，并且如GPS等缩写不要写成Gps

**禁止硬编码武器 ID**：不得在实体/渲染类里用 `weaponId.getPath().equals("某武器名")` 区分弹种；用 `fire_data.canister_count` 等 JSON 字段。弹体须用 RVP 类型化 `EntityRenderer`（不可直接注册本体 `BulletEntityRenderer`），绘制逻辑见 `VehicleProjectileRenderLogic`。

**禁止旧版 JSON 迁移**：`RVP_WeaponTypes` 等加载器只接受当前 schema；勿写 `legacy*`、`migrate*` 或旧键别名。改历史 JSON 用 `scripts/`，不要塞进 Java。

**Bedrock 模型**：只在载具包 `assets/rvp/models/bedrock/` 与 display JSON 的 `model` 配置；禁止在 Java 里维护模型 ID 白名单（已删除 `RVP_BedrockModels`）。