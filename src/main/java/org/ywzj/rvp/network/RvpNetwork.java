package org.ywzj.rvp.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import org.ywzj.rvp.YwzjRvp;

public class RvpNetwork {
    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(ResourceLocation.fromNamespaceAndPath(YwzjRvp.MOD_ID, "main"))
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
        CHANNEL.messageBuilder(C2SSetAntiRadiationPreselect.class, id++)
                .encoder(C2SSetAntiRadiationPreselect::encode)
                .decoder(C2SSetAntiRadiationPreselect::decode)
                .consumerMainThread(C2SSetAntiRadiationPreselect::handle)
                .add();
        CHANNEL.messageBuilder(S2CSetTVMissile.class, id++)
                .encoder(S2CSetTVMissile::encode)
                .decoder(S2CSetTVMissile::decode)
                .consumerMainThread(S2CSetTVMissile::handle)
                .add();
        CHANNEL.messageBuilder(C2STVMissileControlInput.class, id++)
                .encoder(C2STVMissileControlInput::encode)
                .decoder(C2STVMissileControlInput::decode)
                .consumerMainThread(C2STVMissileControlInput::handle)
                .add();
        CHANNEL.messageBuilder(C2STVMissileExit.class, id++)
                .encoder(C2STVMissileExit::encode)
                .decoder(C2STVMissileExit::decode)
                .consumerMainThread(C2STVMissileExit::handle)
                .add();
    }
}
