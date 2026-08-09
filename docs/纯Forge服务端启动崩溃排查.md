# 纯 Forge 服务端启动崩溃排查（LocalVehiclePlayer dist 污染）

> 状态：根因已确认，修复方向待用户拍板
> 日期：2026-08-06
> 修复方向总结（含方案对比与决策项）：[修复方向/修复方案总结.md](修复方向/修复方案总结.md)
> 复现环境：`D:\ywzj\Forge-test-server`（纯 Forge 1.20.1-47.4.13 专用服务端，无 Sinytra Connector）
> 崩溃日志：`D:\ywzj\Forge-test-server\crash-reports\crash-2026-08-06_01.20.37-fml.txt`（三份一致）

## 崩溃现象

```
java.lang.RuntimeException: Attempted to load class org/ywzj/vehicle/vehicle/LocalVehiclePlayer for invalid dist DEDICATED_SERVER
    at org.ywzj.rvp.all.RVP_Entities.<clinit>(RVP_Entities.java:27)
    at org.ywzj.vehicle.all.AllEntities.<clinit>(AllEntities.java:160)
```

`RVP_MOD` 与 `YwzjVehicle` 两个 Mod 容器构造时，实体注册触发加载实体类 → 报错。

## 根因

1. `LocalVehiclePlayer`（本体，`ywzj_vehicle/.../vehicle/LocalVehiclePlayer.java:34`）标注了
   `@OnlyIn(Dist.CLIENT)`。
2. 本体的**公共类**（非 client 包）在**公共方法**中直接引用 `LocalVehiclePlayer`：
   - `entity/weapon/AmmoEntity.readSpawnData`（`:201`）`LocalVehiclePlayer.instance.getPlayer()`
   - `AbstractVehicle`、`FixedWingVehicle`、`MissileEntity`、`AerialBombEntity`、
     `WeaponUnit`、`RadarUnit`、`RotatableUnit`、`DecorationUnit`、`InputHandler`、
     `WarningReceiver`、`Radar`、`ElectroOptical`、`AbstractVehicleWeapon`、
     `VehicleMissile`、`VehicleMultiWeapons`、`ServerHitVehicleEvent`、
     `ServerDecorationAction`、`ServerBroadcastEntities`、`LocalVehiclePlayerEvent`、
     `DecorationItem`、`AllEvents` 等 **20+ 个公共类**（已用字节级扫描部署 jar 确认）。
3. Forge 专用服务端加载这些类时，转换管线（RuntimeDistCleaner + ASM 重算帧）需要加载
   `LocalVehiclePlayer` → 该 class 是 `@OnlyIn(CLIENT)` → RuntimeDistCleaner 直接抛错。
4. RVP 崩溃是**连锁反应**：`RVP_Entities.<clinit>` 注册 `rvp_missile` 时加载
   `RVP_MissileEntity` → 其父类 `RVP_BaseBullet → AmmoEntity`（本体公共类，带毒）→ 崩。
   RVP 自身公共类**全部干净**（字节扫描确认 `LocalVehiclePlayer` 只在 `client.*` 包与
   `client` 数组的 mixin 中）。

## 为什么之前没崩

Sinytra Connector 会中和 Forge 的 `RuntimeDistCleaner` 的 dist 检查，所以带 Connector 时
能跑；纯 Forge 必然崩。本体一直有这个隐患，只是从未在纯 Forge 专用服务端验证过。

## 修复方向（二选一，待确认）

### 方案 A：修本体（推荐）
把本体公共类中所有 `LocalVehiclePlayer` 引用改为「公共接口桥 + 客户端实现」模式
（即 RVP 已有的 `RVP_ClientActionsAccess`/`RVP_IClientActions`/`ClientActionsImpl` 模式），
或移到 `@OnlyIn(CLIENT)` 方法/客户端辅助类中。
- 优点：真正的根治，公共字节码不再含客户端类引用，服务端/客户端都稳。
- 代价：**违反「本体只读」约定**，需改本体 20+ 个文件；改动机械但量大。

### 方案 B：RVP 侧 DistClean mixin
对本体每个带毒公共方法写 `@Inject`/`@Overwrite` mixin，替换掉 `LocalVehiclePlayer` 引用。
- 优点：不动本体。
- 代价：需覆盖 20+ 个类，mixin 数量爆炸，且新 agent.md 已规定「非必要禁止 Mixin、
  禁止 @Overwrite」。**与当前方针冲突，不推荐。**

## 关键代码位置（供修复参考）

- 本体 LocalVehiclePlayer 声明：`ywzj_vehicle/src/main/java/org/ywzj/vehicle/vehicle/LocalVehiclePlayer.java:34`
- 本体 AmmoEntity 带毒用法：`ywzj_vehicle/src/main/java/org/ywzj/vehicle/entity/weapon/AmmoEntity.java:45,201`
- RVP 桥接样板（可复刻到本体）：`ywzj_rvp/src/main/java/org/ywzj/rvp/client/bridge/`
  （`RVP_ClientActionsAccess` / `RVP_IClientActions` / `ClientActionsImpl`）
- RVP 注册入口（崩溃触发点）：`ywzj_rvp/src/main/java/org/ywzj/rvp/all/RVP_Entities.java:27`
