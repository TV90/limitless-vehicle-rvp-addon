package org.ywzj.rvp.client.debug;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.visual.RVP_ClientVisualEffectDispatcher;
import org.ywzj.rvp.client.visual.RVP_WilsonMachEffectFactory;
import org.ywzj.rvp.weapon.visual.RVP_ShockwaveRuntimeTuning;
import org.ywzj.rvp.weapon.visual.RVP_ShockwaveRuntimeTuning.Group;
import org.ywzj.rvp.weapon.visual.RVP_ShockwaveRuntimeTuning.Parameter;
import org.ywzj.rvp.weapon.visual.api.RVP_ShockwaveProfile;
import org.ywzj.rvp.weapon.visual.api.RVP_VisualEffectEvent;

import java.util.Collections;

/** 三种冲击前沿的本地调参指令；不要求服务器授权，不改伤害，不写配置文件。 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ShockwaveTuningCommands {
    private RVP_ShockwaveTuningCommands() { }

    /** 注册独立客户端指令，参数节点自带补全与范围校验。 */
    @SubscribeEvent
    public static void register(RegisterClientCommandsEvent event) {
        // 调用命令树构造入口，在联网和单机客户端都提供相同调参方式。
        event.getDispatcher().register(build());
    }

    /** 构造 set/status/reset/preview 命令树。 */
    private static LiteralArgumentBuilder<CommandSourceStack> build() {
        var root = Commands.literal("rvpshockwave").executes(context -> help(context.getSource()));
        var set = Commands.literal("set");
        var status = Commands.literal("status").executes(context -> {
            for (Group group : Group.values()) { show(context.getSource(), group); }
            return 1;
        });
        var reset = Commands.literal("reset").executes(context -> {
            // 调用会话管理器清除全部覆盖，下一次效果使用代码默认值。
            RVP_ShockwaveRuntimeTuning.reset();
            send(context.getSource(), "三组参数已恢复默认；从下一次爆炸或 preview 生效。");
            return 1;
        });
        for (Group group : Group.values()) {
            var groupNode = Commands.literal(group.name());
            for (Parameter parameter : Parameter.values()) {
                // 调用参数定义检查，仅生成当前组件适用的参数节点。
                if (!parameter.supports(group)) { continue; }
                groupNode.then(Commands.literal(parameter.name()).then(Commands.argument("value",
                        FloatArgumentType.floatArg(parameter.min, parameter.max)).executes(context -> {
                    float value = FloatArgumentType.getFloat(context, "value");
                    try {
                        // 调用统一校验和写入入口，禁止坏值进入渲染曲线。
                        RVP_ShockwaveRuntimeTuning.set(group, parameter, value);
                    } catch (IllegalArgumentException exception) {
                        context.getSource().sendFailure(Component.literal(exception.getMessage()));
                        return 0;
                    }
                    send(context.getSource(), group + "." + parameter + "=" + value + "；"
                            + parameter.description + "。"
                            // 调用代码开关检查，避免提示用户被停用组件已经生效。
                            + (RVP_ShockwaveRuntimeTuning.enabled(group) ? "下一次爆炸或 preview 生效。"
                            : "参数已保存，但该组件已在代码中暂时关闭，不会显示。"));
                    return 1;
                })));
            }
            set.then(groupNode);
            status.then(Commands.literal(group.name()).executes(context -> show(context.getSource(), group)));
            reset.then(Commands.literal(group.name()).executes(context -> {
                // 调用单组重置，保留另外两组的试调结果。
                RVP_ShockwaveRuntimeTuning.reset(group);
                send(context.getSource(), group + " 已恢复默认；下一次爆炸或 preview 生效。");
                return 1;
            }));
        }
        return root.then(Commands.literal("help").executes(context -> help(context.getSource())))
                .then(set).then(status).then(reset)
                .then(Commands.literal("preview")
                        .then(Commands.argument("radius", FloatArgumentType.floatArg(0.1F, 512))
                                .executes(context -> preview(context.getSource(), FloatArgumentType.getFloat(context, "radius"), 0))
                                .then(Commands.argument("height", FloatArgumentType.floatArg(-64, 256))
                                        .executes(context -> preview(context.getSource(), FloatArgumentType.getFloat(context, "radius"),
                                                FloatArgumentType.getFloat(context, "height"))))));
    }

    /** 显示操作方式、单位及生效边界。 */
    private static int help(CommandSourceStack source) {
        // 调用本地提示入口，说明中心通透、白雾内浓外淡的压力波及现有参数的使用方式。
        send(source, "本地会话调参：sphere=球形，ring=地面环，mach=马赫杆墙；退出游戏恢复默认。");
        send(source, "当前压力波正面中心通透，白雾内浓外淡，外层保留空气扭曲；地面环和马赫杆仍在代码中关闭。");
        send(source, "sphere brightness 调白雾，refraction 调背景扭曲，thickness 调空气层厚度基数（最终最多占半径0.6）。");
        send(source, "pressureStrength 调最外侧透明波前的力量，默认1；0关闭加力层，白雾和扩张范围不变。");
        send(source, "initialThicknessRatio/finalThicknessRatio 调初末厚度，compressionPower 调压薄曲线；dispersion 调轻微边缘色散。");
        send(source, "/rvpshockwave set <组> <参数> <值>；status [组] 查看数值/单位/范围；reset [组] 恢复默认。");
        send(source, "speed 整体提速且范围不变；expansionSpeed 只加快扩张；radiusScale 调范围。20 tick=1秒。");
        send(source, "球体范围、时间与压薄曲线沿用 0.2.2；空气层用独立程序合成，读取完整世界颜色与深度。");
        send(source, "时间基准为原事件220tick；自定义事件时长仍同比缩放。扩张与淡出公式见调参文档。");
        send(source, "/rvpshockwave preview <爆炸半径> [离地高度]：在前方20格显示无伤害测试效果。");
        return 1;
    }

    /** 显示一组当前数值、默认值、允许范围和中文含义。 */
    private static int show(CommandSourceStack source, Group group) {
        // 调用快照入口，保证整次查询读取同一组参数。
        var tuning = RVP_ShockwaveRuntimeTuning.snapshot();
        // 调用统一开关查询，状态页明确区分参数保留和实际启用。
        send(source, group + (RVP_ShockwaveRuntimeTuning.enabled(group)
                ? "（已启用；仅本机，下一次效果生效）" : "（代码已暂时关闭；仅保留参数）"));
        for (Parameter parameter : Parameter.values()) {
            if (parameter.supports(group)) {
                send(source, parameter + "=" + tuning.get(group, parameter) + "，默认=" + parameter.defaultValue(group)
                        + "，范围=" + parameter.min + "～" + parameter.max + "；" + parameter.description);
            }
        }
        return 1;
    }

    /** 用正式空气层画法预览；地面组件保留简化轮廓，不向服务端发包或造成伤害。 */
    private static int preview(CommandSourceStack source, float radius, float height) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || !Float.isFinite(radius) || !Float.isFinite(height)) {
            source.sendFailure(Component.literal("需要进入世界，且半径和高度必须为有限数值。"));
            return 0;
        }
        Vec3 forward = minecraft.player.getLookAngle().multiply(1, 0, 1).normalize();
        Vec3 target = minecraft.player.position().add(forward.scale(20));
        int x = (int) Math.floor(target.x);
        int z = (int) Math.floor(target.z);
        if (!minecraft.level.hasChunk(x >> 4, z >> 4)) {
            source.sendFailure(Component.literal("前方区块尚未加载，请靠近后重试。"));
            return 0;
        }
        double ground = minecraft.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        // 调用正式地形分类和尺寸公式；预览使用等长八方向，实弹仍使用服务端地形采样。
        var hitClass = RVP_ShockwaveProfile.classify(height);
        float solidity = switch (hitClass) { case AIR -> 0.1F; case GROUND -> 0.7F; case BURIED -> 1.0F; };
        var profile = new RVP_ShockwaveProfile(hitClass, ground, solidity,
                Collections.nCopies(8, RVP_ShockwaveProfile.visualRadius(radius, 1)));
        var visual = new RVP_VisualEffectEvent(RVP_WilsonMachEffectFactory.EFFECT_TYPE,
                ResourceLocation.fromNamespaceAndPath("rvp", "default"), "{}", minecraft.level.dimension(),
                new Vec3(target.x, ground + height, target.z), radius, 1, 0, 220, 8192,
                minecraft.level.random.nextLong(), minecraft.level.getGameTime(), false, false, false, false, profile);
        // 调用现有客户端分派器，使用真实实例的生命周期、热成像与预算管理。
        RVP_ClientVisualEffectDispatcher.accept(visual);
        // 调用本地提示入口，说明预览与正式爆炸共用中心通透的压力波和深度遮挡。
        send(source, "已显示本地无伤害预览，半径=" + radius + "，离地=" + height
                + "；压力波中心通透、白雾内浓外淡，外层有空气折射，按场景深度遮挡；地面环和马赫杆已停用。");
        return 1;
    }

    /** 向当前客户端反馈中文调参说明。 */
    private static void send(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal("[RVP冲击波] " + message), false);
    }
}
