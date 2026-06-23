package org.ywzj.rvp.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import org.ywzj.rvp.RVP_MOD;

public class RVP_Network {
    private static final String PROTOCOL = "1";

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
        CHANNEL.messageBuilder(S2CBulletVehicleHitDebug.class, id++)
                .encoder(S2CBulletVehicleHitDebug::encode)
                .decoder(S2CBulletVehicleHitDebug::decode)
                .consumerMainThread(S2CBulletVehicleHitDebug::handle)
                .add();
        CHANNEL.messageBuilder(S2CVehicleEraState.class, id++)
                .encoder(S2CVehicleEraState::encode)
                .decoder(S2CVehicleEraState::decode)
                .consumerMainThread(S2CVehicleEraState::handle)
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
    }
}
