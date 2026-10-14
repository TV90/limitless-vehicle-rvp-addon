package org.ywzj.rvp.client.cockpit;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/** 座舱屏诊断开关与状态查询客户端命令（{@code /cockpitdiag}）。 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_CockpitDiagCommands {

    private RVP_CockpitDiagCommands() {}

    /** 注册客户端命令：on/off 切换自动记录，status 输出当前状态到聊天栏。 */
    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("cockpitdiag")
                .executes(context -> {
                    // 无参：输出当前开关与载具解析状态。
                    context.getSource().sendSuccess(() -> Component.literal(
                            "[座舱屏诊断] " + (RVP_CockpitDiag.isEnabled() ? "记录中" : "已关闭")
                                    + "；/cockpitdiag on|off"), false);
                    return 1;
                })
                .then(Commands.literal("on").executes(context -> {
                    // 调用本项目诊断器：开启自动记录（专有文件 logs/rvp_cockpit_diag.log）。
                    RVP_CockpitDiag.setEnabled(true);
                    context.getSource().sendSuccess(() -> Component.literal(
                            "[座舱屏诊断] 已开启，输出到 logs/rvp_cockpit_diag.log"), false);
                    return 1;
                }))
                .then(Commands.literal("off").executes(context -> {
                    // 调用本项目诊断器：关闭自动记录。
                    RVP_CockpitDiag.setEnabled(false);
                    context.getSource().sendSuccess(() -> Component.literal(
                            "[座舱屏诊断] 已关闭"), false);
                    return 1;
                })));
    }
}
