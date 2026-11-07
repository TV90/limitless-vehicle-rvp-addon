# RVP 会话交接：RWR 自身过滤与 HITL 画面隔离（2026-11-03）

当前 HEAD `593ddc8183dd3d20115a931b2b157652b358d5ce`，提交 `Prevent radar self-warnings and isolate HITL visuals`。mod、源包/build/rvp-pack/full.jar 内嵌包/A1 实际包统一为 `0.1.0s_beta_0.6.2`，协议仍 37；只更新包版本标记，全部载具与武器参数不变。实际客户端 jar：`D:\ywzj rvp测试用客户端A1.0\ywzj rvp测试用客户端A1.0\.minecraft\versions\永无止境rvp测试服\mods\ywzj_rvp-1.20.1-0.1.0s_beta_0.6.2-full.jar`，SHA256 `963cacbfc2476c704ecfac135bb9a470bcc138a11aa92b65a0c99ef6c8ac7050`。四处 1138 个生产文件逐文件一致，工程文件仍排除；options.txt、HITL 远程 TOML 和照明 TOML 按部署前哈希验证未改。旧 r-full 与旧 meta 两项已移回收站，MOVE_LOG 逐条追记并校验，无真删除。

用户报告 RWR 偶发显示自身。代码确认：本体 Radar.getClientLevelEntities 的本地查询排除 owner，却在合并 serverEntities 时按已收集 ID 去重，把 owner 的广播克隆重新加入；phase 并未取消本体 tickDetect，少数姿态下原生扫描会将自身克隆入表并用 DETECT 回写服务端。RVP 原接触保活、告警中继和接收端均无自身兜底。

新增公共安全辅助 RVP_RadarSelfFilter：在同一世界按 entity ID 识别自身（克隆 UUID 不保证同步），清理原生合并扫描结果、RVP 扫描及历史航迹/雷达锁/武器站锁/RWR 自身条目。复用 RadarRemoteHbmEntitiesMixin 既有 RETURN 注入点过滤本体扫描列表；复用 ClientRadarActionMixin 既有 HEAD，在服务端线程接管 DETECT，保持本体主雷达路由但拒绝自身；自身 LOCK 按无效锁走原解锁链。RVP 告警收发和本体剩余 MISSILE_LAUNCH 通道兜底拒绝同 ID 自告警。不同 ID 的同型号、友军、敌军不受影响。未新增 Mixin 类、注入点或网络包，本体只读。

用户新增要求：人在回路画面不显示 RWR、雷达面板和超视距框。RVP_RadarOverlay 和 ECM 假 RWR 符号增加 HITL 绘制门；RVP_ScopeOverlay 在非观瞄分支前返回，同时保护直接目标补画入口，既有 shouldSkipVanillaAimLockTarget 对 HITL 返回 true，使本体第二套扫描/硬锁框也不再漏画。弹上专用准星及指定目标标记保留；退出 HITL 自然恢复。探测、锁定、声音和服务端状态不因隐藏 HUD 被关掉。

用户实测已确认此前“超视距天空局部闪白”是自身导弹新程序喷焰进入镜头，不能归因于区块加载，授权修复。RVP_RocketEngineLightQuery 复用 HITL 自身视觉门，停止登记且清除本地/远端当前弹的程序喷焰和发动机持续光。RVP_RocketPlumeManager 的真实喷焰记录 ownerEntityId，collect 每帧屏蔽当前弹，覆盖切入首帧/暂停/同 ID 不同 UUID 的克隆，不等两 tick TTL 到期；其它导弹、外部视角及命令预览保留。没有改 shader、照明参数或区块逻辑。

JDK21.0.11 offline build/jarFull 成功，1328 测试全部通过（新增 4 项，真实实体 ID/探测表污染清理、HITL 绘制入口、缓存喷焰切入切出及自告警提前拒绝）。runServer 冒烟以新 latest.log 的 `Done (2.719s)!` 为准，无新增 Mixin/类加载错误；启动与测试 JVM 身份逐条打印确认后精确收尾，无残留，25565 无监听。未启动用户客户端；UI 隐藏和喷焰白闪仍待用户实际飞行验证，勿把单元测试当实飞。

状态清单 `_tmp_rwr_hitl_20261103.json`，源码备份 `D:\ywzj\ywzj\ywzj_rvp\_backups\rwr_self_hitl_20261103_023541`，源 meta 备份 `D:\ywzj\ywzj\ywzj_rvp\limitless_vehicle\rvp\_bak\vehicle_pack.meta.json.bak_20261103_024034_rwr_hitl_release`。提交核验 show--stat 共 18 文件，3 个新 Java 类/测试均已跟踪，src 无遗留未跟踪文件。保留原有 Meshy/鲨鱼网页、文档及 7 份根武器 JSON WIP，不提交载具包资产。继续遵守用户最新规则：root 亲自做、不派 Luna、不动 authoritative、本体只读、SBM 单面保持、零真删除。BUK/IRIS-T `initial_speed:null` 部署解析问题仍未处理。

前一入口：《RVP会话交接_HITL侧栏与阻尼准星_20261103.md》。Gitee 与 GitHub master 均已核对为 `593ddc8183dd3d20115a931b2b157652b358d5ce`。
