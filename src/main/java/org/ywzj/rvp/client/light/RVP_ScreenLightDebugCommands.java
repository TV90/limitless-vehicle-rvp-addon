package org.ywzj.rvp.client.light;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
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
import org.ywzj.rvp.light.RVP_LightRuntimeTuning;
import org.ywzj.rvp.light.RVP_LightRuntimeTuning.Group;
import org.ywzj.rvp.light.RVP_LightRuntimeTuning.Parameter;
import java.util.Map;

/** 光照与光晕本地调参、无伤害预览和兼容性诊断；不请求服务器放置方块。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ScreenLightDebugCommands {
    private RVP_ScreenLightDebugCommands() { }

    @SubscribeEvent
    public static void register(RegisterClientCommandsEvent event) {
        // 调用统一命令树构造，单机与联机都只调整当前客户端的画面。
        event.getDispatcher().register(build());
    }

    /** 保留原预览命令，按同一份参数定义生成补全、范围检查、查询和重置节点。 */
    static LiteralArgumentBuilder<CommandSourceStack> build() {
        var root = Commands.literal("rvplight")
                .executes(context -> status())
                .then(Commands.literal("help").executes(context -> help(context.getSource())))
                .then(Commands.literal("copy").executes(context -> copy(context.getSource())))
                .then(Commands.literal("clear").executes(context -> {
                    // 调用光源取消入口，下一帧无活动灯即完全跳过场景复制。
                    RVP_ScreenLightManager.INSTANCE.clear();
                    RVP_RocketPlumeManager.INSTANCE.clear();
                    // 调用新尾烟清理，显式 clear 同时清除残留的世界烟段。
                    RVP_RocketTrailManager.INSTANCE.clear();
                    send(context.getSource(), "已清除当前预览与光源；调参值保留，仍在燃烧的发动机会继续更新。");
                    return 1;
                }))
                .then(Commands.literal("preview").executes(context -> preview(8))
                        .then(Commands.argument("radius", FloatArgumentType.floatArg(0.01F, RVP_LightRuntimeTuning.MAX_TUNED_RADIUS))
                                .executes(context -> preview(FloatArgumentType.getFloat(context, "radius")))))
                .then(Commands.literal("engine").executes(context -> preview(0, true))
                        .then(Commands.argument("scale", FloatArgumentType.floatArg(0.001F, 256F))
                                .executes(context -> preview(0, true,
                                        FloatArgumentType.getFloat(context, "scale")))
                                .then(Commands.argument("caliber", FloatArgumentType.floatArg(0, 1000000F))
                                        .executes(context -> preview(0, true,
                                                FloatArgumentType.getFloat(context, "scale"),
                                                FloatArgumentType.getFloat(context, "caliber"))))));
        var status = Commands.literal("status").executes(context -> status());
        var set = Commands.literal("set");
        var reset = Commands.literal("reset").executes(context -> {
            // 调用会话重置，恢复现有算法的代码参数与用户原有画质配置。
            RVP_LightRuntimeTuning.reset();
            send(context.getSource(), "全部调参已恢复默认；下一帧生效。");
            return 1;
        });
        for (Group group : Group.values()) {
            var setGroup = Commands.literal(group.name()).executes(context -> show(context.getSource(), group));
            var statusGroup = Commands.literal(group.name()).executes(context -> show(context.getSource(), group));
            var resetGroup = Commands.literal(group.name()).executes(context -> {
                // 调用分组重置，其他分组保留试调结果。
                RVP_LightRuntimeTuning.reset(group, null);
                send(context.getSource(), group + " 已恢复默认；下一帧生效。");
                return 1;
            });
            for (Parameter parameter : Parameter.values()) {
                // 调用适用性定义，避免出现设置了却没有接入渲染的无效参数。
                if (!parameter.supports(group)) { continue; }
                setGroup.then(Commands.literal(parameter.name())
                        .executes(context -> showParameter(context.getSource(), group, parameter))
                        .then(Commands.argument("value", FloatArgumentType.floatArg(parameter.min, parameter.max))
                                .executes(context -> {
                                    try {
                                        // 调用统一校验入口，非法数值不能污染渲染输入。
                                        RVP_LightRuntimeTuning.set(group, parameter,
                                                FloatArgumentType.getFloat(context, "value"));
                                    } catch (IllegalArgumentException exception) {
                                        context.getSource().sendFailure(Component.literal(exception.getMessage()));
                                        return 0;
                                    }
                                    // 调用参数反馈，修改后同时展示中文含义和当前范围。
                                    showParameter(context.getSource(), group, parameter);
                                    send(context.getSource(), "下一帧生效；仅本机，本次游戏进程内保留，重启游戏恢复默认。");
                                    return 1;
                                })));
                statusGroup.then(Commands.literal(parameter.name())
                        .executes(context -> showParameter(context.getSource(), group, parameter)));
                resetGroup.then(Commands.literal(parameter.name()).executes(context -> {
                    // 调用单参数重置，方便对照调试前后效果。
                    RVP_LightRuntimeTuning.reset(group, parameter);
                    return showParameter(context.getSource(), group, parameter);
                }));
            }
            set.then(setGroup);
            status.then(statusGroup);
            reset.then(resetGroup);
        }
        return root.then(set).then(status).then(reset);
    }

    /** 展示中文分组含义和可直接复制使用的调试示例。 */
    private static int help(CommandSourceStack source) {
        // 调用本地反馈说明指令与单位，避免只展示英文参数名。
        send(source, "global=环境光总设置，explosion=爆炸，muzzle=炮口，engine=发动机，bloom=模糊光晕。");
        send(source, "/rvplight set <组> <参数> <值>；status <组> [参数] 查看数值、默认值及中文含义，支持Tab补全。");
        send(source, "/rvplight reset [组] [参数] 恢复默认；copy 将本次全部修改复制成可重放指令。");
        send(source, "explosion/muzzle/engine 均支持 radiusScale 调照亮半径、haloRadiusScale 调光晕半径；两者分开，0可单独关闭。");
        send(source, "例：set explosion worldHaloIntensity 8 独立调爆炸广域光晕；set explosion haloRadiusScale 0.5 调其范围。");
        send(source, "bloom nearStrength 调贴近亮焰的两层光晕，farStrength 调向周围消散的两层辉光；两者互不补偿。");
        send(source, "爆炸默认brightness=20、radiusScale=5；worldHaloIntensity默认16，独立于照亮亮度。发动机亮度继续分开调。");
        send(source, "/rvplight preview [半径] 预览爆炸类短光；engine [喷焰缩放] [毫米口径] 预览真实发动机算法。");
        send(source, "两种半径均允许0～8192格；爆炸光默认32刻即1.6秒，衰减系数0.3；喷焰长宽仍由原有缩放决定。");
        send(source, "explosion/muzzle的decayScale越小整盏光源越长，越大越短；爆炸0.5约3.2秒、2约0.8秒，照亮和光晕同步平滑淡出。");
        send(source, "所有修改下一帧生效，仅本机；重连/换维度保留，重启游戏恢复默认。不会写配置或载具包。");
        return 1;
    }

    /** 查询一组全部参数；开关未覆盖时显示实际画质配置的状态。 */
    private static int show(CommandSourceStack source, Group group) {
        // 调用参数元数据逐项反馈，查询内容与指令允许设置的内容一致。
        for (Parameter parameter : Parameter.values()) {
            if (parameter.supports(group)) { showParameter(source, group, parameter); }
        }
        return 1;
    }

    /** 展示值、范围、默认值和是否临时覆盖，避免把配置默认误报为命令覆盖。 */
    private static int showParameter(CommandSourceStack source, Group group, Parameter parameter) {
        // 调用覆盖快照和画质读取入口，未设置的开关尊重现有 TOML。
        boolean overridden = RVP_LightRuntimeTuning.overrides().getOrDefault(group, Map.of()).containsKey(parameter);
        float value = parameter == Parameter.enabled
                ? ((group == Group.global ? RVP_LightingConfig.screenEnabled() : RVP_LightingConfig.bloomEnabled()) ? 1 : 0)
                : RVP_LightRuntimeTuning.get(group, parameter);
        send(source, group + "." + parameter + "=" + value + (overridden ? "（临时覆盖）" : "（沿用默认）")
                + "，默认=" + (parameter == Parameter.enabled ? "客户端配置" : parameter.defaultValue(group))
                + "，范围=" + parameter.min + "～" + parameter.max + "；" + parameter.description);
        return 1;
    }

    /** 复制可重放的覆盖清单；不创建文件，也不把临时值保存进用户画质配置。 */
    private static int copy(CommandSourceStack source) {
        // 调用不可变快照，只导出本次确实设置过的参数，顺序固定方便比较。
        var values = RVP_LightRuntimeTuning.overrides();
        StringBuilder commands = new StringBuilder("/rvplight reset");
        for (Group group : Group.values()) {
            for (Parameter parameter : Parameter.values()) {
                Float value = values.getOrDefault(group, Map.of()).get(parameter);
                if (value != null) {
                    commands.append('\n').append("/rvplight set ").append(group).append(' ')
                            .append(parameter).append(' ').append(value);
                }
            }
        }
        Minecraft.getInstance().keyboardHandler.setClipboard(commands.toString());
        send(source, "已将恢复当前调参的指令复制到剪贴板；可保存到笔记，下次进游戏逐条执行。");
        return 1;
    }

    /** 统一中文反馈；客户端命令不向服务器广播调试值。 */
    private static void send(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal("[RVP Light] " + message), false);
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
                + "，plumes=" + RVP_RocketPlumeManager.INSTANCE.size()
                + "，体积尾烟段=" + RVP_RocketTrailManager.INSTANCE.size()
                + "，环境光总倍率=" + RVP_LightingConfig.intensity()
                + "，观察距离=" + RVP_LightingConfig.maxDistance() + "格"
                + "，调参覆盖组数=" + RVP_LightRuntimeTuning.overrides().size()
                + "；" + (reason.isEmpty() ? RVP_ScreenLightPass.frameStatus() : reason)), false);
        mc.player.displayClientMessage(Component.literal("[RVP Light] /rvplight help 查看调参用法；status <组> 查看参数。"), false);
        return 1;
    }

    /** 在准星表面外侧生成1.6秒无伤害爆炸类橙光，便于观察地面、墙面和独立光晕。 */
    private static int preview(float radius) {
        // 调用共享预览入口，原有命令继续验证普通表面光。
        return preview(radius, false);
    }

    /** engine 模式用真实代码预设显示两秒喷口核心和橙色光晕，朝天空也可验证。 */
    private static int preview(float radius, boolean engine) {
        // 调用相同预览程序，默认喷流尺寸沿用未配 flame_scale 时的 0.3。
        return preview(radius, engine, org.ywzj.rvp.light.RVP_RocketPlumeParameters.DEFAULT_SCALE);
    }

    /** 缩放预览只改变喷焰几何；会话调参由正式采样和渲染入口统一应用。 */
    private static int preview(float radius, boolean engine, float scale) {
        // 调用毫米口径预览的基础范围，旧命令参数仍只表示喷焰几何缩放。
        return preview(radius, engine, scale, 0F);
    }

    /** 口径提供两种范围的基础值，各自倍率独立应用；缩放仍只改变喷焰本身长宽。 */
    private static int preview(float radius, boolean engine, float scale, float caliber) {
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
        // 调用真实代码预设：发动机只把预览延长到两秒，爆炸沿用正式1.6秒和0.3衰减。
        RVP_LightParameters preset = engine ? RVP_LightParameters.engine(caliber) : RVP_LightParameters.explosion(4);
        RVP_LightParameters parameters = engine
                ? new RVP_LightParameters(true, preset.red(), preset.green(), preset.blue(), preset.radius(),
                        preset.intensity(), 40, 0, preset.surfaceWeight(), preset.decay(), preset.bloom(), 0, 0)
                : new RVP_LightParameters(true, preset.red(), preset.green(), preset.blue(), radius, preset.intensity(),
                        preset.durationTicks(), 0, preset.surfaceWeight(), preset.decay(), preset.bloom(), 0, 0);
        RVP_ScreenLightManager.INSTANCE.addTransient(position,
                engine ? RVP_LightSourceType.ROCKET_ENGINE : RVP_LightSourceType.EXPLOSION, null,
                parameters, mc.level.getGameTime());
        if (engine) {
            // 调用正式喷流管理器，侧向展示完整形状，实际导弹则沿弹体局部尾轴喷出。
            Vec3 direction = mc.player.getLookAngle().cross(new Vec3(0, 1, 0)).normalize();
            if (direction.lengthSqr() < 0.01) { direction = new Vec3(1, 0, 0); }
            RVP_RocketPlumeManager.INSTANCE.beginWorld(mc.level, mc.level.getGameTime());
            RVP_RocketPlumeManager.INSTANCE.upsert(java.util.UUID.randomUUID(), position, direction, scale, caliber, 1, true);
            // 调用两种独立范围规则，提示照亮半径和光晕半径，避免把分离后的结果说成同一个值。
            mc.player.displayClientMessage(Component.literal("[RVP Light] 发动机预览：口径 " + caliber
                    + " mm，照亮半径 " + RVP_LightRuntimeTuning.radius(Group.engine, preset.radius())
                    + " 格，光晕半径 " + RVP_LightRuntimeTuning.haloRadius(Group.engine, preset.radius())
                    + " 格，喷焰缩放 " + scale), false);
        }
        return 1;
    }
}
