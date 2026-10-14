package org.ywzj.rvp.client.cockpit;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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

/**
 * 座舱屏诊断命令（2026-10-15 诊断 v2）：{@code /cockpitdiag}。
 *
 * <pre>
 *   /cockpitdiag            查看用法与当前开关
 *   /cockpitdiag on|off     开启/关闭**全量**记录（logs/rvp_cockpit_diag.log：每秒心跳/特殊骨/PNG dump）
 *   /cockpitdiag watch      只待命**黑匣子**：不写心跳、不 dump PNG，出问题时再落盘（推荐长期挂着）
 *   /cockpitdiag blackbox   立即把黑匣子（最近 30 秒的 本体/原版/屏源 三元组）落盘
 *   /cockpitdiag status     聊天栏输出各屏分层计数（draws/fail/md5/内容 + 骨直绘帧/秒）
 *   /cockpitdiag gl         聊天栏输出当前 GL 状态快照
 *   /cockpitdiag trace 200  开启逐帧 trace（200 tick 内每帧一行全量快照）
 * </pre>
 *
 * <p><b>默认全部关闭</b>（2026-10-15 用户裁定：改为命令驱动，避免长期自动写盘）。
 * 复现随机问题的推荐姿势：先 {@code /cockpitdiag watch} 挂着 → 出问题 → {@code /cockpitdiag blackbox}
 * （或直接看它自动落盘的那段）→ 再看同刻的 {@code 近旁载具=} / {@code 席位=} / {@code 接收器=}。</p>
 *
 * <p>排"屏绘戛然而止"的老姿势：先 {@code status} 看 draws 与骨直绘帧/秒是否还在涨，
 * 再看 md5 是否停滞——据此判定是驱动层、RT 侧还是显示侧断的（详见
 * {@link RVP_CockpitDiag} 类注释的判别表）。</p>
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_CockpitDiagCommands {

    private RVP_CockpitDiagCommands() {}

    /** 注册客户端命令。 */
    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("cockpitdiag")
                .executes(context -> {
                    // 无参：输出用法与当前开关状态（默认全关，2026-10-15 改为命令驱动）。
                    String state = RVP_CockpitDiag.isEnabled() ? "全量记录中"
                            : (RVP_CockpitDiag.isWatch() ? "黑匣子待命" : "已关闭（默认）");
                    context.getSource().sendSuccess(() -> Component.literal(
                            "[座舱屏诊断] " + state
                                    + " | 用法: on|off|watch|blackbox|status|gl|trace <tick>"), false);
                    return 1;
                })
                .then(Commands.literal("on").executes(context -> {
                    // 调用本项目诊断器：开启全量记录（专有文件 logs/rvp_cockpit_diag.log）。
                    RVP_CockpitDiag.setEnabled(true);
                    context.getSource().sendSuccess(() -> Component.literal(
                            "[座舱屏诊断] 已开启全量记录，输出到 logs/rvp_cockpit_diag.log"), false);
                    return 1;
                }))
                .then(Commands.literal("off").executes(context -> {
                    // 调用本项目诊断器：关闭全量记录（黑匣子若开着则继续待命）。
                    RVP_CockpitDiag.setEnabled(false);
                    context.getSource().sendSuccess(() -> Component.literal(
                            "[座舱屏诊断] 已关闭全量记录"
                                    + (RVP_CockpitDiag.isWatch() ? "（黑匣子仍在待命）" : "")), false);
                    return 1;
                }))
                .then(Commands.literal("watch").executes(context -> {
                    // 只待命黑匣子：不写每秒心跳、不 dump PNG；三元组任一变化即自动落盘。
                    // 长期挂着不吵，随机复现后可直接取证（2026-10-15 用户要求）。
                    boolean now = !RVP_CockpitDiag.isWatch();
                    RVP_CockpitDiag.setWatch(now);
                    context.getSource().sendSuccess(() -> Component.literal(now
                            ? "[座舱屏诊断] 黑匣子已待命：只记 本体/原版/屏源 三元组（最近 30 秒），"
                                    + "状态变化自动落盘；心跳与 PNG 不写"
                            : "[座舱屏诊断] 黑匣子已停（缓冲已清空）"), false);
                    return 1;
                }))
                .then(Commands.literal("blackbox").executes(context -> {
                    // 立即把最近 30 秒三元组落盘（出现问题时马上敲这条）。
                    int size = RVP_CockpitDiag.blackboxSize();
                    RVP_CockpitDiag.dumpBlackboxNow("手动 /cockpitdiag blackbox");
                    context.getSource().sendSuccess(() -> Component.literal(
                            "[座舱屏诊断] 黑匣子已落盘：缓冲 " + size + " tick（≈" + (size / 20)
                                    + " 秒）→ logs/rvp_cockpit_blackbox.log"), false);
                    return 1;
                }))
                .then(Commands.literal("status").executes(context -> {
                    // 聊天栏输出分层计数：三屏的 painter 次数/异常/RT 指纹/内容签名。
                    for (String line : statusLines()) {
                        context.getSource().sendSuccess(() -> Component.literal(line), false);
                    }
                    return 1;
                }))
                .then(Commands.literal("gl").executes(context -> {
                    // 聊天栏输出 GL 状态快照（排查残留状态：FBO/纹理/shader/混合/深度/线宽）。
                    String snapshot = RVP_CockpitDiag.glSnapshot();
                    context.getSource().sendSuccess(
                            () -> Component.literal("[GL快照] " + snapshot), false);
                    return 1;
                }))
                .then(Commands.literal("trace")
                        .executes(context -> {
                            // 默认 trace 100 tick。
                            RVP_CockpitDiag.startTrace(100);
                            context.getSource().sendSuccess(() -> Component.literal(
                                    "[座舱屏诊断] 逐帧 trace 100 tick（每帧一行全量快照）"), false);
                            return 1;
                        })
                        .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 20000))
                                .executes(context -> {
                                    int ticks = IntegerArgumentType.getInteger(context, "ticks");
                                    RVP_CockpitDiag.startTrace(ticks);
                                    context.getSource().sendSuccess(() -> Component.literal(
                                            "[座舱屏诊断] 逐帧 trace " + ticks + " tick"), false);
                                    return 1;
                                }))));
    }

    /** @return 各屏分层计数（聊天栏逐行展示）。 */
    private static java.util.List<String> statusLines() {
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add("[座舱屏诊断] " + (RVP_CockpitDiag.isEnabled() ? "记录中" : "已关闭")
                + (RVP_CockpitDiag.isTracing() ? " + 逐帧trace" : ""));
        boolean empty = true;
        for (var entry : RVP_CockpitDiag.allStats().entrySet()) {
            empty = false;
            RVP_CockpitDiag.ScreenStat stat = entry.getValue();
            lines.add(" " + entry.getKey() + ": draws=" + stat.draws
                    + " fail=" + stat.fails + " md5=" + stat.md5
                    + (stat.md5Same > 1 ? "(停滞×" + stat.md5Same + ")" : "")
                    + " 内容[" + stat.contentSig + "]"
                    + " 异常=" + stat.lastError);
        }
        if (empty) {
            lines.add(" 尚未记录到屏绘（未乘载 / 刚重载资源？）");
        }
        for (var entry : RVP_CockpitDiag.renderStats().entrySet()) {
            RVP_CockpitDiag.RenderStat stat = entry.getValue();
            lines.add(" 骨直绘 " + entry.getKey() + ": 累计帧=" + stat.frames
                    + " verts=" + stat.verts);
        }
        lines.add(" 相关性 " + RVP_CockpitDiag.missileSnapshot(localVehicle()));
        return lines;
    }

    /** @return 本地玩家载具（与 Manager 同款解析：优先武器站，回退 lvp.vehicle）。 */
    private static AbstractVehicle localVehicle() {
        LocalVehiclePlayer lvp = LocalVehiclePlayer.instance;
        if (lvp == null) {
            return null;
        }
        WeaponUnit weaponUnit = lvp.getWeaponUnit();
        if (weaponUnit != null && weaponUnit.getVehicle() != null) {
            return weaponUnit.getVehicle();
        }
        return lvp.vehicle;
    }
}
