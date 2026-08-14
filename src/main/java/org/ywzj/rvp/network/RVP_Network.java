package org.ywzj.rvp.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.simple.SimpleChannel;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.countermeasure.network.C2SFireCountermeasure;
import org.ywzj.rvp.countermeasure.network.S2CCountermeasureHudSync;
import org.ywzj.rvp.network.visual.S2CVisualEffectEvent;

public class RVP_Network {
    private static final String PROTOCOL = "2";

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
        CHANNEL.messageBuilder(S2CExtendedAirVisualSnapshot.class, id++)
                .encoder(S2CExtendedAirVisualSnapshot::encode)
                .decoder(S2CExtendedAirVisualSnapshot::decode)
                .consumerMainThread(S2CExtendedAirVisualSnapshot::handle)
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
        CHANNEL.messageBuilder(C2SFireCountermeasure.class, id++)
                .encoder(C2SFireCountermeasure::encode)
                .decoder(C2SFireCountermeasure::decode)
                .consumerMainThread(C2SFireCountermeasure::handle)
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
    }
}
