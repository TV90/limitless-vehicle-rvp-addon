- 本项目为`ywzj_vehicle`的Submod`limitless-vehicle-rvp-addon`,均在Gradle,Minecraft 1.20.1-Forge下开发
- docs目录下为项目文档，入口见 docs/README.md；docs/plan 下放计划文档
- 命名时武器名称要使用AntiRadiation、TVMissile这种正规写法，并且如GPS等缩写不要写成Gps
- 代码中必须包含清晰的中文注释，每个使用的字段也必须注释，在方法中调用其他本项目(`limitless-vehicle-rvp-addon`)以及本体(`ywzj_vehicle`)的方法时必须注释调用目的
- 验证编译时在项目根目录直接运行 `./gradlew build` 即可

**`ywzj_vehicle` 本体源码** 在 `"填入你的本体源码绝对路径"` 下

**不要改** `ywzj_vehicle` 本体源码；新逻辑写在 `limitless-vehicle-rvp-addon`。

**禁止硬编码武器 ID**：不得在实体/渲染类里用 `weaponId.getPath().equals("某武器名")` 区分弹种；用 `fire_data.canister_count` 等 JSON 字段。弹体须用 RVP 类型化 `EntityRenderer`（不可直接注册本体 `BulletEntityRenderer`），绘制逻辑见 `VehicleProjectileRenderLogic`。

**禁止旧版 JSON 迁移**：`RVP_WeaponTypes` 等加载器只接受当前 schema；勿写 `legacy*`、`migrate*` 或旧键别名。改历史 JSON 用 `scripts/`，不要塞进 Java。

**Bedrock 模型**：只在载具包 `assets/rvp/models/bedrock/` 与 display JSON 的 `model` 配置；禁止在 Java 里维护模型 ID 白名单（已删除 `RVP_BedrockModels`）。

**`RVP_*Data` JavaDoc**：每个 `@SerializedName` 字段须有与 `RVP_WeaponData` 同级的说明（单位、默认、生效条件）
