# RVP 拓展包开发文档

面向在 `limitless-vehicle-rvp-addon` 与 `limitless_vehicle/rvp` 载具包上配置武器、载具的开发者。

## 文档索引

| 文档 | 适合谁 | 内容 |
| --- | --- | --- |
| [RVP包新增参数字段说明.md](./plan/RVP武器数据模型/RVP包新增参数字段说明.md) | 配置作者 | 武器 JSON 全字段说明（权威 schema） |
| [弹体运动学开发与测试.md](./弹体运动学开发与测试.md) | 弹道 / 性能调试 | `projectile_data` 运行时流程、与本体对照、常见问题 |
| [无人机_TV弹_区块加载功能调研.md](./无人机_TV弹_区块加载功能调研.md) | 系统调研 / 接手开发 | 可部署 UAV、TV/HITL 导弹、区块加载器当前实现与风险 |
| [RVP伤害倍率与爆炸.md](./RVP伤害倍率与爆炸.md) | 平衡 / 移植 | `damage_factor`、直击与本体 `VehicleExplosion` |
| [子母弹系统与Mi28边界测试.md](./子母弹系统与Mi28边界测试.md) | 子母弹 / QA | 架构、release 级三维释放云、载荷速度/定向发射、`allow_submunition` 与边界测试 |
| [RVP Distant Horizons 地形 LOD 遮挡兼容技术文档与调参指南](./RVP载具渲染/RVP_DistantHorizons地形LOD遮挡兼容技术文档与调参指南_20260829.md) | 客户端渲染 / 整合包 / QA | DH API 7.0.1+ 的远距代理与 512 格内真实载具双层深度合成、显式回退、bias 校准、诊断与实机验证矩阵 |
| [RVP空爆白磷弹实际实现与调参指南.md](./RVP弹体-fish/RVP空爆白磷弹实际实现与调参指南.md) | 配置作者 / 特效 / 平衡 / QA | M30 权威白磷释放云、分层下坠、初段尾迹增密、毁伤、性能预算与调参排障 |
| [炮兵地图与战术点亮机制方案.md](./plan/炮兵地图与战术点亮机制方案.md) | 火控开发 / QA | 炮兵地图、逆 CCIP 解算、偏航优先瞄准、俯仰门控与战术点亮机制 |
| [RVP 炮火支援技术文档与调参指南](./RVP_item/RVP炮火支援终端/RVP炮火支援技术文档与调参指南_20260906.md) | 玩家 / 配置作者 / 服务端管理员 / QA | schema v3 单武器/混合弹药方案、数据驱动终端变体、`fire_support_profiles` 全字段、调参、性能预算与排障 |
| [examples/mi28_s13_boundary/](./examples/mi28_s13_boundary/) | QA / 配置 | Mi-28 演示武器 JSON 副本（可复制到载具包） |
| [plan/](./plan/) | 功能设计 | TV 导弹、[分段复合制导](./plan/导弹分段复合制导实现.md)、[自定义挂架衔接点/出弹点分离](./plan/研发调研_自定义挂架衔接点与出弹点分离.md) 等方案稿 |
| [examples/custom_mount_shoot_bone/](./examples/custom_mount_shoot_bone/) | 配置作者 / QA | 自定义挂架出弹骨（`shoot_structure_bones`）j15 新旧写法对比与条目级示例（伪配置） |
| [plan/RVP_armor_min_max_damage移植方案_20260901.md](./plan/RVP_armor_min_max_damage移植方案_20260901.md) | 伤害/平衡开发 | 载具级装甲参数（固定扣减 + 最终封顶），已落码 |
| [plan/RVP模型目录整理方案_20260901.md](./plan/RVP模型目录整理方案_20260901.md) | 载具包资产维护 | 模型/贴图目录 ammo/weapon_mount 子目录化与引用改写（待确认执行） |
| [plan/RVP干扰物重构数据模型/](./plan/RVP干扰物重构数据模型/) | 功能设计 / 配置作者 / 开发 | 干扰物拆分为热焰弹（IR/AIR）与箔条（SARH/ARH）：载具状态机制、载具侧推荐参数、双端解耦规划（服务端优先里程碑） |
| [调试与修复规范.md](./调试与修复规范.md) | 调试 / 接手开发 | RVP 侧铁律、Mixin 纪律、功能丢失记录表、经验教训 |
| [plan/RVP按键占用与提示刷屏审计修复方案_20260906.md](./plan/RVP按键占用与提示刷屏审计修复方案_20260906.md) | 客户端 / 网络 / 接手开发 | N/M/F/5 键守卫缺失与服务端"不适用必回提示"全量审计（16 键总表）、A1/A2/A3/D 修复方案与验收标准、B 类 overlay 锚定备忘、载具包 git 事故恢复记录 |
| [修复方向/进度交接_20260806.md](./修复方向/进度交接_20260806.md) | 调试 / 接手开发 | **纯 Forge 服务端崩溃修复的最新交接**（阶段 1 已完成，功能恢复计划） |
| [plan/RVP载具座位无座舱视角方案_20260910.md](./plan/RVP载具座位无座舱视角方案_20260910.md) | 客户端 / 配置作者 / 接手开发 | 座位级 `rvp_no_cockpit_view` 数据字段（§3.4）：标记座位视角循环剔除 OPERATOR（通用机制，j16 双座改装后启用） |
| [plan/RVP有人载具潜行右键改装屏蔽方案_20260913.md](./plan/RVP有人载具潜行右键改装屏蔽方案_20260913.md) | 玩法 / 交互 / 接手开发 | 有人（玩家/gunner 乘员）载具屏蔽"改装工具+潜行+主手"右键子武器站改装：`RVP_ModdingInteractGuard` 零 Mixin 事件拦截 + `hasPilotOrGunnerPassenger` 语义抽取，含本体三条改装链路的距离/乘员校验调查结论与实机测试清单 |
| [修复方向/修复方案总结.md](./修复方向/修复方案总结.md) | 调试 / 接手开发 | 崩溃机制证据、8 类 16 方法对照表、方案对比 |

## 路径约定

| 用途 | 路径 |
| --- | --- |
| 开发实机运行包（**客户端**） | `run/client_1/limitless_vehicle/rvp/` |
| 开发实机运行包（**服务端**） | `run/server/limitless_vehicle/rvp/` |
| 玩家 `.minecraft` 安装目录 | `.minecraft/limitless_vehicle/rvp/` |
| 武器数据 | `data/rvp/weapons/<id>.json` → 资源 ID `rvp:<id>` |
| 武器显示 | `assets/rvp/display/weapon/<id>.json` |
| 载具数据 | `data/rvp/vehicles/<id>.json` |

发布或覆盖安装前请递增 `vehicle_pack.meta.json` 的 `version`）。

## 硬约束（与根目录 agents.md 一致）

- **不要改** `ywzj_vehicle` 本体源码；新逻辑写在 `limitless-vehicle-rvp-addon`。
- 新武器 `type` 只用 7 个公开类型：`rvp:missile` / `rocket` / `machinegun` / `bomb` / `laser` / `dispenser` / `targetingpod`。
- TV、ARH 制导仅挂在 `rvp:missile` 的 `guidance_data` 中。
- 改 JSON 字段时同步更新 [RVP包新增参数字段说明.md](./plan/RVP武器数据模型/RVP包新增参数字段说明.md)。
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
