package org.ywzj.rvp.virtualflight.server;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;

/**
 * 注册仅管理员可用的虚拟飞行运维命令：{@code /rvpvirtualflight status|on|off}。
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_VirtualMissileDebugCommands {
    private RVP_VirtualMissileDebugCommands() {}

    /**
     * 在 Forge 命令注册事件中安装命令树。
     *
     * @param event 服务端命令注册事件
     */
    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        // status 调用统一统计出口；on/off 只切换日志，不改变权威飞行状态。
        event.getDispatcher().register(Commands.literal("rvpvirtualflight")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("status").executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal(
                            "[RVP] virtualflight " + RVP_VirtualMissileDebug.status(context.getSource().getServer())), false);
                    return 1;
                }))
                .then(Commands.literal("on").executes(context -> {
                    RVP_VirtualMissileDebug.setEnabled(true);
                    context.getSource().sendSuccess(() -> Component.literal("[RVP] virtualflight debug=on"), false);
                    return 1;
                }))
                .then(Commands.literal("off").executes(context -> {
                    RVP_VirtualMissileDebug.setEnabled(false);
                    context.getSource().sendSuccess(() -> Component.literal("[RVP] virtualflight debug=off"), false);
                    return 1;
                })));
    }
}
