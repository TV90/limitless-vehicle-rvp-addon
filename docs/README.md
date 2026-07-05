# RVP 拓展包开发文档

面向在 `limitless-vehicle-rvp-addon` 与 `limitless_vehicle/rvp` 载具包上配置武器、载具的开发者。

## 文档索引

| 文档 | 适合谁 | 内容 |
| --- | --- | --- |
| [RVP包新增参数字段说明.md](./RVP包新增参数字段说明.md) | 配置作者 | 武器 JSON 全字段说明（权威 schema） |
| [弹体运动学开发与测试.md](./弹体运动学开发与测试.md) | 弹道 / 性能调试 | `projectile_data` 运行时流程、与本体对照、常见问题 |
| [无人机_TV弹_区块加载功能调研.md](./无人机_TV弹_区块加载功能调研.md) | 系统调研 / 接手开发 | 可部署 UAV、TV/HITL 导弹、区块加载器当前实现与风险 |
| [RVP伤害倍率与爆炸.md](./RVP伤害倍率与爆炸.md) | 平衡 / 移植 | `damage_factor`、直击与本体 `VehicleExplosion` |
| [子母弹系统与Mi28边界测试.md](./子母弹系统与Mi28边界测试.md) | 子母弹 / QA | 架构、`allow_submunition`、C01–C10 创意演示弹与安装步骤 |
| [examples/mi28_s13_boundary/](./examples/mi28_s13_boundary/) | QA / 配置 | Mi-28 演示武器 JSON 副本（可复制到载具包） |
| [plan/](./plan/) | 功能设计 | TV 导弹、[分段复合制导](./plan/导弹分段复合制导实现.md) 等方案稿 |

## 路径约定

| 用途 | 路径 |
| --- | --- |
| Gradle 开发载具包（唯一维护位置） | `limitless-vehicle-rvp-addon/run/client_1/limitless_vehicle/rvp/` |
| 玩家 `.minecraft` 安装目录 | `.minecraft/limitless_vehicle/rvp/` |
| 武器数据 | `data/rvp/weapons/<id>.json` → 资源 ID `rvp:<id>` |
| 武器显示 | `assets/rvp/display/weapon/<id>.json` |
| 载具数据 | `data/rvp/vehicles/<id>.json` |

直接编辑 `run/client_1/limitless_vehicle/rvp/`；发布或覆盖安装前请递增 `vehicle_pack.meta.json` 的 `version`。

## 硬约束（与根目录 agents.md 一致）

- **不要改** `ywzj_vehicle` 本体源码；新逻辑写在 `limitless-vehicle-rvp-addon`。
- 新武器 `type` 只用 7 个公开类型：`rvp:missile` / `rocket` / `machinegun` / `bomb` / `laser` / `dispenser` / `targetingpod`。
- TV、ARH 制导仅挂在 `rvp:missile` 的 `guidance_data` 中。
- 改 JSON 字段时同步更新 [RVP包新增参数字段说明.md](./RVP包新增参数字段说明.md)。
- **禁止在 Java 中按武器资源 ID / 路径名分支**（例如 `if (weaponId.equals("mi28_2a42_canister"))`）。行为差异用武器 JSON（`fire_data`、`collision_data` 等）表达。
- **弹体渲染**：RVP 实体须注册 RVP 专用 `EntityRenderer`（`RVP_BulletEntityRenderer`、`RVP_BedrockProjectileEntityRenderer`），绘制规则与本体一致；模型来自 `assets/rvp/display/weapon/<id>.json`，缺省回退本体 `missile_akd10` / `rocket_57mm` / `aerial_bomb` / `basic_bullet`。

## 相关代码入口

| 主题 | 类 |
| --- | --- |
| 武器加载与归一化 | `org.ywzj.rvp.all.RVP_WeaponTypes` |
| 弹体数据 | `org.ywzj.rvp.weapon.data.RVP_ProjectileData` |
| 弹体实体运动 | `org.ywzj.rvp.entity.projectile.RVP_BaseBullet` |
| 子母弹调度 / 生成 | `org.ywzj.rvp.weapon.submunition.RVP_SubmunitionRunner`、`RVP_SubmunitionSpawner` |
| 载具命中调试 HUD（客户端） | `S2CBulletVehicleHitDebug`、`RVP_BulletHitDebugOverlay`（服务端 `RVP_BulletHitDebugNetworking`） |
| 伤害倍率 / 爆炸 | `RVP_Explosion`（配置）、`RVP_DamageFactor`、`VehicleExplosion`（本体运行时） |
| 机炮弹丸 | `org.ywzj.rvp.entity.projectile.RVP_BulletEntity` |
| 弹体客户端渲染 | `RVP_ClientEntityRenderers`、`VehicleProjectileRenderLogic`（对齐本体绘制） |
| 本体对照（只读） | `BulletEntityRenderer`、`MissileEntityRenderer`、`AerialBombEntityRenderer` |
