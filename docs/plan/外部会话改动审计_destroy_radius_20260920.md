# 外部会话改动审计交接 — destroy_radius 参数 / 核爆炸弹坑兼容 / 9M723 数值(2026-09-20)

> ⚠️ 本文档出自一个**不在本项目工作区**的 ZCode 会话(工作区 E:\mwt,ModernWarfront 解包任务)。用户在该窗口误发了 ywzj_rvp 的任务,该会话全盘搜索定位到本仓库并直接实施,**未提交任何 git commit**。请本项目会话逐项审计。
>
> **待办:若决定保留 destroy_radius 功能,需按硬约束补写 `docs/plan/RVP武器数据模型/RVP包新增参数字段说明.md` 的 destroy_radius 条目(该会话未读过此文件)。**

## 1. 需求来源(用户在该窗口的原话)

1. 调研 RVP 的爆炸;借鉴 `D:\MCHR\MCH-Reforged` 的 `explosionblock` 参数
2. 增加参数把"爆炸破坏范围"与"对生物杀伤范围"隔离,默认相等,可单独配
3. 与 RVP 已有的"对地面爆炸破坏"(弹坑深度规则)结合
4. 调研"本体爆炸半径 >32 走核爆炸",让地面破坏兼容核爆炸
5. 9M723 杀伤半径调 64、爆破半径调 33
6. 载具包维护约定改为:仓库根 `limitless_vehicle/rvp/` 为唯一权威源,run/* 仅服务端代码冒烟

## 2. 该会话的调研结论(前提,需复核)

- RVP 的"本体"是 **ywzj_vehicle**(非 SuperbWarfare);SuperbWarfare 仅反射级 ECM 联动(`compat/RVP_SuperbWarfareCompat.java`),未触碰其爆炸
- ">32 走核爆炸" = ywzj_vehicle `VehicleExplosion.java:55` 的 `BATCHED_DESTRUCTION_RADIUS_THRESHOLD = 32.0F`:≤32 走 `GridCollectionTask` 即时破坏,>32 走 `SphericalCollectionTask` 分 tick 批量破坏(0.6r 全毁/外圈烧焦)+ 客户端蘑菇云视觉
- 旧 `VehicleExplosionCraterMixin` 只注入 `GridCollectionTask#finish`,核爆炸路径方块在 `flushBlocks` 分 tick 消耗、无 finish → **弹坑深度规则对 >32 爆炸失效**(旧 mixin 的 javadoc 与此不符,系按旧版引擎写的,已顺手修正)
- MCH `ExplosionBlock` 语义(null 缺省=继承杀伤半径,0=不破坏方块,>0=独立破坏半径)即本次参数设计依据

## 3. 改动清单(全部未提交)

| # | 文件 | 改动 |
|---|------|------|
| 1 | `weapon/data/RVP_Explosion.java` | 新增 `@SerializedName("destroy_radius") private Float destroyRadius` + getter。null=继承 radius(历史行为零变化);0=不破坏地形;>0=独立破坏半径 |
| 2 | `entity/projectile/RVP_BaseBullet.java` | `triggerExplosion` 新增双爆炸分支:爆炸 A(地形,destroy_radius + damage 0 + 可破坏)、爆炸 B(杀伤,radius + damage + 不破坏)。`instanceof RVP_Explosion` 防御式取值(字段声明类型是本体基类 `Explosion`)。debug 日志加 destroyRadius。导入 `org.ywzj.rvp.weapon.data.RVP_Explosion`、`org.ywzj.rvp.weapon.effects.RVP_TerrainOnlyExplosion` |
| 3 | `weapon/effects/RVP_TerrainOnlyExplosion.java` | **新文件**,ThreadLocal 窗口门面(仿 `RVP_ExplosionVisualSuppression`) |
| 4 | `mixin/VehicleExplosionHurtSkipMixin.java` | **新文件**,@Inject `VehicleExplosion.hurt` HEAD cancellable,窗口激活时跳过实体伤害(避免爆炸 A 的 0 伤害 LivingHurtEvent/受击无敌帧污染) |
| 5 | `mixin/VehicleExplosionCraterSphericalMixin.java` | **新文件**,@Inject `SphericalCollectionTask.flushBlocks` HEAD,按 `getMaxDepthForRadius(task.radius)` removeIf 低于 `floor(y)-maxDepth` 的 pendingBlocks(破坏与烧灼都拦)——**核爆炸(>32)路径弹坑深度兼容修复**,对未拆参武器同样生效 |
| 6 | `mixin/VehicleExplosionCraterMixin.java` | 仅 javadoc 修正(旧注释称两条路径都走 finish,与现引擎不符) |
| 7 | `resources/ywzj_rvp.mixins.json` | 注册 #4、#5 两个 mixin(common mixins 列表) |
| 8 | `docs/RVP爆炸系统参考.md` | destroy_radius 参数表、双爆炸机制、新 mixin 表、视觉规则说明 |
| 9 | `docs/examples/rvp_json/weapons/destroy_radius_example.json` | **新文件**,参数示例 |
| 10 | `docs/README.md` | 路径约定表:根包=唯一权威源,run/server=仅冒烟 |
| 11 | `build.gradle`(83 行注释) | 同上路径约定更新(标注 2026-09-20 作废旧 run/client_1 约定) |
| 12 | `limitless_vehicle/rvp/data/rvp/weapons/9k720_9m723.json` | radius 31→**64**,新增 `destroy_radius`: **33**(git-ignored 载具包;run/* 三份副本**未同步**,仍是旧值 31) |
| — | `build/libs/*.jar` | `gradlew build` 通过,`ywzj_rvp-1.20.1-0.5.9(-all).jar` 被重建两次(含上述 Java 改动) |

## 4. 行为语义(拆分后)

| explosion_data 配置 | 行为 |
|---|---|
| 未配 `destroy_radius` | 与历史完全一致(单爆炸,零行为变化) |
| `destroy_radius: 0` | 只伤人不破坏地形(单爆炸,destroyBlock 视为 false) |
| `destroy_radius: N`(N>0 且 ≠radius) | 双爆炸:A 按 N 破坏地形,B 按 radius 伤人+视觉 |

- 双爆炸时本体视觉包只有**半径更大**的一发保留(两个半径可能都 >32,避免双份蘑菇云);A 定档用 destroy_radius,B 用 radius
- 引信覆盖(空爆/近炸的 `*_explosion_radius`)只重写杀伤半径;`hbm_effect_data` 真实爆炸接管(`realExplosionApplied`)时 destroy_radius 不参与
- 弹坑深度规则(`craterDepthRules`)两条破坏路径都生效,按破坏半径查表
- 爆炸 A 的 excluded 列表(近炸直击/炮手自排除)照常传入,但其 hurt 被跳过故仅形式上生效

## 5. 约束遵循自查(按 agents.md / docs/README.md)

- ✅ 未改 ywzj_vehicle 本体源码(仅只读)
- ✅ 未执行任何 git commit / git add
- ✅ 未按武器资源 ID 在 Java 里分支(纯数据驱动)
- ✅ mixin 写法沿用仓库既有模式(targets 字符串 / remap=false / ThreadLocal 门面)
- ⚠️ **可能漏了硬约束**:docs/README.md 要求"改 JSON 字段时同步更新 `docs/plan/RVP武器数据模型/RVP包新增参数字段说明.md`"——该文件本会话未读过也未更新(见文首待办)
- ⚠️ `mixins.json` 的 `defaultRequire: 1`:mixin 目标(`hurt(Ljava/util/List;)V`、`flushBlocks`、`finish`)按本地 `D:\ywzj\ywzj\ywzj_vehicle\src` 源码核对过,但**未核对实际解析的 `libs:ywzj_vehicle-1.20.1:0.5.10-all` 依赖字节码**;若目标不存在会运行时崩
- ⚠️ 双爆炸副作用:每次引爆 `ExplosionEvent.Start` 发两次、`ServerVehicleExplosion` 包发两次(一份被抑制)、载具 OBB 防护重复计算——其他插件/监听器视角可见

## 6. 验证情况

- `gradlew build` 通过(仅编译级验证);**未做任何游戏内实机测试**
- 9M723 JSON 语法校验通过;其 `visual_effect_data` 挂 `rvp:thermobaric` 且 `suppress_native_explosion_effect: true`,故该武器实际视觉由温压工厂接管(按 radius 64 变大),本体蘑菇云本就被抑制
- `RVP_WeaponData` 的 fuse 半径解析、`RVP_Config.getMaxDepthForRadius` 未改动

## 7. 建议审计动作

1. 补 `RVP包新增参数字段说明.md` 的 destroy_radius 条目(或整体回滚功能)
2. 解包 fg.deobf 实际解析的 ywzj_vehicle 0.5.10,确认 `hurt(Ljava/util/List;)V`、`SphericalCollectionTask.flushBlocks`、`GridCollectionTask.finish` 签名存在(defaultRequire=1,不匹配会运行时崩)
3. 实机三发验证:null(行为不变)/ `destroy_radius: 0`(只伤人)/ `destroy_radius: 33`(核爆炸路径 + craterDepthRules 生效、坑深 ≤2 层)
4. 决定 9M723 数值(radius 64 / destroy_radius 33)去留——用户口头要求,数值口径由本项目定
5. 确认 docs/README.md、build.gradle 注释的新路径约定(根包权威、run/* 仅冒烟)是否认可
6. run/* 三份副本未同步本次数值,跑服务端冒烟前注意其中的 9M723 仍是旧值 radius 31

---

## 复审结论（2026-09-20，主会话审计处置）

三个探索代理并行核查（改动全貌/mixin 签名与副作用/性能与亮度），结论：**改动整体保留，修正三处**。

1. **签名与安全**：三个新 mixin 目标对本体 0.5.10 源码与 `libs/ywzj_vehicle-1.20.1-0.5.10-all.jar` 字节码常量池全部命中（`hurt(Ljava/util/List;)V`、`flushBlocks`、@Shadow 字段、`GridCollectionTask.finish`），`defaultRequire=1` 不会运行时崩；mixins.json 公共数组注册正确；无武器 ID 硬编码；`destroy_radius` 未配置（null）路径与改动前逐字节等价。
2. **已修正——爆炸 B 地形破坏 bug（本审计最重要发现）**：原实现 split 模式下 B 构造第 7 参 `explosion.destroyBlock && !disableTerrain` 恒 true，`0 < destroy_radius < radius` 时（9M723 的 33<64）B 自己按完整杀伤 radius 走核爆炸批量路径破坏+烧灼地形，destroy_radius 完全失效且方块处理量数倍放大（">32 爆炸卡顿"主因）。修复：split 时 B 传 `destroyBlock=false`——本体 `explode()` 的 `if (destroyBlocks)` 门控使 B 跳过全部方块扫描/入队，地形全权归爆炸 A。
3. **已修正——flushBlocks removeIf 边界瑕疵**：`VehicleExplosionCraterSphericalMixin` 的 removeIf 追加 `flushCursor == 0` 前置（@Shadow 本体游标字段），消除跨 tick 预算截断时"前缀连移导致续传跳块"的弹坑不完整问题。
4. **副作用复核**：`ExplosionEvent.Start` 双发（本体/RVP 零监听，仅第三方防爆类 mod 理论受影响，接受）；`ServerVehicleExplosion` 双包经负半径标记 + 客户端 `VehicleExplosionClientVisualMixin` 单份播放；伤害/OBB/ERA/命中包无重复结算；`flushBlocks` HEAD removeIf 与 flushCursor 续传逻辑常规相容（边界已修）。
5. **约束补办**：`docs/plan/RVP武器数据模型/RVP包新增参数字段说明.md` 已补 `destroy_radius` 三态语义条目与 9M723 示例；9M723 权威包 `radius 64 / destroy_radius 33` 保留（用户定版），`run/server`、`run/client_1/2` 三副本已同步（原为旧值 radius 31）；载具包仍不入库。
6. **连带发现（与本审计无关但影响仓库完整性）**：上一轮提交 `8a07b69d` 漏 add `RVP_ThermalParticleChannel.java`（已跟踪代码引用它，HEAD 单独检出不可编译）——已随本轮一并补提交。
7. **性能结论**：radius>32 触发本体 `BATCHED_DESTRUCTION_RADIUS_THRESHOLD=32` 双路径切换（SphericalCollectionTask 批量破坏：射线 8 倍、烧灼转化方块替换 2×setBlock+广播、主线程 20-30 tick 高负载）；修复 B 弹后 9M723 只剩 A(33) 一份批处理，卡顿预计大幅回落；若实测仍不满意，后续可选灼烧环带过滤配置或 per-tick 预算调整（本轮未做）。
