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
 *   /cockpitdiag status     聊天栏输出各屏分层计数（draws/fail/md5/内容 + 骨直绘帧/秒）
 *   /cockpitdiag gl         聊天栏输出当前 GL 状态快照
 *   /cockpitdiag trace 200  开启逐帧 trace（200 tick 内每帧一行全量快照）
 *
 *   /rvpbb  （长名 /rvpblackbox，等价）——黑匣子，2026-10-15 从 cockpitdiag 拆出成独立命令
 *   /rvpbb on|off           开始/停止待命（只留最近 30 秒三元组，不写心跳、不 dump PNG）
 *   /rvpbb dump             立即把最近 30 秒落盘 → logs/rvp_cockpit_blackbox.log
 *   /rvpbb status           查看开关与缓冲长度
 * </pre>
 *
 * <p><b>默认全部关闭</b>（2026-10-15 用户裁定：命令驱动，避免长期自动写盘）。
 * 复现随机问题的推荐姿势：{@code /rvpbb on} 挂着 → 出问题 → {@code /rvpbb dump}
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
                                    + " | 用法: on|off|status|gl|trace <tick>（黑匣子见 /rvpbb）"), false);
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

        // 黑匣子独立命令（2026-10-15 用户要求：不与 cockpitdiag 的全量记录混用）。
        // 短名 rvpbb 便于随手敲，长名 rvpblackbox 便于自查；两者等价。
        dispatcher.register(blackboxCommand("rvpbb"));
        dispatcher.register(blackboxCommand("rvpblackbox"));
    }

    /**
     * 黑匣子命令树：把"本体认为的车 / 原版乘坐 / 屏内容源"三元组的最近 30 秒留证。
     *
     * <pre>
     *   /rvpbb            查看状态与用法
     *   /rvpbb on|off     开始/停止待命（停止会清空环形缓冲）
     *   /rvpbb dump       立即把最近 30 秒落盘 → logs/rvp_cockpit_blackbox.log
     *   /rvpbb status     聊天栏看当前开关与缓冲长度
     * </pre>
     */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> blackboxCommand(
            String name) {
        return Commands.literal(name)
                .executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal(blackboxStateLine()), false);
                    return 1;
                })
                .then(Commands.literal("on").executes(context -> {
                    RVP_CockpitDiag.setWatch(true);
                    context.getSource().sendSuccess(() -> Component.literal(
                            "[黑匣子] 已待命：每 tick 记 本体/原版/屏源 三元组（最近 30 秒），"
                                    + "任一变化自动落盘 logs/rvp_cockpit_blackbox.log；不写心跳、不 dump PNG"), false);
                    return 1;
                }))
                .then(Commands.literal("off").executes(context -> {
                    RVP_CockpitDiag.setWatch(false);
                    context.getSource().sendSuccess(() -> Component.literal(
                            "[黑匣子] 已停止（环形缓冲已清空）"), false);
                    return 1;
                }))
                .then(Commands.literal("dump").executes(context -> {
                    int size = RVP_CockpitDiag.blackboxSize();
                    RVP_CockpitDiag.dumpBlackboxNow("手动 /" + name + " dump");
                    context.getSource().sendSuccess(() -> Component.literal(
                            "[黑匣子] 已落盘：缓冲 " + size + " tick（≈" + (size / 20)
                                    + " 秒）→ logs/rvp_cockpit_blackbox.log"), false);
                    return 1;
                }))
                .then(Commands.literal("status").executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal(blackboxStateLine()), false);
                    return 1;
                }));
    }

    /** @return 黑匣子当前状态一行（命令反馈用）。 */
    private static String blackboxStateLine() {
        return "[黑匣子] " + (RVP_CockpitDiag.isWatch() ? "待命中" : "已停止（默认）")
                + " | 缓冲 " + RVP_CockpitDiag.blackboxSize() + " tick"
                + " | 用法: on|off|dump|status（/cockpitdiag on 全量记录期间黑匣子同样工作）";
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
