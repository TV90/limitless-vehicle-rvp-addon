# RVP 交接文档：热成像通道与 destroy_radius 与批量修复（2026-09-20）

> **用途**：上下文交接。新窗口从本文档恢复全部背景；文末附新窗口开场提示词。
> **当前状态**：HEAD=`25aff805`（master），已推 gitee；**github 落后 4 个提交（ea9a0bec/9d2ba2bf/53e95e0f/25aff805），待网络恢复 `git push github master` 补推**。工作区剩余均为不入库项（载具包本地 JSON、logs、scripts/texture_backup 备份）。
> 前置：`docs/plan/RVP交接_BVR探测修复与观瞄视角射弹与HITL相机_20260919.md` 仍有效。

---

## 一、本窗口主线（按提交序）

### A. 固体发动机尾迹与尾焰（`8a07b69d` 前）
1. **凝结云保持期绑定燃尽**：`RVP_BaseBullet.ticksUntilMotorStopsBurning()`（钳 [0,1200]）经桥 `holdTicks` 传入粒子，`holdEndTick=max(holdTicks+20,108)`、`lifetime=holdEnd+120+rand(60)`——发动机开启时飞过的距离全程留云；橙焰相位改定长 24~36t；抖动重掷间隔随寿命 2t→14t 递增 + 幅度湍流衰减（刚喷射湍急→稳定）。
2. **弹体尾焰隔烟修复**：本体 translucent 顶点随主 bufferSource 推迟到粒子阶段后 flush，烟浪盖不住尾焰——`RVP_BedrockProjectileEntityRenderer` 改独立 `FLAME_IMMEDIATE_SOURCE` 当场 endBatch（实体阶段上屏）。

### B. 热成像白热通道（`RVP_ThermalParticleChannel`，client）
"画进本体 thermal_buffer 者即热源"（thermal.fsh 按 luma+alpha 混白热）。三条来源：
1. **RVP 粒子登记表**（WeakHashMap 弱表，构造器自登记）：RocketFlame/MchrSmoke/MchrFlare/WhitePhosphorus；
2. **引擎 translucent 批白名单**（Smoke/LargeSmoke/CampfireSmoke/BaseAshSmoke/Flame，**运行时反射 `getParticles()`**，本体 SmokeCloudParticle 跳过防双画）；
3. **自绘特效 renderThermal 入口**：温压蘑菇云（`RVP_ThermobaricRenderer.render(…,thermalMode=true)`，火球色灰化+60% 混白——橙色 luma 低天生不白）+ 内置 HBM 爆炸（`RVP_ExplosionVisualManager.renderThermal`）。
阶段=**AFTER_PARTICLES**（⚠️ 不可用 AFTER_WEATHER：该阶段 ModelViewStack 已含视图矩阵，再乘即双重视图→粒子错位，已踩坑）。混合覆盖：RGB 叠加饱和 + alpha over + depthMask(false)。渲染器若在分组循环里复用自有 RenderType，begin 后统一覆盖。

### C. destroy_radius（外部会话改动，审计后保留+修正，`0bb57e3a`）
- 参数三态：null=继承 radius（零变化）；0=不破坏地形；>0 且≠radius=「地形爆炸 A（destroy_radius，伤害 0）+ 杀伤爆炸 B（radius，**不破坏地形**）」双爆炸；
- 审计修正：①B 弹 destroyBlock 原 bug（恒 true → destroy_radius 失效+卡顿主因）已修；②CraterSphericalMixin removeIf 加 `flushCursor==0` 前置（防跨 tick 跳块）；③字段说明已补办；
- mixin 签名对本体 0.5.10 全 PASS；ExplosionEvent.Start/ServerVehicleExplosion 双发有负半径抑制兜底；9M723 定版 radius 64 / destroy_radius 33。

### D. >32 卡顿 → 强制即时破坏路径（`cda631d9`）
本体 `BATCHED_DESTRUCTION_RADIUS_THRESHOLD=32` 双路径切换是卡顿根因（SphericalCollectionTask 分 tick+烧灼替换）。新增 `VehicleExplosionImmediatePathMixin`（**@ModifyConstant** 32.0F，allow=2）+ `RVP_ExplosionImmediatePath` ThreadLocal 窗口 + `RVP_Config.forceImmediateExplosionDestruction`（默认 true）：**RVP 弹爆炸任意半径走 ≤32 即时路径**（GridCollectionTask+即时破坏，单 tick hitch、无烧灼替换）；本体/其它 mod 不受影响。烧灼过滤/per-tick 预算为后续可选优化（实测仍卡再做）。

### E. 第一批四项（`9d2ba2bf`）
1. **爆炸伤害倍率拆分（已在 F 返工，见下）**；
2. **本体爆炸晃动钳制**：client mixin `FirstPersonHandlerExplosionShakeMixin`（@ModifyArg 强度 × `explosionShakeIntensity` 0~1）——本体晃动唯一入口（爆炸 effect+大口径导弹飞行 5t）；
3. **ERA 入射角 35° 上界**：直击命中激活 ERA 骨骼 → 角度衰减取 `min(实际角, eraMaxIncidenceAngle)`（common 默认 35，90 关）；跳弹判定用原始角（数据流独立）；`lastImpactIncidenceAngleDeg` 字段不改写；
4. **载具物品副手静默拦截**：`RVP_OffhandVehicleSpawnGuard`（RightClickItem HIGHEST+OFF_HAND+instanceof VehicleSpawnItem→cancel，双端静默）。

### F. 第二批（`53e95e0f`）
1. **爆炸倍率定位返工（用户澄清）**：`explosion_damage_factor` 移**武器侧**（`explosion_data.explosion_damage_factor`，默认 1.0，仅载具目标，ThreadLocal `RVP_ExplosionDamageFactor` 窗口）；载具侧改名 **`vehicle_explosion_damage_factor(_default)`**（per-bone 受击倍率，任何来源爆炸）；结算=本体距离衰减 × 武器侧 × 载具侧。例：面板 300→偏差衰减 270→武器侧×2=540。
2. **IR 离轴角 bug 四层修复**：bug A=HMD 20t 保活保留超锥锁+授权只查锁存在；bug B=RF 落锁(90°锥)/EO 捕获不查 IR 离轴角+发射复核基准=伺服当前指向空转+服务端不查。修复：`RVP_IrLockHelper.isLaunchTargetWithinOffAxis`（boresight 按 `off_axis_stacks_with_station_rotation` 二选一）三点收口=客户端 `passesShootLockGates` 终检（超锥拒射提示 `ui.ir_target_off_axis`，lang 已加）+ 服务端 `dispatchShots` 权威终检 + `WeaponUnitTickFireControlMixin` RF 供锁落锁前校验；20t 保活保留（授权已解耦）。
3. **船适配 P1**：NCTR 早期标签（ScopeOverlay/TacticalMapScreen）加 VesselVehicle→"SHIP"；战术地图图标 `ship.png`（程序生成占位，正式素材直接替换文件）。P2 遗留：远程可见性分类（现状归地面）、gunner GROUND_VEHICLE capability、烟雾规避、战术曝光——均无崩溃，待反馈。

### G. 编译兼容修复（`25aff805`，其它开发者编译失败反馈）
`RVP_ThermalParticleChannel` 曾 import 本体 mixin 类 `ParticleEngineAccessor`（读引擎批次）——该类只在本体 dev `-all` jar（libs/ 本地依赖，未入库），其它开发者的本体发布 jar 编译 classpath 不含它。已改**运行时反射** `getParticles()`（mod 自定义名不混淆，本体 ThermalHandler 强依赖故必然存在），失败静默降级。**铁律：RVP 代码禁止 import 本体 `org.ywzj.vehicle.mixin.**` 包**。

### H. 许可证声明体系
`THIRD_PARTY_NOTICES.md`（基座 ywzj_vehicle 居首/HBM NTM Rebirth LGPL-3.0 并入/SuperWarfare 与 HBM 本体参考来源/MCHR 致谢——MCHR 为作者自有不列第三方）；`mod_license` 改 GPL-3.0（解决与 LICENSE 冲突）；HBM 资产迁移清单策略改"GPL-3.0 并入保留"。flare.png 补 alpha（脚本 `scripts/fix_flare_alpha_20260920.py`，原件备份 `scripts/texture_backup_20260920/`，**备份不入库**）。

---

## 二、⚠️ 冒烟测试新方法（必须遵守，防止服务端杀不掉）

**背景**：`TaskStop`/终止后台 shell 只杀外层 bash，Gradle fork 的服务端 JVM（`GradleWrapperMain`+`BootstrapLauncher`）会孤儿化残留（此前多轮属实，用户手动兜底）。标准流程：

1. `./gradlew build > build_xxx.log 2>&1` 确认成功后 `./gradlew runServer --console=plain > server_smoke_xxx.log 2>&1 &`；
2. 每 10 秒轮询日志，`Done (Xs)!` 即通过（对照 §5.1 噪音基线只看新增；grep 中文加 `-a`）；
3. **结束必须显式清理并验证**（tasklist 管道不可靠，一律 jps）：
   ```bash
   PIDS=$("C:/Program Files/Java/jdk-21.0.11/bin/jps.exe" -l | grep -i "BootstrapLauncher\|GradleWrapperMain" | awk '{print $1}')
   for p in $PIDS; do "/c/Windows/System32/taskkill.exe" //F //PID $p; done
   "C:/Program Files/Java/jdk-21.0.11/bin/jps.exe" -l   # 复验干净
   ```

## 三、⚠️ run 目录载具包策略（2026-09-20 用户新指令）

**run/client_1、run/client_2、run/server 的载具包暂不与权威载具包（`limitless_vehicle/rvp/`）同步，暂只做代码启动测试。** 9M723 三副本此前已手动同步为 radius 64/destroy_radius 33，保持现状即可；新窗口**不要**再主动做 run 同步（docs/README 路径约定中"权威源=仓库根包"仍成立，run 副本仅冒烟用）。

## 四、待实机验证清单

- [ ] 9M723（64/33，强制即时路径）：爆破圈 33、单 tick hitch 后不再持续掉 tick、无烧灼替换（石→深板岩/沙→玻璃消失）；
- [ ] 热成像：温压火球白度≥凝结云、原版烟火（CAMPFIRE_SIGNAL_SMOKE 等）变白、火球为全场最白；
- [ ] 爆炸倍率：武器配 `explosion_data.explosion_damage_factor: 2` 打载具，伤害≈偏差衰减后×2（HBX/命中调试对照）；载具配 `vehicle_explosion_damage_factor` 受击缩放；
- [ ] IR 离轴角：甩出离轴角后 20t 内不可发射（提示 ui.ir_target_off_axis）、EO/RF 锁定的 IR 弹同样受限；
- [ ] 晃动钳制（explosionShakeIntensity 调 0 爆炸不晃）、副手拦截（F 切副手后右键无反应）、ERA 爆炸为 MCHR 特效（radius 3×模块缩放）；
- [ ] 回归：直击 hitbox_damage_factor、煤油款尾迹、本体武器爆炸（未配新参数=原行为）。

## 五、已知边界与坑

1. **github 待补推 4 提交**（网络 SSL 失败），恢复后 `git push github master`；
2. **禁止 import 本体 `org.ywzj.vehicle.mixin.**` 包**（dev 构件内容，跨环境编译必炸；复用本体注入产物用运行时反射，见 G）；
3. 本体版本要求：热成像完整效果需本体含 `5ddf8d4`（烟通道+accessor，0.5.10 近期快照，船更新 `e513a11` 已含）——旧快照本体下 RVP 原版烟火热成像自动停用（不崩）；
4. `signal_intensity_factor_on_radar` 已封印勿恢复；自车雷达对弹药 512 结构顶（见 0919 交接）；
5. ExplosionEvent.Start 双发（本体/RVP 零监听，第三方防爆 mod 理论受影响，已接受）；>32 剩余优化选项：灼烧环带过滤配置/per-tick 预算 @Mutable（实测仍卡再做）；
6. 船 P2 待办：远程可见性分类（现状归地面类）、gunner capability、烟雾规避、船图标正式素材（当前 ship.png 为程序生成占位）；
7. Git 注意：github 网络抖动重试即可；plan mode 下 git 命令偶发被拦（拆只读命令）。

## 六、新窗口开场提示词（复制即用）

> 项目：D:\ywzj\ywzj\ywzj_rvp（RVP，Limitless Vehicle 的 Submod；本体 D:\ywzj\ywzj\ywzj_vehicle 只读）。
> 规范：agents.md 与 docs/调试与修复规范.md。
> 交接：先读 docs/plan/RVP交接_热成像与destroy_radius与批量修复_20260920.md 恢复全部背景（重点：§二冒烟新方法必须遵守——结束后 jps 查 BootstrapLauncher/GradleWrapperMain 并 taskkill，防服务端孤儿；§三 run 目录载具包暂不同步，只做代码启动测试）。
> 状态速览：HEAD=25aff805 已推 gitee，github 落后 4 提交待补推。本窗口完成：热成像白热通道（RVP 粒子+原版烟火白名单+温压/HBM 自绘特效 renderThermal）、destroy_radius 三态双爆炸（审计保留+修正 B 弹 bug）、RVP 弹爆炸强制即时破坏路径（>32 不走核爆批处理）、爆炸倍率拆分（武器侧 explosion_damage_factor + 载具侧 vehicle_explosion_damage_factor）、晃动钳制 explosionShakeIntensity、副手载具物品静默拦截、ERA 入射角 35° 上界、ERA 爆炸 MCHR 特效、尾焰隔烟修复、凝结云保持期绑定燃尽+抖动降频、船适配 P1、编译兼容修复（禁 import 本体 mixin 包改反射）、许可证声明 THIRD_PARTY_NOTICES.md。
> 待办：实机验证清单见交接文档 §四；第二批遗留 P2 见 §五-6；github 补推见 §五-1。
