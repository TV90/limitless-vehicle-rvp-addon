# RVP 拓展包开发文档

面向在 `limitless-vehicle-rvp-addon` 与 `limitless_vehicle/rvp` 载具包上配置武器、载具的开发者。

## 文档索引

| 文档 | 适合谁 | 内容 |
| --- | --- | --- |
| [RVP包新增参数字段说明.md](./RVP包新增参数字段说明.md) | 配置作者 | 武器 JSON 全字段说明（权威 schema） |
| [弹体运动学开发与测试.md](./弹体运动学开发与测试.md) | 弹道 / 性能调试 | `projectile_data` 运行时流程、与本体对照、常见问题 |
| [RVP伤害倍率与爆炸.md](./RVP伤害倍率与爆炸.md) | 平衡 / 移植 | `damage_factor`、直击与 `RVP_Explosion` |
| [plan/](./plan/) | 功能设计 | TV 导弹、制导架构等方案稿（非日常配置手册） |

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

## 相关代码入口

| 主题 | 类 |
| --- | --- |
| 武器加载与归一化 | `org.ywzj.rvp.all.RVP_WeaponTypes` |
| 弹体数据 | `org.ywzj.rvp.weapon.data.RVP_ProjectileData` |
| 弹体实体运动 | `org.ywzj.rvp.entity.projectile.RVP_BaseBullet` |
| 伤害倍率 / 爆炸 | `RVP_DamageFactor`、`RVP_DamageApplier`、`RVP_Explosion` |
| 机炮弹丸 | `org.ywzj.rvp.entity.projectile.RVP_BulletEntity` |
| 本体对照（只读） | `org.ywzj.vehicle.entity.weapon.BulletEntity`、`MissileEntity` |
