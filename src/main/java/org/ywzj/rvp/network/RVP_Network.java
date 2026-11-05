package org.ywzj.rvp.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.simple.SimpleChannel;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.countermeasure.network.C2SFireCountermeasure;
import org.ywzj.rvp.countermeasure.network.S2CCountermeasureHudSync;
import org.ywzj.rvp.maintenance.network.C2SSetRepairOrder;
import org.ywzj.rvp.maintenance.network.C2SUseMaintenance;
import org.ywzj.rvp.maintenance.network.S2CMaintenanceSync;
import org.ywzj.rvp.network.remotevisibility.S2CRemoteAmmoVisualSnapshot;
import org.ywzj.rvp.network.remotevisibility.S2CRemoteVehicleVisualSnapshot;
import org.ywzj.rvp.network.visual.S2CVisualEffectEvent;
import org.ywzj.rvp.network.firesupport.C2SRequestFireSupport;
import org.ywzj.rvp.network.firesupport.C2SRequestFireSupportCeaseFire;
import org.ywzj.rvp.network.firesupport.C2SSavePodFireSupportSelection;
import org.ywzj.rvp.network.firesupport.S2CPodFireSupportSelection;
import org.ywzj.rvp.network.firesupport.S2CFireSupportMissionUpdate;
import org.ywzj.rvp.network.firesupport.S2CFireSupportProfileSnapshot;
import org.ywzj.rvp.network.firesupport.S2CFireSupportRequestResult;
import org.ywzj.rvp.network.gunner.S2CGunnerProfileSnapshot;

public class RVP_Network {
    /** 协议 19：新增服务端 Gunner Profile ID 快照，用于数据驱动生成物品变体。 */
    
    /** 协议 20：新增 S2CBoneDamageProgress（全模块累计化配套：骨→累计伤害，面板虚拟血量）；旧客户端无法连新服。 */
    /** 协议 21：失效表新增 BARREL_DAMAGED（炮管受损档进失效表：可快修/队列/持久化）；旧客户端无法连新服。 */
    /** 协议 25：视觉碎片事件新增方向圆锥半角参数；旧客户端无法连新服。 */
    /** 协议 26：新增 C2SHeliDockToggle（直升机着舰 P 键）+ S2CHeliDockState（着舰/接管控制锁定同步）；旧客户端无法连新服。 */
    /** 协议 27：新增地面载具击毁分支及权威殉燃时间表；双端必须一同更新。 */
    /** 协议 28：新增载具表面命中火花消息；双端必须一同更新。 */
    /** 协议 29：命中火星消息增加入射方向，用于按装甲面采样飞溅；双端必须一同更新。 */
    /** 协议 30：通用视觉事件增加可选的八方向冲击波形状；双端必须一同更新。 */
    /** 协议 31：新增 S2CDamageNumber（生物头顶伤害飘字）；旧客户端无法连新服。 */
    /** 协议 32：新增小口径划空与方块命中声音消息；客户端和服务端须同时更新。 */
    /** 协议 33：新增不依赖实体追踪的导弹与殉燃声音消息；客户端和服务端须同时更新。 */
    /** 协议 34：殉燃弹药爆响改为客户端本地跟随播放（移除 AMMO_POPS 与截止时刻）；发射声改为客户端本地优先，只向未追踪载具的玩家补发；双端必须一同更新。 */
    /** 协议 35：新增母车辅助载具候选选择请求和权威状态同步。 */
    /** 协议 36：显式配置的 HITL 远程观察窗口、区块、心跳及导弹 spawn 标志。 */
    /** 协议 42：两侧分支分别新增虚拟中段雷达目标/实体交接，以及方向机两档回转限制。 */
    /** 协议 44：HITL 链路包增加权威锁点、OBB 身份及 DIRCM 禁锁剩余时间。 */
    /** 协议 45：新增每玩家客户端视距上报，导弹与载具近远交接随各自客户端设置动态变化。 */
    /** 协议 46：合并虚拟弹/动态视距消息与权威电视锁定状态，双端须一同更新。 */
    /** 协议 47：视距上报附带客户端扩展请求，载具快照下发服务端实际视距与许可。 */
    /** 协议 48：新版吊舱绝对请求、实际轨迹与预测确认；双端须一同更新。 */
    private static final String PROTOCOL = "48";

    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(ResourceLocation.fromNamespaceAndPath(RVP_MOD.MOD_ID, "main"))
            .networkProtocolVersion(() -> PROTOCOL)
            .clientAcceptedVersions(PROTOCOL::equals)
            .serverAcceptedVersions(PROTOCOL::equals)
            .simpleChannel();
    private static boolean initialized;

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        int id = 0;
        CHANNEL.messageBuilder(C2SOpticalSightCommand.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SOpticalSightCommand::encode).decoder(C2SOpticalSightCommand::decode)
                .consumerMainThread(C2SOpticalSightCommand::handle).add();
        CHANNEL.messageBuilder(S2COpticalSightState.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2COpticalSightState::encode).decoder(S2COpticalSightState::decode)
                .consumerMainThread(S2COpticalSightState::handle).add();
        // 服务端权威的 HITL 观察会话；消息处理均在主线程并明确传输方向。
        CHANNEL.messageBuilder(org.ywzj.rvp.network.hitl.S2CHitlRemoteView.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(org.ywzj.rvp.network.hitl.S2CHitlRemoteView::encode)
                .decoder(org.ywzj.rvp.network.hitl.S2CHitlRemoteView::decode)
                .consumerMainThread(org.ywzj.rvp.network.hitl.S2CHitlRemoteView::handle).add();
        CHANNEL.messageBuilder(org.ywzj.rvp.network.hitl.S2CHitlRemoteChunk.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(org.ywzj.rvp.network.hitl.S2CHitlRemoteChunk::encode)
                .decoder(org.ywzj.rvp.network.hitl.S2CHitlRemoteChunk::decode)
                .consumerMainThread(org.ywzj.rvp.network.hitl.S2CHitlRemoteChunk::handle).add();
        CHANNEL.messageBuilder(org.ywzj.rvp.network.hitl.C2SHitlRemoteHeartbeat.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(org.ywzj.rvp.network.hitl.C2SHitlRemoteHeartbeat::encode)
                .decoder(org.ywzj.rvp.network.hitl.C2SHitlRemoteHeartbeat::decode)
                .consumerMainThread(org.ywzj.rvp.network.hitl.C2SHitlRemoteHeartbeat::handle).add();
        CHANNEL.messageBuilder(C2SSetGPSTarget.class, id++)
                .encoder(C2SSetGPSTarget::encode)
                .decoder(C2SSetGPSTarget::decode)
                .consumerMainThread(C2SSetGPSTarget::handle)
                .add();
        CHANNEL.messageBuilder(S2CGpsStateSync.class, id++)
                .encoder(S2CGpsStateSync::encode)
                .decoder(S2CGpsStateSync::decode)
                .consumerMainThread(S2CGpsStateSync::handle)
                .add();
        CHANNEL.messageBuilder(S2CEnterHitlView.class, id++)
                .encoder(S2CEnterHitlView::encode)
                .decoder(S2CEnterHitlView::decode)
                .consumerMainThread(S2CEnterHitlView::handle)
                .add();
        CHANNEL.messageBuilder(C2SHitlSteeringInput.class, id++)
                .encoder(C2SHitlSteeringInput::encode)
                .decoder(C2SHitlSteeringInput::decode)
                .consumerMainThread(C2SHitlSteeringInput::handle)
                .add();
        CHANNEL.messageBuilder(C2SHitlDesignate.class, id++)
                .encoder(C2SHitlDesignate::encode)
                .decoder(C2SHitlDesignate::decode)
                .consumerMainThread(C2SHitlDesignate::handle)
                .add();
        CHANNEL.messageBuilder(C2SExitHitlView.class, id++)
                .encoder(C2SExitHitlView::encode)
                .decoder(C2SExitHitlView::decode)
                .consumerMainThread(C2SExitHitlView::handle)
                .add();
        CHANNEL.messageBuilder(C2SHitlDetonate.class, id++)
                .encoder(C2SHitlDetonate::encode)
                .decoder(C2SHitlDetonate::decode)
                .consumerMainThread(C2SHitlDetonate::handle)
                .add();
        CHANNEL.messageBuilder(S2CHitlLinkState.class, id++)
                .encoder(S2CHitlLinkState::encode)
                .decoder(S2CHitlLinkState::decode)
                .consumerMainThread(S2CHitlLinkState::handle)
                .add();
        CHANNEL.messageBuilder(C2SSetAirburstRange.class, id++)
                .encoder(C2SSetAirburstRange::encode)
                .decoder(C2SSetAirburstRange::decode)
                .consumerMainThread(C2SSetAirburstRange::handle)
                .add();
        CHANNEL.messageBuilder(C2SSelectModdingSubWeapon.class, id++)
                .encoder(C2SSelectModdingSubWeapon::encode)
                .decoder(C2SSelectModdingSubWeapon::decode)
                .consumerMainThread(C2SSelectModdingSubWeapon::handle)
                .add();
        // 辅助载具状态请求：验证母车实体 UUID 后返回服务端选项、冷却和部署锁。
        CHANNEL.messageBuilder(C2SRequestDeployableUavState.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SRequestDeployableUavState::encode)
                .decoder(C2SRequestDeployableUavState::decode)
                .consumerMainThread(C2SRequestDeployableUavState::handle)
                .add();
        // 辅助载具候选选择：服务端校验工具、停车门、冷却、租约与候选 ID。
        CHANNEL.messageBuilder(C2SSelectDeployableUavOption.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SSelectDeployableUavOption::encode)
                .decoder(C2SSelectDeployableUavOption::decode)
                .consumerMainThread(C2SSelectDeployableUavOption::handle)
                .add();
        CHANNEL.messageBuilder(C2SDeployDeployableUav.class, id++)
                .encoder(C2SDeployDeployableUav::encode)
                .decoder(C2SDeployDeployableUav::decode)
                .consumerMainThread(C2SDeployDeployableUav::handle)
                .add();
        CHANNEL.messageBuilder(C2SSwitchDeployableUav.class, id++)
                .encoder(C2SSwitchDeployableUav::encode)
                .decoder(C2SSwitchDeployableUav::decode)
                .consumerMainThread(C2SSwitchDeployableUav::handle)
                .add();
        CHANNEL.messageBuilder(C2SRequestExternalRadarLock.class, id++)
                .encoder(C2SRequestExternalRadarLock::encode)
                .decoder(C2SRequestExternalRadarLock::decode)
                .consumerMainThread(C2SRequestExternalRadarLock::handle)
                .add();
        CHANNEL.messageBuilder(C2SIrSeekerArmed.class, id++)
                .encoder(C2SIrSeekerArmed::encode)
                .decoder(C2SIrSeekerArmed::decode)
                .consumerMainThread(C2SIrSeekerArmed::handle)
                .add();
        CHANNEL.messageBuilder(C2SClearExternalRadarLock.class, id++)
                .encoder(C2SClearExternalRadarLock::encode)
                .decoder(C2SClearExternalRadarLock::decode)
                .consumerMainThread(C2SClearExternalRadarLock::handle)
                .add();
        CHANNEL.messageBuilder(S2CBulletVehicleHitDebug.class, id++)
                .encoder(S2CBulletVehicleHitDebug::encode)
                .decoder(S2CBulletVehicleHitDebug::decode)
                .consumerMainThread(S2CBulletVehicleHitDebug::handle)
                .add();
        CHANNEL.messageBuilder(S2CBoneModuleState.class, id++)
                .encoder(S2CBoneModuleState::encode)
                .decoder(S2CBoneModuleState::decode)
                .consumerMainThread(S2CBoneModuleState::handle)
                .add();
        CHANNEL.messageBuilder(C2SUseMaintenance.class, id++)
                .encoder(C2SUseMaintenance::encode)
                .decoder(C2SUseMaintenance::decode)
                .consumerMainThread(C2SUseMaintenance::handle)
                .add();
        CHANNEL.messageBuilder(S2CMaintenanceSync.class, id++)
                .encoder(S2CMaintenanceSync::encode)
                .decoder(S2CMaintenanceSync::decode)
                .consumerMainThread(S2CMaintenanceSync::handle)
                .add();
        CHANNEL.messageBuilder(C2SSaclosDesignation.class, id++)
                .encoder(C2SSaclosDesignation::encode)
                .decoder(C2SSaclosDesignation::decode)
                .consumerMainThread(C2SSaclosDesignation::handle)
                .add();
        CHANNEL.messageBuilder(C2SSetArmPreselect.class, id++)
                .encoder(C2SSetArmPreselect::encode)
                .decoder(C2SSetArmPreselect::decode)
                .consumerMainThread(C2SSetArmPreselect::handle)
                .add();
        CHANNEL.messageBuilder(S2CApsHudSync.class, id++)
                .encoder(S2CApsHudSync::encode)
                .decoder(S2CApsHudSync::decode)
                .consumerMainThread(S2CApsHudSync::handle)
                .add();
        CHANNEL.messageBuilder(S2CApsFlameLink.class, id++)
                .encoder(S2CApsFlameLink::encode)
                .decoder(S2CApsFlameLink::decode)
                .consumerMainThread(S2CApsFlameLink::handle)
                .add();
        CHANNEL.messageBuilder(S2CDircmHudSync.class, id++)
                .encoder(S2CDircmHudSync::encode)
                .decoder(S2CDircmHudSync::decode)
                .consumerMainThread(S2CDircmHudSync::handle)
                .add();
        CHANNEL.messageBuilder(S2CEcmHudSync.class, id++)
                .encoder(S2CEcmHudSync::encode)
                .decoder(S2CEcmHudSync::decode)
                .consumerMainThread(S2CEcmHudSync::handle)
                .add();
        CHANNEL.messageBuilder(S2CRemoteAmmoSnapshot.class, id++)
                .encoder(S2CRemoteAmmoSnapshot::encode)
                .decoder(S2CRemoteAmmoSnapshot::decode)
                .consumerMainThread(S2CRemoteAmmoSnapshot::handle)
                .add();
        CHANNEL.messageBuilder(S2CHbmMissileSnapshot.class, id++)
                .encoder(S2CHbmMissileSnapshot::encode)
                .decoder(S2CHbmMissileSnapshot::decode)
                .consumerMainThread(S2CHbmMissileSnapshot::handle)
                .add();
        CHANNEL.messageBuilder(S2CExternalRadarSnapshot.class, id++)
                .encoder(S2CExternalRadarSnapshot::encode)
                .decoder(S2CExternalRadarSnapshot::decode)
                .consumerMainThread(S2CExternalRadarSnapshot::handle)
                .add();
        CHANNEL.messageBuilder(S2CTacticalRevealSnapshot.class, id++)
                .encoder(S2CTacticalRevealSnapshot::encode)
                .decoder(S2CTacticalRevealSnapshot::decode)
                .consumerMainThread(S2CTacticalRevealSnapshot::handle)
                .add();
        CHANNEL.messageBuilder(S2CRemoteAmmoVisualSnapshot.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CRemoteAmmoVisualSnapshot::encode)
                .decoder(S2CRemoteAmmoVisualSnapshot::decode)
                .consumerMainThread(S2CRemoteAmmoVisualSnapshot::handle)
                .add();
        CHANNEL.messageBuilder(S2CRemoteVehicleVisualSnapshot.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CRemoteVehicleVisualSnapshot::encode)
                .decoder(S2CRemoteVehicleVisualSnapshot::decode)
                .consumerMainThread(S2CRemoteVehicleVisualSnapshot::handle)
                .add();
        CHANNEL.messageBuilder(S2CNuclearVisualEffect.class, id++)
                .encoder(S2CNuclearVisualEffect::encode)
                .decoder(S2CNuclearVisualEffect::decode)
                .consumerMainThread(S2CNuclearVisualEffect::handle)
                .add();
        CHANNEL.messageBuilder(S2CMarkedBlockSync.class, id++)
                .encoder(S2CMarkedBlockSync::encode)
                .decoder(S2CMarkedBlockSync::decode)
                .consumerMainThread(S2CMarkedBlockSync::handle)
                .add();
        CHANNEL.messageBuilder(C2SToggleUavLoiter.class, id++)
                .encoder(C2SToggleUavLoiter::encode)
                .decoder(C2SToggleUavLoiter::decode)
                .consumerMainThread(C2SToggleUavLoiter::handle)
                .add();
        CHANNEL.messageBuilder(C2SSetLoiterCenter.class, id++)
                .encoder(C2SSetLoiterCenter::encode)
                .decoder(C2SSetLoiterCenter::decode)
                .consumerMainThread(C2SSetLoiterCenter::handle)
                .add();
        CHANNEL.messageBuilder(S2CLoiterStateSync.class, id++)
                .encoder(S2CLoiterStateSync::encode)
                .decoder(S2CLoiterStateSync::decode)
                .consumerMainThread(S2CLoiterStateSync::handle)
                .add();
        CHANNEL.messageBuilder(C2SHeliDockToggle.class, id++)
                .encoder(C2SHeliDockToggle::encode)
                .decoder(C2SHeliDockToggle::decode)
                .consumerMainThread(C2SHeliDockToggle::handle)
                .add();
        CHANNEL.messageBuilder(S2CHeliDockState.class, id++)
                .encoder(S2CHeliDockState::encode)
                .decoder(S2CHeliDockState::decode)
                .consumerMainThread(S2CHeliDockState::handle)
                .add();
        CHANNEL.messageBuilder(S2CGunnerVehicleSync.class, id++)
                .encoder(S2CGunnerVehicleSync::encode)
                .decoder(S2CGunnerVehicleSync::decode)
                .consumerMainThread(S2CGunnerVehicleSync::handle)
                .add();
        CHANNEL.messageBuilder(C2SDebugSpawnVehicle.class, id++)
                .encoder(C2SDebugSpawnVehicle::encode)
                .decoder(C2SDebugSpawnVehicle::decode)
                .consumerMainThread(C2SDebugSpawnVehicle::handle)
                .add();
        CHANNEL.messageBuilder(S2CVehicleRvpConfig.class, id++)
                .encoder(S2CVehicleRvpConfig::encode)
                .decoder(S2CVehicleRvpConfig::decode)
                .consumerMainThread(S2CVehicleRvpConfig::handle)
                .add();
        CHANNEL.messageBuilder(C2SRadarPowerToggle.class, id++)
                .encoder(C2SRadarPowerToggle::encode)
                .decoder(C2SRadarPowerToggle::decode)
                .consumerMainThread(C2SRadarPowerToggle::handle)
                .add();
        CHANNEL.messageBuilder(S2CRvpHitIndicator.class, id++)
                .encoder(S2CRvpHitIndicator::encode)
                .decoder(S2CRvpHitIndicator::decode)
                .consumerMainThread(S2CRvpHitIndicator::handle)
                .add();
        CHANNEL.messageBuilder(S2CVisualEffectEvent.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CVisualEffectEvent::encode)
                .decoder(S2CVisualEffectEvent::decode)
                .consumerMainThread(S2CVisualEffectEvent::handle)
                .add();
        CHANNEL.messageBuilder(S2CMissileAirTargetImpactFragments.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CMissileAirTargetImpactFragments::encode)
                .decoder(S2CMissileAirTargetImpactFragments::decode)
                .consumerMainThread(S2CMissileAirTargetImpactFragments::handle)
                .add();
        CHANNEL.messageBuilder(C2SFireCountermeasure.class, id++)
                .encoder(C2SFireCountermeasure::encode)
                .decoder(C2SFireCountermeasure::decode)
                .consumerMainThread(C2SFireCountermeasure::handle)
                .add();
        CHANNEL.messageBuilder(C2SFireEcm.class, id++)
                .encoder(C2SFireEcm::encode)
                .decoder(C2SFireEcm::decode)
                .consumerMainThread(C2SFireEcm::handle)
                .add();
        CHANNEL.messageBuilder(S2CEcmActiveHudSync.class, id++)
                .encoder(S2CEcmActiveHudSync::encode)
                .decoder(S2CEcmActiveHudSync::decode)
                .consumerMainThread(S2CEcmActiveHudSync::handle)
                .add();
        CHANNEL.messageBuilder(S2CEcmFakeLock.class, id++)
                .encoder(S2CEcmFakeLock::encode)
                .decoder(S2CEcmFakeLock::decode)
                .consumerMainThread(S2CEcmFakeLock::handle)
                .add();
        CHANNEL.messageBuilder(S2CCountermeasureHudSync.class, id++)
                .encoder(S2CCountermeasureHudSync::encode)
                .decoder(S2CCountermeasureHudSync::decode)
                .consumerMainThread(S2CCountermeasureHudSync::handle)
                .add();
        CHANNEL.messageBuilder(S2CMissileTrackAlert.class, id++)
                .encoder(S2CMissileTrackAlert::encode)
                .decoder(S2CMissileTrackAlert::decode)
                .consumerMainThread(S2CMissileTrackAlert::handle)
                .add();
        CHANNEL.messageBuilder(S2CRvpWarn.class, id++)
                .encoder(S2CRvpWarn::encode)
                .decoder(S2CRvpWarn::decode)
                .consumerMainThread(S2CRvpWarn::handle)
                .add();
        CHANNEL.messageBuilder(S2CEcmDebug.class, id++)
                .encoder(S2CEcmDebug::encode)
                .decoder(S2CEcmDebug::decode)
                .consumerMainThread(S2CEcmDebug::handle)
                .add();
        // [RVP] 可变后掠翼手动切换（C2S）：状态权威在服务端 RVP_WingSweepState
        CHANNEL.messageBuilder(C2SWingSweepToggle.class, id++)
                .encoder(C2SWingSweepToggle::encode)
                .decoder(C2SWingSweepToggle::decode)
                .consumerMainThread(C2SWingSweepToggle::handle)
                .add();
        CHANNEL.messageBuilder(S2CFireSupportProfileSnapshot.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CFireSupportProfileSnapshot::encode)
                .decoder(S2CFireSupportProfileSnapshot::decode)
                .consumerMainThread(S2CFireSupportProfileSnapshot::handle)
                .add();
        CHANNEL.messageBuilder(C2SRequestFireSupport.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SRequestFireSupport::encode)
                .decoder(C2SRequestFireSupport::decode)
                .consumerMainThread(C2SRequestFireSupport::handle)
                .add();
        CHANNEL.messageBuilder(S2CFireSupportRequestResult.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CFireSupportRequestResult::encode)
                .decoder(S2CFireSupportRequestResult::decode)
                .consumerMainThread(S2CFireSupportRequestResult::handle)
                .add();
        CHANNEL.messageBuilder(S2CFireSupportMissionUpdate.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CFireSupportMissionUpdate::encode)
                .decoder(S2CFireSupportMissionUpdate::decode)
                .consumerMainThread(S2CFireSupportMissionUpdate::handle)
                .add();
        CHANNEL.messageBuilder(C2SRequestFireSupportCeaseFire.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SRequestFireSupportCeaseFire::encode)
                .decoder(C2SRequestFireSupportCeaseFire::decode)
                .consumerMainThread(C2SRequestFireSupportCeaseFire::handle)
                .add();
        // [RVP] 吊舱火力支援选择保存（C2S，2026-10-13 整合批）：战术地图吊舱工具上行
        CHANNEL.messageBuilder(C2SSavePodFireSupportSelection.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SSavePodFireSupportSelection::encode)
                .decoder(C2SSavePodFireSupportSelection::decode)
                .consumerMainThread(C2SSavePodFireSupportSelection::handle)
                .add();
        // [RVP] 吊舱火力支援选择同步（S2C，2026-10-13 整合批）：保存 ACK + 登录/reload 下发
        CHANNEL.messageBuilder(S2CPodFireSupportSelection.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CPodFireSupportSelection::encode)
                .decoder(S2CPodFireSupportSelection::decode)
                .consumerMainThread(S2CPodFireSupportSelection::handle)
                .add();
        // [RVP] 激光致盲（S2C）：客户端白色闪光滤镜
        CHANNEL.messageBuilder(S2CLaserBlind.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CLaserBlind::encode)
                .decoder(S2CLaserBlind::decode)
                .consumerMainThread(S2CLaserBlind::handle)
                .add();
        // [RVP] 观瞄视角状态上行（C2S）：进入/退出观瞄 + 心跳，出弹覆盖判定依据
        CHANNEL.messageBuilder(C2SScopeViewSync.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SScopeViewSync::encode)
                .decoder(C2SScopeViewSync::decode)
                .consumerMainThread(C2SScopeViewSync::handle)
                .add();
        // [RVP] 维修顺序上行（C2S，协议 16）：辅助设备面板编辑"爆反/辅助设备维修顺序"后即时同步，
        //       服务端按载具记入 RVP_RepairOrderTable；触发维修仍走快修工具既有链路
        CHANNEL.messageBuilder(C2SSetRepairOrder.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SSetRepairOrder::encode)
                .decoder(C2SSetRepairOrder::decode)
                .consumerMainThread(C2SSetRepairOrder::handle)
                .add();
        // [RVP] 引擎部件档位同步（S2C，协议 17）：受损/瘫痪档差分推送，辅助设备面板 ENGINE 栏目消费；
        //       瘫痪档另经 S2CBoneModuleState 失效广播（面板红框 / dev 维修队列）
        CHANNEL.messageBuilder(S2CEngineDamageState.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CEngineDamageState::encode)
                .decoder(S2CEngineDamageState::decode)
                .consumerMainThread(S2CEngineDamageState::handle)
                .add();
        // [RVP] 骨骼模块累计伤害进度（S2C，协议 20，全模块累计化配套）：差分推送骨→累计伤害，
        //       辅助设备面板显示各模块"虚拟血量"（阈值 − 已累计）
        CHANNEL.messageBuilder(S2CBoneDamageProgress.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CBoneDamageProgress::encode)
                .decoder(S2CBoneDamageProgress::decode)
                .consumerMainThread(S2CBoneDamageProgress::handle)
                .add();
        // [RVP] 部件战果通知（S2C，协议 17）：摧毁部件/重创发动机推送给射手本人，
        //       命中展板下方显示 60 tick 文案（"摧毁光电干扰机"/"重创发动机"等）
        CHANNEL.messageBuilder(S2CModuleHitNotify.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CModuleHitNotify::encode)
                .decoder(S2CModuleHitNotify::decode)
                .consumerMainThread(S2CModuleHitNotify::handle)
                .add();
        // [RVP] 雷达开关状态同步（S2C，协议 17）：服务端强制关闭/快修自动开机的结果
        //       推送到客户端镜像实体（本体 RadarUnit.on 不参与同步，客户端雷达页/HMD 读本地 isOn）
        CHANNEL.messageBuilder(S2CRadarPowerSync.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CRadarPowerSync::encode)
                .decoder(S2CRadarPowerSync::decode)
                .consumerMainThread(S2CRadarPowerSync::handle)
                .add();
        CHANNEL.messageBuilder(S2CGunnerProfileSnapshot.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CGunnerProfileSnapshot::encode)
                .decoder(S2CGunnerProfileSnapshot::decode)
                .consumerMainThread(S2CGunnerProfileSnapshot::handle)
                .add();
        // 调用本项目击毁快照编码/解码及安全桥处理器，只允许服务端向客户端发送。
        CHANNEL.messageBuilder(S2CWreckDestructionState.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CWreckDestructionState::encode)
                .decoder(S2CWreckDestructionState::decode)
                .consumerMainThread(S2CWreckDestructionState::handle)
                .add();
        // 注册服务端权威命中火花消息，客户端经安全桥创建纯视觉粒子。
        CHANNEL.messageBuilder(S2CVehicleImpactSparks.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CVehicleImpactSparks::encode)
                .decoder(S2CVehicleImpactSparks::decode)
                .consumerMainThread(S2CVehicleImpactSparks::handle)
                .add();
        // 生物头顶伤害飘字：服务端结算出实际掉血后单发推送，客户端纯视觉渲染。
        CHANNEL.messageBuilder(S2CDamageNumber.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CDamageNumber::encode)
                .decoder(S2CDamageNumber::decode)
                .consumerMainThread(S2CDamageNumber::handle)
                .add();
        // 注册服务端确认的小口径弹体声音消息，客户端经安全桥播放已有音频。
        CHANNEL.messageBuilder(S2CSmallCaliberSound.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CSmallCaliberSound::encode)
                .decoder(S2CSmallCaliberSound::decode)
                .consumerMainThread(S2CSmallCaliberSound::handle)
                .add();
        // 注册独立载具声音消息，服务端仅发布位置与时间，客户端经安全桥播放已有音频。
        CHANNEL.messageBuilder(S2CVehicleSound.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CVehicleSound::encode)
                .decoder(S2CVehicleSound::decode)
                .consumerMainThread(S2CVehicleSound::handle)
                .add();
        // 辅助载具状态同步：S2C 单向推送已校验的服务端权威快照。
        CHANNEL.messageBuilder(S2CDeployableUavState.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CDeployableUavState::encode)
                .decoder(S2CDeployableUavState::decode)
                .consumerMainThread(S2CDeployableUavState::handle)
                .add();
        // 当前乘员的辅助载具 HUD 身份；追加注册，保持既有消息 ID 顺序。
        CHANNEL.messageBuilder(S2CAuxiliaryHudContext.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CAuxiliaryHudContext::encode)
                .decoder(S2CAuxiliaryHudContext::decode)
                .consumerMainThread(S2CAuxiliaryHudContext::handle)
                .add();
        // 追加虚拟雷达目标消息，保持原有消息 ID 顺序，经安全桥分端消费。
        CHANNEL.messageBuilder(S2CVirtualMissileTargets.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CVirtualMissileTargets::encode)
                .decoder(S2CVirtualMissileTargets::decode)
                .consumerMainThread(S2CVirtualMissileTargets::handle)
                .add();

        // 虚拟弹独立视觉完整集合；公共端口保证专服注册时不加载客户端类。
        CHANNEL.messageBuilder(org.ywzj.rvp.network.remotevisibility.S2CVirtualMissileVisualSnapshot.class,
                        id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(org.ywzj.rvp.network.remotevisibility.S2CVirtualMissileVisualSnapshot::encode)
                .decoder(org.ywzj.rvp.network.remotevisibility.S2CVirtualMissileVisualSnapshot::decode)
                .consumerMainThread(org.ywzj.rvp.network.remotevisibility.S2CVirtualMissileVisualSnapshot::handle)
                .add();
        // 追加客户端视距消息，不改动既有消息编号；只允许客户端向服务器上报。
        CHANNEL.messageBuilder(org.ywzj.rvp.network.remotevisibility.C2SRemoteViewDistance.class,
                        id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(org.ywzj.rvp.network.remotevisibility.C2SRemoteViewDistance::encode)
                .decoder(org.ywzj.rvp.network.remotevisibility.C2SRemoteViewDistance::decode)
                .consumerMainThread(org.ywzj.rvp.network.remotevisibility.C2SRemoteViewDistance::handle)
                .add();
    }
}
