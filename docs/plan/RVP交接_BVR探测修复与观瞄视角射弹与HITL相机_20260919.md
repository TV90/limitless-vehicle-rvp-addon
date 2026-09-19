# RVP 交接文档：BVR 探测修复与观瞄视角射弹与 HITL 相机（2026-09-19）

> **用途**：上下文交接。新窗口从此文档恢复全部背景；文末附新窗口开场提示词。
> **当前状态**：本窗口（09-18~09-19）全部工作已提交并推送双远端，HEAD=`9f9e5522`；工作区干净（仅 docs/README.md 另一线 WIP、载具包本地 JSON 改动与日志不进 git）。
> 前置背景：0917 交接（`docs/plan/RVP交接_创造保护与弹药隐身与蓄力UI_20260917.md`）仍有效，本文为其后续。

---

## 一、本窗口会话主线（按提交序，`git log --oneline -30` 可复核）

### A. 激光武器四项修复 + 烟幕反制（`b44ea573`）
1. **过热熄束**：`RVP_LaserWeapons.canRenderBeam` 追加 `!isOverheated`——服务端过热门拒伤后客户端光束仍渲染的分叉修复（刻意不查 `isCoolingDown()`，逐发冷却不剪持续光束）；
2. **命中点精确化**：原版 `ProjectileUtil.getEntityHitResult` 返回位置=实体脚底，改为对命中实体碰撞箱精确 clip（修光束折向生物脚底 + 载具命中箱因子按脚底点结算的偏移）；
3. **烟幕反制激光**：`RVP_LaserRaycast.traceFrom` 命中 `RVP_SmokeEntity`（rvp:rvp_smoke）AABB 即截断光束于云团表面（hitEntity=null 自动跳过伤害/告警/致盲，不分敌我，双端同射线，gunner 激光同被反制）；
4. **生物无敌帧**：命中结算后清零 `invulnerableTime`（与 `RVP_BaseBullet.applyEntityHitDamage` 尾部同款）——修复激光打生物被原版 20t 无敌窗吞伤害。

### B. 热量表双线程 CME 崩溃修复 + gameTime 时钟（`695bfdd5`）
crash-2026-09-18_05.10.20：`RVP_WeaponHeatManager` 静态 HashMap 被单机服务端/客户端双线程并发 `computeIfAbsent` → CME。修复：静态 `LOCK` 互斥锁覆盖全部 public 入口（**不能按端拆表**——该表设计目的就是跨端共享热量）。**连带修掉第二颗雷**：冷却时钟 `tickCount`（双端独立计数域）→ 世界 `gameTime`（双端同域），否则交织写 lastTick 会把热量瞬间清零。**并入了 09-17 热量跨端共享表未提交 WIP**（补齐 HEAD 上 fireController 单参调用与 manager 双参签名不一致的断裂中间态）。

### C. 观瞄视角射弹原点分离（大功能，`b44d5dbc`）——`rvp_sight_fire_disguise`
**观瞄视角（SCOPE）下实际弹从观瞄相机坐标射出（方向不变=准星方向，所见即所得），视觉上从炮口出弹**：
- 配置：载具 JSON 武器站层 `rvp_sight_fire_disguise{disguise_ticks=1, blend_ticks=3, max_distance=32}`，**仅 MACHINEGUN 类弹种适用**（用户定版收敛；火箭/导弹/炸弹回本体原行为）；
- 链路：客户端 SCOPE 状态差分+30t 心跳（C2S `C2SScopeViewSync`，**协议 12→13**）→ 服务端 `RVP_ScopeViewStateTable` → `RVP_ProjectileSpawner` 覆盖 muzzle（`spawnMuzzle = 观瞄相机`，`worldOpticalSightPosition` 服务端同款解析）→ 伪装数据（visualMuzzle/actualSpawn/时长）随生成包同步 → `RVP_SightFireDisguiseRender` 把模型/曳光/尾焰平移渲染为"炮口出发的平行弹道"，blend smoothstep 合流真实弹道；尾迹在伪装期跳过；
- 广播克隆继承分角度隐身因子：`RVP_BaseBullet.readData` 末尾按 remoteWeaponId 走 `getResolvedWeaponConfig()` 解析 `ammo_radar_rcs_factor` 写入克隆字段；
- **修复过 NPE**（adaptScopeCrosshair 短路求值 vehicle 未赋值先解引用，crash-2026-09-19_00.39.56）；
- 试点载具：ztz100（`turret`，仅主包有此 JSON）、lav25/vt4/m1a2sep/t90m/zbl08a/t84bm/abramsx（`turret`+`commander_machine_gun` 两站，主包+run 三副本同步）。

### D. 准星 RVP 弹道适配（三轮演进，最终形态）
观瞄中 RVP 弹药的 `weaponHitPos` 一律按 RVP 物理积分 march 重算（无条件，不要求配置）：
- `RVP_CcipUtil.computeBulletImpact` 按 kind 走 `RVP_UnguidedBallisticMath` 同款积分器（MACHINEGUN→stepCannon 先移动后摩擦重力；BOMB→stepBomb、MISSILE/ROCKET→stepProjectile 先受力后移动，含 drag_in_air/恒速/速度钳制），逐 tick 本体 `VectorUtil.hitPosition`（方块+实体）求交，视距 512 格帽、无命中返回 null；
- march 原点跟随实际出弹点：配了伪装=观瞄相机，没配=炮口；
- **准星对天空居中**（无命中不标记数千格外弹道终点）；
- **仅 MACHINEGUN 类启用 march**（用户定版：火箭/导弹/炸弹含推进段预测不准，回本体直线）；
- 附带：同步边界**静止鬼影修复**（本地同步后及时删除同 id 广播克隆，`mergedClientEntities`）；
- 调试：`/rvpdebug radarammo on|off`（`RVP_RadarAmmoDebug`，logs/rvp_radar_ammo_debug.log，记录本地/克隆弹药计数、每弹 dist/sig/pass）。

### E. BVR 弹药探测修复（三段，全部 `9f9e5522` 前后落地）
用户症状：bukm3+96L6 组网、雷达（1500/3500）只能探测视距内（~500 格）弹药。
1. **中继链 √maxScan 单位错误**：`RVP_ExternalRadarSyncService.appendAmmoTargets` 的 `effectiveMaxSqr = maxDistance × sig²`（线性值误当平方距离）→ 实际半径 √3500≈59 格。修复：`maxDistance² × sig²`；
2. **广播克隆 signatureSize=0**：超视距弹药以广播克隆（serverEntities）存在但 `isRadarDetectableAmmo()` 要求 `signatureSize > 0`，克隆无 initFromWeapon 保持字段默认 0 → 永不可探测。修复：判定补 `radarRcsSide > 0` 分支（克隆 radarRcs 默认 1.0）；
3. **拦截残留鬼影**：被拦截弹药的广播克隆无人调 setRemoved（isAlive 恒 true）且位置冻结在拦截点，`tickContactHold` 的存活/扇区/距离门拦不住 → 接触表无限期保留"静止+旧速度矢量+无法锁定"鬼影。修复：克隆被本体 prune 移出 serverEntities 且本地无同 id 实体时立即移除接触（残影亚秒级）。
- **附带结论**：自车雷达（客户端链路）对弹药结构性封顶 512 格（MC 实体同步上限，与 RCS 改动无关），BVR 正解=96L6 中继链；旧 5/6 倍率用户记忆中的"几千格探测"实为放大后值。

### F. 其它
- **HITL 相机沿弹轴前移 2m**（`RVP_ClientHitlCamera.NOSE_OFFSET` 0.35→2.0，用户 workaround：TV 画面脱离自身尾焰烟）；
- **railgun 同轴蓄力真根因**（`2a709593`）：`isFireKeyDown` 用 `getCurrentWeapon()==weapon` 恒等比较，Multi 组内恒假落"任意键"兜底 → 打同轴副键驱动主选中 railgun 蓄力；修复=`RVP_WeaponResolveHelper.currentSecondary`（unwrap）+ `isFireKeyDown` 全解包比较（0917 修复只改了 chargeOwnInput 门未改输入源）；
- **HBM 固体发动机凝结云尾焰样式**：路由调整——`rvp_rocket_flame`=固体发动机凝结云款（橙焰 12% 相位 + 烟相位中灰渐变灰白 + 寿命 240~340t + 前 45% 保持 + 距离 LOD 256 单层/1024 消亡 + 层间抖动每 tick 重掷帧间插值）；`rvp_kerosene_black_smoke`=液氧煤油黑烟技术储备款（原观感）；9k720_9m723/bukm3_9m317ma/f14a_iriaf_aim23b 三弹生效中（JSON 已切，载具包不进 git）；
- **`RVP_MiscData` 封印已删参数** signal_intensity_factor_on_radar（JSON 写该键被 Gson 静默忽略，等效恒 1，勿恢复）。

---

## 二、语义定版速查

### 观瞄视角射弹原点分离（rvp_sight_fire_disguise）
| 场景 | 行为 |
|---|---|
| 观瞄（SCOPE）+ 站配置启用 + MACHINEGUN 类弹 | 实际弹从观瞄相机出弹（方向=准星），视觉从炮口出弹伪装 1t 后 3t smoothstep 合流 |
| 观瞄 + 未配置站 / 火箭导弹炸弹 / gunner / 第三人称 / 座舱 | 全部本体原行为（炮口出弹、直线准星） |
| 自车雷达对弹药探测 | 结构性封顶 512 格（MC 实体同步上限）；BVR 正解=96L6 中继（3500×分角度因子） |

### 准星 RVP 弹道适配（无条件全局）
- 观瞄中 RVP 弹药 `weaponHitPos` = RVP 物理积分 march（仅 MACHINEGUN kind 启用 march，其余回本体直线）；
- 原点=实际出弹点（配伪装=观瞄相机/否则炮口）；对天空居中；同步边界无鬼影（克隆及时删除）。

### 尾迹样式路由
| 样式名 | 观感 | 状态 |
|---|---|---|
| `rvp_rocket_flame` | 固体发动机凝结云：橙焰 12% → 烟中灰渐变灰白（0.9），寿命 240~340t，前 45% 保持，LOD（>256 单层+1.25×、>1024 消亡） | **默认生效**（9k720_9m723/bukm3_9m317ma/f14a_iriaf_aim23b/f14d_aim54c/irist_sl/j16_yj80/su57_kh38/su57_kh58 八弹） |
| `rvp_kerosene_black_smoke` | 液氧煤油黑烟技术储备（寿命 45~65t 黑烟） | 配置可选 |

### 已封印参数
`signal_intensity_factor_on_radar` 已全量删除（b9a74592）：JSON 写该键被 Gson 静默忽略（等效恒 1），`RVP_MiscData` 类注释有封印警告——**勿恢复**（BVR √ 事故源头）。

---

## 三、关键文件/类速查

| 主题 | 位置 |
|---|---|
| 观瞄状态上行/服务端表 | `network.C2SScopeViewSync` + `sight.RVP_ScopeViewStateTable`（客户端 `client.state.RVP_ScopeViewSyncClient` 差分+30t 心跳） |
| 观瞄出弹覆盖 | `weapon.core.RVP_ProjectileSpawner`（旧入口 muzzle 处 resolveSightFireDisguise）+ `RVP_ProjectileSpawnContext`（sightFireDisguise 组件） |
| 准星 RVP 弹道 march | `util.RVP_CcipUtil.computeBulletImpact` + `client.state.RVP_ScopeViewSyncClient.adaptScopeCrosshair` |
| 弹药 BVR 探测三链 | `radar.RVP_RadarScanHelper.appendRvpAmmoTargets`（主链✓）、`network.RVP_ExternalRadarSyncService.appendAmmoTargets`（中继，√ 已修）、`entity.gunner.ai.GunnerTargeting`（感知门✓） |
| 广播克隆可探测判定 | `entity.projectile.RVP_BaseBullet.isRadarDetectableAmmo`（signatureSize>0 \|\| radarRcsSide>0） |
| 克隆雷达因子继承 | `RVP_BaseBullet.readData` 末尾（按 remoteWeaponId 解析配置） |
| 鬼影清除 | `RVP_ClientRadarTickHandler.mergedClientEntities`（本地同步删同 id 克隆）+ `tickContactHold`（克隆不在表即删接触） |
| 观瞄伪装渲染 | `client.render.RVP_SightFireDisguiseRender`（PoseStack 平移 + smoothstep 合流）+ `RVP_LaserBeamSmoothing` 同类 |
| 烟色调参数化 | `client.particle.RVP_RocketFlameParticle`（smokeGreyMin/Spread/Whiten/flamePhaseRatio 实例字段；ofTrail=凝结云 / ofKeroseneBlackSmokeTrail=黑烟储备） |
| 凝结云尾迹样式判定 | `weapon.data.RVP_EffectsData.isMissileNativeTrailSolidMotor` |
| HITL 相机 | `client.state.RVP_ClientHitlCamera`（NOSE_OFFSET=2.0 沿弹轴前移） |
| 诊断 | `/rvpdebug radarammo`（`radar.RVP_RadarAmmoDebug`，logs/rvp_radar_ammo_debug.log） |
| 热量表（锁+gameTime） | `weapon.core.RVP_WeaponHeatManager` |

---

## 四、待实机验证清单

- [ ] **观瞄出弹**（ztz100/lav25/vt4/m1a2sep/t90m/zbl08a/t84bm/abramsx 八车）：观瞄视角抵近射击弹着点=准星；视觉弹药从炮口出 1t 后 smoothstep 合流；炮口烟仍在炮管；交界处无静止鬼影；第三人称/座舱不变；
- [ ] **准星下坠**：T90M 3of26（velocity 12/gravity -0.01）观瞄远距——方框落在带下坠真实弹着点（无需配置）；APFSDS 高初速弹不回归；对天空准星居中；
- [ ] **BVR 弹药探测**：bukm3+96L6 对超视距来袭导弹恢复探测（3500×分角度因子）；被拦截后接触 ~1 秒内消失（无静止鬼影）；cssa5 自车雷达 512 内照常；
- [ ] **隐身弹 BVR**：akf98a/风暴阴影超视距克隆按分角度因子收缩（迎头仅 maxScan×0.08 内可见）；
- [ ] **HITL 相机**：TV 视角位于弹体前方 2 米、画面无自身尾焰烟；锁定照常；
- [ ] **固体尾焰**：9M723/9M317MA/AIM-23B 尾迹=橙焰（12%）→灰白凝结云（12~17 秒缓缓散开）；远距单层 LOD 观感；
- [ ] **railgun**：ztz100 打同轴机枪 railgun 蓄力条不涨；蓄力中松开 0.2 秒跌回；
- [ ] **热量表**：单机过热可正常攒满/冷却（不崩、不秒清零）；
- [ ] **激光**：过热熄束、打生物连发掉血（无无敌帧）、烟幕遮激光、命中点不折向脚底；
- [ ] **回归**：飞机雷达对载具探测、gunner AI、本体武器、第三人称、座舱全部零变化。

## 五、已知边界与坑

1. **自车雷达对弹药 512 结构顶**：MC 实体同步上限，非 bug；BVR 正解=96L6 中继。可选后续（需用户定版）：RemoteAmmoSyncService 6144 格快照并入自车雷达显示；
2. **BVR 克隆按标称 RCS**：广播数据不携带分角度因子，隐身弹超视距克隆按 1 处理（隐身突防 BVR 优势打折，如需隐身 BVR 突防要给广播加字段，另做）；
3. **协议 12→13**：旧客户端无法连新服；
4. **载具包本地改动清单（不进 git，换机需手动同步）**：观瞄配置 8 车（ztz100 仅主包；lav25/vt4/m1a2sep/t90m/zbl08a/t84bm/abramsx 主包+run 三副本）；尾迹样式 8 弹（主包+部分 run 副本，f14d_aim54c/9k720_9m723 无 client_1/2 副本等按实际存在同步）；
5. **粒子抖动已降频**（每 tick 重掷+帧间插值），如仍嫌闪可加大插值窗口；
6. **`rvp_rocket_flame` 样式语义已变更**（黑烟→凝结云）：外部如依赖旧观感需知会；
7. **git 注意**：`git log -- <深路径>` 在 Git Bash 偶发空输出（用 `--name-only` 全列表代替）；`python` heredoc 带 UTF-8 中文需 `python -X utf8` 或首行 coding 声明；plan mode 下 `git show <commit> -- <path>` 偶发被拦截（拆分命令）。

## 六、新窗口开场提示词（复制即用）

> 项目：D:\ywzj\ywzj\ywzj_rvp（RVP，Limitless Vehicle 的 Submod；本体 D:\ywzj\ywzj\ywzj_vehicle 只读）。
> 规范：agents.md 与 docs/调试与修复规范.md。
> 交接：先读 docs/plan/RVP交接_BVR探测修复与观瞄视角射弹与HITL相机_20260919.md 恢复全部背景。
> 状态速览：HEAD=9f9e5522 双端同步。本窗口完成：观瞄视角射弹原点分离（rvp_sight_fire_disguise，实际弹从观瞄相机出弹+炮口伪装，8 车已配）+ 准星 RVP 弹道全局适配（下坠/天空居中/无鬼影）+ BVR 弹药探测三段修复（中继 √maxScan 单位错误/广播克隆 signatureSize=0/拦截鬼影）+ 同步边界鬼影修复 + 广播克隆继承分角度隐身因子 + HITL 相机沿弹轴前移 2m + 固体发动机凝结云尾焰样式（rvp_rocket_flame 路由调整）+ 8 弹尾迹配置 + railgun 同轴蓄力真根因 + 热量表 CME + 激光四项修复 + radarammo 诊断工具。待办：全量实机验证清单见交接文档 §四；已知边界与坑见 §五（自车雷达 512 结构顶、BVR 克隆按标称 RCS、协议 12→13、载具包本地改动清单）。
