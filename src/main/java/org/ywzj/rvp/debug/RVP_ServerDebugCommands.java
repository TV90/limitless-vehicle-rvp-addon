package org.ywzj.rvp.debug;

import com.mojang.logging.LogUtils;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.ywzj.rvp.RVP_MOD;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ServerDebugCommands {
    private static final Logger LOGGER = LogUtils.getLogger();

    private RVP_ServerDebugCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("rvpdebug")
                        .then(Commands.literal("dualpulse")
                                .then(Commands.literal("on").executes(ctx -> {
                                    RVP_DualPulseDebug.clearLog();
                                    RVP_DualPulseDebug.setEnabled(true);
                                    ctx.getSource().sendSuccess(() ->
                                            Component.literal("[RVP] 已开启 dualpulse 调试: " + RVP_DualPulseDebug.getLogPath()), false);
                                    return 1;
                                }))
                                .then(Commands.literal("off").executes(ctx -> {
                                    RVP_DualPulseDebug.setEnabled(false);
                                    ctx.getSource().sendSuccess(() ->
                                            Component.literal("[RVP] 已关闭 dualpulse 调试: " + RVP_DualPulseDebug.getLogPath()), false);
                                    return 1;
                                }))
                                .then(Commands.literal("status").executes(ctx -> {
                                    boolean enabled = RVP_DualPulseDebug.isEnabled();
                                    ctx.getSource().sendSuccess(() ->
                                            Component.literal("[RVP] dualpulse=" + enabled + " path=" + RVP_DualPulseDebug.getLogPath()), false);
                                    return enabled ? 1 : 0;
                                }))
                                .then(Commands.literal("dump").executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayer();
                                    writeLog(RVP_DualPulseDebug.getLogPath(), RVP_DualPulseDebug.dumpSnapshot(player));
                                    ctx.getSource().sendSuccess(() ->
                                            Component.literal("[RVP] 已写入 " + RVP_DualPulseDebug.getLogPath()), false);
                                    return 1;
                                }))
                                .then(Commands.literal("clear").executes(ctx -> {
                                    RVP_DualPulseDebug.clearLog();
                                    ctx.getSource().sendSuccess(() ->
                                            Component.literal("[RVP] 已清空 dualpulse 调试日志: " + RVP_DualPulseDebug.getLogPath()), false);
                                    return 1;
                                })))
        );
    }

    private static void writeLog(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (IOException e) {
            LOGGER.error("Failed to write dualpulse debug log to {}", path, e);
        }
    }
}
