package org.ywzj.rvp.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.client.event.RegisterParticleProvidersEvent;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.rvp.all.RVP_Particles;
import org.ywzj.rvp.client.particle.RVP_MchrSmokeParticle;
import org.ywzj.rvp.client.particle.RVP_TracerGhostParticle;
import org.ywzj.rvp.client.particle.RVP_ImpactSparkParticle;
import org.ywzj.rvp.client.particle.RVP_WhitePhosphorusParticle;
import org.ywzj.rvp.client.debug.RVP_SbmProbeDebug;
import org.ywzj.rvp.client.firesupport.RVP_ClientFireSupportState;
import org.ywzj.rvp.client.gunner.RVP_ClientGunnerProfileState;
import org.ywzj.rvp.client.compat.distanthorizons.RVP_DistantHorizonsCompatBootstrap;
import org.ywzj.rvp.client.nuclear.RVP_ExplosionVisualManager;
import org.ywzj.rvp.client.nuclear.RVP_NuclearVisualManager;
import org.ywzj.rvp.client.render.GunnerRenderer;
import org.ywzj.rvp.client.render.RVP_VehiclePartRender;
import org.ywzj.rvp.client.render.RVP_VehicleRender;
import org.ywzj.rvp.client.state.remotevisibility.RVP_ClientRemoteAmmoVisualState;
import org.ywzj.rvp.client.state.remotevisibility.RVP_ClientRemoteVehicleVisualState;
import org.ywzj.rvp.client.visual.RVP_ClientVisualEffectDispatcher;
import org.ywzj.rvp.client.visual.RVP_DefaultExplosionEffectFactory;
import org.ywzj.rvp.client.visual.RVP_WilsonMachEffectFactory;
import org.ywzj.rvp.client.visual.RVP_MissileAirTargetImpactFragmentEffects;
import org.ywzj.rvp.client.visual.thermobaric.RVP_ThermobaricEffectFactory;
import org.ywzj.rvp.network.RVP_MissileAirTargetImpactFragmentEndpoint;
import org.ywzj.rvp.network.RVP_NuclearVisualEndpoint;
import org.ywzj.rvp.network.firesupport.RVP_FireSupportClientEndpoint;
import org.ywzj.rvp.network.gunner.RVP_GunnerProfileClientEndpoint;
import org.ywzj.rvp.network.remotevisibility.RVP_RemoteAmmoVisualEndpoint;
import org.ywzj.rvp.network.remotevisibility.RVP_RemoteVehicleVisualEndpoint;
import org.ywzj.rvp.weapon.visual.api.RVP_VisualEffectEndpoint;
import org.ywzj.vehicle.all.AllEntities;

/**
 * Client-only setup; keep out of {@link org.ywzj.rvp.RVP_MOD} so dedicated servers do not load renderer classes.
 */
public final class RVP_ClientBootstrap {

    private RVP_ClientBootstrap() {}

    /** 沿用已有客户端 Shader 注册链，专服不会链接灯光渲染类型。 */
    public static void onRegisterLightShaders(net.minecraftforge.client.event.RegisterShadersEvent event) {
        // 调用独立灯光程序注册；加载失败时该层自行降级，不阻断已有特效。
        org.ywzj.rvp.client.light.RVP_ScreenLightPass.registerShaders(event);
        // 夜视仪干扰机光斑：自绘 quad 程序，不依赖 PostChain，光影下同样可用。
        org.ywzj.rvp.client.shader.RVP_NvgGlowPass.registerShaders(event);
    }

    public static void onClientSetup(FMLClientSetupEvent event) {
        // 调用通用调参工具的 Mods 配置入口注册；物理服务端不会加载此客户端类。
        org.ywzj.rvp.client.tuning.RVP_TuningClient.registerConfigScreen();
        event.enqueueWork(() -> {
            // 调用扩展注册与本地预设加载；具体分类通过事件自注册，不在启动器中列举。
            org.ywzj.rvp.client.tuning.RVP_TuningClient.initialize();
            // 客户端初始化时安装通用视觉消费端，避免公共网络消息直接加载客户端渲染类。
            RVP_VisualEffectEndpoint.install(RVP_ClientVisualEffectDispatcher::accept);
            // 安装导弹空中目标命中碎片消费端，把 S2C 快照转换为本地纯视觉粒子。
            RVP_MissileAirTargetImpactFragmentEndpoint.install(
                    RVP_MissileAirTargetImpactFragmentEffects::accept);
            // 调用 RVP 客户端视觉注册表，为通用事件协议注册温压效果工厂。
            RVP_ClientVisualEffectDispatcher.register(RVP_ThermobaricEffectFactory.EFFECT_TYPE,
                    new RVP_ThermobaricEffectFactory());
            // 调用 RVP 客户端视觉注册表，为通用事件协议注册 RVP 内置默认爆炸（MCHR 风格）工厂。
            RVP_ClientVisualEffectDispatcher.register(RVP_DefaultExplosionEffectFactory.EFFECT_TYPE,
                    new RVP_DefaultExplosionEffectFactory());
            // 注册由数据选择的威尔逊云/马赫杆工厂，复用公共视觉网络事件。
            RVP_ClientVisualEffectDispatcher.register(RVP_WilsonMachEffectFactory.EFFECT_TYPE,
                    new RVP_WilsonMachEffectFactory());
            // 客户端初始化时安装既有核爆视觉消费端，保持旧 HBM 视觉行为并隔离物理侧。
            RVP_NuclearVisualEndpoint.install(message -> {
                if ("nuclear".equalsIgnoreCase(message.preset()) || "nuke".equalsIgnoreCase(message.preset())) {
                    RVP_NuclearVisualManager.spawn(message);
                } else {
                    RVP_ExplosionVisualManager.spawn(message);
                }
            });
            // 安装 RVP 弹药视觉公共消费端，把完整集合写入客户端弹药视觉状态表。
            RVP_RemoteAmmoVisualEndpoint.install(message -> RVP_ClientRemoteAmmoVisualState.replace(
                    message.dimension(), message.entityIds(), message.motorBurnRemainingTicksByEntityId(),
                    Minecraft.getInstance().level == null
                            ? Long.MIN_VALUE : Minecraft.getInstance().level.getGameTime()));
            // 安装 RVP 载具视觉公共消费端，把完整集合交给非世界代理状态管理器。
            RVP_RemoteVehicleVisualEndpoint.install(RVP_ClientRemoteVehicleVisualState::accept);
            // 安装独立虚拟弹显示消费者，只写视觉缓存，不写本体远程目标表。
            org.ywzj.rvp.network.remotevisibility.RVP_VirtualMissileVisualEndpoint.install(
                    org.ywzj.rvp.client.state.remotevisibility.RVP_ClientVirtualMissileVisualState::accept);
            // 安装炮火支援公共端口，阶段 C 的 profile、请求结果和任务状态只写入客户端快照。
            RVP_FireSupportClientEndpoint.install(RVP_ClientFireSupportState.INSTANCE);
            // 安装 Gunner Profile 公共端口，使服务端 JSON 列表生成创造栏物品变体。
            RVP_GunnerProfileClientEndpoint.install(RVP_ClientGunnerProfileState.INSTANCE);
            // 调用无 DH 类型的可选依赖入口，仅在客户端且确认安装 DH 后加载 API 7 强类型桥。
            RVP_DistantHorizonsCompatBootstrap.initialize();
            RVP_ClientEntityRenderers.register();
            EntityRenderers.register(RVP_Entities.GUNNER.get(), GunnerRenderer::new);
            registerVehicleRenderers();
        });
    }

    public static void onLoadComplete(FMLLoadCompleteEvent event) {
        event.enqueueWork(() -> {
            registerVehicleRenderers();
            RVP_SbmProbeDebug.dumpCurrentVehicleState("load-complete-reregister");
        });
    }

    /** 注册直接绑定权威贴图的白磷粒子 Provider，不依赖粒子 JSON 或图集追加文件。 */
    public static void onRegisterParticleProviders(RegisterParticleProvidersEvent event) {
        event.registerSpecial(RVP_Particles.WHITE_PHOSPHORUS.get(), new RVP_WhitePhosphorusParticle.Provider());
        // MCHR 风格默认爆炸烟雾：粒子直接绑定 MCHR 原版 smoke.png（textures/boom/smoke.png，
        // 灰白底样 8 帧横排），帧集仅用于 Provider 兜底分发，工厂路径直接构造实例。
        event.registerSpecial(RVP_Particles.MCHR_SMOKE.get(), new RVP_MchrSmokeParticle.Provider());
        // 近距命中曳光残影：自绘速度对齐发光箱体（替代 DustParticleOptions 色尘补渲的观感方案）。
        event.registerSpecial(RVP_Particles.TRACER_GHOST.get(), new RVP_TracerGhostParticle.Provider());
        // 命中火星：弹药命中载具/方块后溅出的扩散火花（方向=命中反方向，大阻力衰减）。
        event.registerSpecial(RVP_Particles.IMPACT_SPARK.get(), new RVP_ImpactSparkParticle.Provider());
    }

    private static void registerVehicleRenderers() {
        // 统一换成 RVP 渲染器：仅在本体渲染后追加光电干扰机玻片红光，未配置 display 时行为完全一致。
        EntityRenderers.register(AllEntities.NONE_VEHICLE.get(), RVP_VehicleRender::new);
        EntityRenderers.register(AllEntities.WHEELED_VEHICLE.get(), RVP_VehicleRender::new);
        EntityRenderers.register(AllEntities.TRACKED_VEHICLE.get(), RVP_VehicleRender::new);
        EntityRenderers.register(AllEntities.VESSEL_VEHICLE.get(), RVP_VehicleRender::new);
        EntityRenderers.register(AllEntities.LAV150.get(), RVP_VehicleRender::new);
        EntityRenderers.register(AllEntities.ROTARY_WING_VEHICLE.get(), RVP_VehicleRender::new);
        EntityRenderers.register(AllEntities.FIXED_WING_VEHICLE.get(), RVP_VehicleRender::new);
        EntityRenderers.register(AllEntities.ZTZ99A.get(), RVP_VehicleRender::new);
        EntityRenderers.register(AllEntities.Z10.get(), RVP_VehicleRender::new);
        EntityRenderers.register(AllEntities.MOTORCYCLE.get(), RVP_VehicleRender::new);
        EntityRenderers.register(AllEntities.HIACE.get(), RVP_VehicleRender::new);
        EntityRenderers.register(AllEntities.QUADCOPTER.get(), RVP_VehicleRender::new);
        EntityRenderers.register(AllEntities.M1A2.get(), RVP_VehicleRender::new);
        EntityRenderers.register(AllEntities.CSSA5.get(), RVP_VehicleRender::new);
        EntityRenderers.register(AllEntities.AH64D.get(), RVP_VehicleRender::new);
        EntityRenderers.register(AllEntities.LAV_AD.get(), RVP_VehicleRender::new);
        EntityRenderers.register(AllEntities.BGM_71_TOW.get(), RVP_VehicleRender::new);
        EntityRenderers.register(AllEntities.HTF5980.get(), RVP_VehicleRender::new);
        // 残件渲染器覆盖注册：必须晚于本体 ClientSetupHandler 的注册（FMLClientSetupEvent）；
        // 本方法在 FMLLoadCompleteEvent 的 onLoadComplete() 里会被再调一次，覆盖注册顺序有保证。
        EntityRenderers.register(AllEntities.VEHICLE_PART.get(), RVP_VehiclePartRender::new);
    }
}
