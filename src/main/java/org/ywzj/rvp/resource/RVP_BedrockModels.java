package org.ywzj.rvp.resource;

import com.github.mcmodderanchor.simplebedrockmodel.v1.event.RegisterBedrockModelEvent;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.vehicle.resource.BedrockModelLoader;

/**
 * Built-in RVP projectile bedrock models (shipped in the vehicle pack under namespace {@code rvp}).
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class RVP_BedrockModels {

    public static final ResourceLocation SABOT_MODEL = rvp("entity/rvp_sabot");
    public static final ResourceLocation SABOT_TEXTURE = rvp("textures/entity/rvp_sabot.png");
    public static final ResourceLocation SHOTGUN_PELLET_MODEL = rvp("entity/rvp_shotgun_pellet");
    public static final ResourceLocation SHOTGUN_PELLET_TEXTURE = rvp("textures/entity/rvp_shotgun_pellet.png");

    private RVP_BedrockModels() {}

    public static ResourceLocation rvp(String path) {
        return ResourceLocation.fromNamespaceAndPath("rvp", path);
    }

    @SubscribeEvent
    public static void onRegisterBedrockModels(RegisterBedrockModelEvent event) {
        event.register(SABOT_MODEL, BedrockModelLoader.COMMON_LOADER);
        event.register(SHOTGUN_PELLET_MODEL, BedrockModelLoader.COMMON_LOADER);
    }
}
