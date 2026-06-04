package org.ywzj.rvp.client.debug;

import com.mojang.logging.LogUtils;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.ywzj.rvp.RVP_MOD;

@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RVP_DebugCommands {

    private static final Logger LOGGER = LogUtils.getLogger();

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("rvpdebug")
                        .then(Commands.literal("tvMissileDump").executes(ctx -> {
                            RVP_TVMissileDebug.requestDump();
                            LOGGER.info("[RVP][TVMissile] tvMissileDump requested");
                            ctx.getSource().sendSuccess(() -> Component.translatable("commands.ywzj_rvp.debug.tv_missile.dump"), false);
                            return 1;
                        }))
                        .then(Commands.literal("tvMissileBwSpam")
                                .then(Commands.literal("on").executes(ctx -> {
                                    RVP_TVMissileDebug.setBwSpamEnabled(true);
                                    ctx.getSource().sendSuccess(() -> Component.translatable("commands.ywzj_rvp.debug.tv_missile.bw_spam.on"), false);
                                    return 1;
                                }))
                                .then(Commands.literal("off").executes(ctx -> {
                                    RVP_TVMissileDebug.setBwSpamEnabled(false);
                                    ctx.getSource().sendSuccess(() -> Component.translatable("commands.ywzj_rvp.debug.tv_missile.bw_spam.off"), false);
                                    return 1;
                                }))
                                .then(Commands.literal("status").executes(ctx -> {
                                    boolean enabled = RVP_TVMissileDebug.isBwSpamEnabled();
                                    Component state = Component.translatable(enabled ? "commands.ywzj_rvp.state.on" : "commands.ywzj_rvp.state.off");
                                    ctx.getSource().sendSuccess(() -> Component.translatable("commands.ywzj_rvp.debug.tv_missile.bw_spam.status", state), false);
                                    return enabled ? 1 : 0;
                                }))
                        )
        );
    }
}
