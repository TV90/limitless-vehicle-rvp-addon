package org.ywzj.rvp;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.rvp.all.RVP_DisplayTypes;
import org.ywzj.rvp.all.RVP_Items;
import org.ywzj.rvp.all.RVP_Particles;
import org.ywzj.rvp.all.RVP_Sounds;
import org.ywzj.rvp.all.RVP_WeaponTypes;
import org.ywzj.rvp.config.RVP_ClientConfig;
import org.ywzj.rvp.config.RVP_CommonConfig;
import org.ywzj.rvp.config.RVP_Config;
import org.ywzj.rvp.config.UIPresetManager;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.resource.RVP_VehiclePackInstaller;
import org.ywzj.rvp.server.visual.RVP_NetworkVisualEventPublisher;
import org.ywzj.rvp.weapon.visual.RVP_VisualEffects;

@Mod(RVP_MOD.MOD_ID)
public class RVP_MOD {
    public static final String MOD_ID = "ywzj_rvp";

    public static ResourceLocation modLocation(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    public static ResourceLocation resourceLocation(String location) {
        return ResourceLocation.parse(location);
    }

    public RVP_MOD(FMLJavaModLoadingContext context) {
        RVP_Config.register(context);
        RVP_CommonConfig.register(context);
        RVP_ClientConfig.register(context);
        UIPresetManager.load();
        RVP_VehiclePackInstaller.ensureInstalled();
        IEventBus modBus = context.getModEventBus();
        RVP_Entities.register(modBus);
        RVP_DisplayTypes.register(modBus);
        RVP_Items.register(modBus);
        RVP_Particles.register(modBus);
        RVP_Sounds.register(modBus);
        RVP_WeaponTypes.register(modBus);
        modBus.addListener(this::onCommonSetup);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                {
                    modBus.addListener(org.ywzj.rvp.client.RVP_ClientBootstrap::onClientSetup);
                    modBus.addListener(org.ywzj.rvp.client.RVP_ClientBootstrap::onLoadComplete);
                    modBus.addListener(org.ywzj.rvp.client.RVP_ClientBootstrap::onRegisterParticleProviders);
                });
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            RVP_Network.init();
            // 安装 RVP 网络视觉发布端，使弹体业务只依赖公共发布接口，不直接读取网络通道。
            RVP_VisualEffects.installPublisher(new RVP_NetworkVisualEventPublisher());
        });
    }
}
