package org.ywzj.rvp.debug;

import com.mojang.brigadier.arguments.FloatArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.server.visual.RVP_NetworkVisualEventPublisher;
import org.ywzj.rvp.server.visual.RVP_ShockwaveProfileSampler;
import org.ywzj.rvp.weapon.visual.api.RVP_VisualEffectEvent;

/** 无伤害视觉验收命令，直接使用真实地形采样和正式网络消息。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ShockwaveDebugCommands {
    private RVP_ShockwaveDebugCommands() { }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("rvpwilsonmach")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("position", Vec3Argument.vec3())
                        .then(Commands.argument("radius", FloatArgumentType.floatArg(0.1F, 512F))
                                .executes(context -> {
                                    var source = context.getSource();
                                    var level = source.getLevel();
                                    var position = Vec3Argument.getVec3(context, "position");
                                    float radius = FloatArgumentType.getFloat(context, "radius");
                                    // 调用正式采样器，以实际地形自动判定空爆、地爆或埋爆。
                                    var profile = RVP_ShockwaveProfileSampler.sample(level, position, radius, 1);
                                    var visual = new RVP_VisualEffectEvent(RVP_ShockwaveProfileSampler.EFFECT_TYPE,
                                            ResourceLocation.fromNamespaceAndPath("rvp", "default"), "{}",
                                            level.dimension(), position, radius, 1, 1, 220, 8192,
                                            level.random.nextLong(), level.getGameTime(), true, true, true, false, profile);
                                    // 调用正式发布端，仅播放效果，不产生伤害或方块破坏。
                                    new RVP_NetworkVisualEventPublisher().publish(level, visual);
                                    source.sendSuccess(() -> Component.literal("威尔逊云测试已发送：" + profile.hitClass()
                                            + "，地表高度=" + profile.groundY() + "，不产生伤害"), false);
                                    return 1;
                                }))));
    }
}
