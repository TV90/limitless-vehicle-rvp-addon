package org.ywzj.rvp.client.light;

import com.mojang.brigadier.arguments.FloatArgumentType;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.config.RVP_LightingConfig;
import org.ywzj.rvp.light.RVP_LightParameters;

/** 无伤害本地预览与兼容性诊断；不请求服务器放置方块。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ScreenLightDebugCommands {
    private RVP_ScreenLightDebugCommands() { }

    @SubscribeEvent
    public static void register(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("rvplight")
                .executes(context -> status())
                .then(Commands.literal("status").executes(context -> status()))
                .then(Commands.literal("clear").executes(context -> {
                    // 调用光源取消入口，下一帧无活动灯即完全跳过场景复制。
                    RVP_ScreenLightManager.INSTANCE.clear();
                    return 1;
                }))
                .then(Commands.literal("preview").executes(context -> preview(8))
                        .then(Commands.argument("radius", FloatArgumentType.floatArg(0.5F, 48))
                                .executes(context -> preview(FloatArgumentType.getFloat(context, "radius")))))
                .then(Commands.literal("engine").executes(context -> preview(7, true))));
    }

    /** 展示实际可用/降级状态，方便分别验证 CRT/TV、热成像和光影组合。 */
    private static int status() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) { return 0; }
        // 调用和合成器相同的能力检查，不把装了模组误报为画面已经验证通过。
        String reason = RVP_LightingCompatibility.disabledReason();
        mc.player.displayClientMessage(Component.literal("[RVP Light] tracked=" + RVP_ScreenLightManager.INSTANCE.size()
                + "，screen=" + RVP_LightingConfig.screenEnabled() + "，bloom=" + RVP_LightingConfig.bloomEnabled()
                + "，block=" + RVP_LightingConfig.blockLightEnabled()
                + "；" + (reason.isEmpty() ? RVP_ScreenLightPass.frameStatus() : reason)), false);
        return 1;
    }

    /** 在准星表面外侧生成一秒无伤害橙光，便于观察地面、墙面和遮挡。 */
    private static int preview(float radius) {
        // 调用共享预览入口，原有命令继续验证普通表面光。
        return preview(radius, false);
    }

    /** engine 模式用真实代码预设显示两秒喷口核心和橙色光晕，朝天空也可验证。 */
    private static int preview(float radius, boolean engine) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) { return 0; }
        Vec3 position = mc.gameRenderer.getMainCamera().getPosition().add(mc.player.getLookAngle().scale(4));
        HitResult hit = mc.hitResult;
        if (hit != null && hit.getType() != HitResult.Type.MISS) {
            Vec3 towardCamera = mc.gameRenderer.getMainCamera().getPosition().subtract(hit.getLocation()).normalize();
            position = hit.getLocation().add(towardCamera.scale(0.25));
        }
        // 调用统一管理器，普通光与发动机自发光均使用真实渲染管线，不创建额外粒子或方块。
        RVP_ScreenLightManager.INSTANCE.beginWorld(mc.level, mc.level.getGameTime());
        // 调用真实发动机预设，只把预览寿命延长到两秒，亮度/颜色/半径与正式光源一致。
        RVP_LightParameters preset = RVP_LightParameters.engine();
        RVP_LightParameters parameters = engine
                ? new RVP_LightParameters(true, preset.red(), preset.green(), preset.blue(), preset.radius(),
                        preset.intensity(), 40, 0, preset.surfaceWeight(), preset.decay(), preset.bloom(), 0, 0)
                : new RVP_LightParameters(true, 1, 0.8F, 0.5F, radius, 1.3F, 20, 0, 1, 1, true, 0.2F, 0);
        RVP_ScreenLightManager.INSTANCE.addTransient(position,
                engine ? RVP_LightSourceType.ROCKET_ENGINE : RVP_LightSourceType.EXPLOSION, null,
                parameters, mc.level.getGameTime());
        return 1;
    }
}
