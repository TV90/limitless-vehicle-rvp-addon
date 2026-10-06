package org.ywzj.rvp.client.debug;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.visual.cookoff.RVP_WreckCookoffController;
import org.ywzj.rvp.client.visual.cookoff.RVP_WreckCookoffResolver;
import org.ywzj.rvp.client.visual.cookoff.RVP_WreckCookoffSettings;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.structure.OBB;

import java.util.List;
import java.util.function.DoubleConsumer;

/** 本地殉燃调试，无需修改车辆 JSON 或真正击毁载具。 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_WreckCookoffDebugCommands {
    private RVP_WreckCookoffDebugCommands() {}

    /** 注册会话调参和准星载具预览。 */
    @SubscribeEvent
    public static void register(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("rvpcookoff")
                .executes(context -> show(context.getSource()))
                .then(Commands.literal("show").executes(context -> show(context.getSource())))
                .then(Commands.literal("reset").executes(context -> {
                    // 调用本项目会话重置，不写入任何资产文件。
                    RVP_WreckCookoffSettings.reset();
                    return show(context.getSource());
                }))
                .then(Commands.literal("set")
                        // 调用本类参数节点构造器，限制长度和粒子倍率在安全范围。
                        .then(parameter("height", 1, 16, value -> RVP_WreckCookoffSettings.height = value))
                        .then(parameter("length", 0.5, 8, value -> RVP_WreckCookoffSettings.length = value))
                        .then(parameter("density", 0, 2, value -> RVP_WreckCookoffSettings.density = value))
                        .then(parameter("grow", 1, 120, value -> RVP_WreckCookoffSettings.growthTicks = value))
                        .then(parameter("shrink", 1, 120, value -> RVP_WreckCookoffSettings.shrinkTicks = value))
                        .then(parameter("roof_speed_min", 0.05, 20.0,
                                RVP_WreckCookoffSettings::setRoofAxisSpeedMin))
                        .then(parameter("roof_speed_max", 0.05, 20.0,
                                RVP_WreckCookoffSettings::setRoofAxisSpeedMax))
                        .then(parameter("roof_cone_base", 0.02, 1.5,
                                RVP_WreckCookoffSettings::setRoofConeBase))
                        .then(parameter("roof_cone_tip", 0.02, 2.5,
                                RVP_WreckCookoffSettings::setRoofConeTip))
                        .then(parameter("roof_outward", 0.0, 0.5,
                                value -> RVP_WreckCookoffSettings.roofOutwardSpeed = value))
                        .then(parameter("roof_scale_min", 0.1, 3.0,
                                RVP_WreckCookoffSettings::setRoofTextureScaleMin))
                        .then(parameter("roof_scale_max", 0.1, 3.0,
                                RVP_WreckCookoffSettings::setRoofTextureScaleMax))
                        .then(parameter("roof_vertical_offset",
                                RVP_WreckCookoffSettings.ROOF_VERTICAL_OFFSET_MIN,
                                RVP_WreckCookoffSettings.ROOF_VERTICAL_OFFSET_MAX,
                                RVP_WreckCookoffSettings::setRoofVerticalOffset))
                        .then(parameter("muzzle_smoke_density", 0.0, 2.0,
                                value -> RVP_WreckCookoffSettings.muzzleSmokeDensity = value))
                        .then(parameter("muzzle_smoke_size", 0.1, 3.0,
                                value -> RVP_WreckCookoffSettings.muzzleSmokeSize = value))
                        .then(parameter("muzzle_smoke_lifetime", 4.0, 120.0,
                                value -> RVP_WreckCookoffSettings.muzzleSmokeLifetime = value))
                        .then(parameter("muzzle_smoke_axis_speed", 0.0, 2.0,
                                value -> RVP_WreckCookoffSettings.muzzleSmokeAxisSpeed = value))
                        .then(parameter("muzzle_smoke_updraft", 0.0, 1.0,
                                value -> RVP_WreckCookoffSettings.muzzleSmokeUpdraft = value))
                        .then(parameter("muzzle_smoke_spread", 0.0, 0.25,
                                value -> RVP_WreckCookoffSettings.muzzleSmokeSpread = value))
                        .then(parameter("muzzle_smoke_alpha", 0.0, 1.0,
                                value -> RVP_WreckCookoffSettings.muzzleSmokeAlpha = value))
                        .then(parameter("muzzle_smoke_grey", 0.02, 0.5,
                                value -> RVP_WreckCookoffSettings.muzzleSmokeGrey = value)))
                .then(Commands.literal("preview")
                        .then(Commands.literal("start").executes(context -> preview(context.getSource())))
                        .then(Commands.literal("stop").executes(context -> {
                            // 调用本项目预览停止入口；不影响真实残骸的持续效果。
                            RVP_WreckCookoffController.stopPreview();
                            context.getSource().sendSuccess(() -> Component.literal("[RVP 殉燃] 已停止预览"), false);
                            return 1;
                        }))));
    }

    /** 每个可调参数都有独立范围，拒绝非有限数字。 */
    private static LiteralArgumentBuilder<CommandSourceStack> parameter(String name, double min, double max,
                                                                       DoubleConsumer setter) {
        return Commands.literal(name).then(Commands.argument("value", DoubleArgumentType.doubleArg(min, max))
                .executes(context -> {
                    double value = DoubleArgumentType.getDouble(context, "value");
                    if (!Double.isFinite(value)) return 0;
                    setter.accept(value);
                    // 调用本类状态显示，反馈实际生效的全局参数。
                    return show(context.getSource());
                }));
    }

    /** 展示活动实例与全局参数。 */
    private static int show(CommandSourceStack source) {
        // 调用本项目状态描述，使用户能核对预算而不依赖日志猜测。
        source.sendSuccess(() -> Component.literal("[RVP 殉燃] " + RVP_WreckCookoffController.describe()), false);
        source.sendSuccess(() -> Component.literal(
                "/rvpcookoff set height|length|density|grow|shrink|roof_speed_min|roof_speed_max|"
                        + "roof_cone_base|roof_cone_tip|roof_outward|roof_scale_min|roof_scale_max|"
                        + "roof_vertical_offset|muzzle_smoke_density|muzzle_smoke_size|"
                        + "muzzle_smoke_lifetime|muzzle_smoke_axis_speed|muzzle_smoke_updraft|"
                        + "muzzle_smoke_spread|muzzle_smoke_alpha|muzzle_smoke_grey"
                        + " <数值>；reset；preview start|stop"), false);
        return 1;
    }

    /** 沿准星 64 格寻找最近的真实地面载具，以其主 OBB 对应 AABB 为候选。 */
    private static int preview(CommandSourceStack source) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) return 0;
        Vec3 start = minecraft.player.getEyePosition();
        Vec3 end = start.add(minecraft.player.getLookAngle().scale(64));
        AbstractVehicle selected = null;
        double closest = Double.POSITIVE_INFINITY;
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            // 调用本项目类型门控，预览不支持飞机或脱落残件。
            if (!(entity instanceof AbstractVehicle vehicle) || vehicle.isRemoved()
                    || !RVP_WreckCookoffController.isGroundVehicle(vehicle)) continue;
            // 调用本体结构盒转换，以实际车身尺寸完成准星选取。
            var cube = vehicle.getMainCubeOBB();
            var bounds = cube == null ? vehicle.getBoundingBox() : OBB.toAABB(List.of(cube.obb()));
            var hit = bounds.inflate(0.25).clip(start, end);
            double distance = bounds.contains(start) ? 0 : hit.map(start::distanceToSqr).orElse(Double.POSITIVE_INFINITY);
            if (distance < closest) {
                closest = distance;
                selected = vehicle;
            }
        }
        if (selected == null) {
            source.sendFailure(Component.literal("请瞄准 64 格内的地面载具"));
            return 0;
        }
        // 调用自动挂点解析显示识别数量，再启动仅本地可见的 60 秒预览。
        var discovery = RVP_WreckCookoffResolver.discover(selected, true);
        long hatchSamples = discovery.anchors().stream()
                .filter(a -> a.kind() == RVP_WreckCookoffResolver.Kind.HATCH).count();
        long hatchColumns = discovery.anchors().stream()
                .filter(RVP_WreckCookoffResolver::isHatchColumnAnchor).count();
        String counts = "舱盖火柱=" + hatchColumns + "（火星采样点=" + hatchSamples + "），接缝采样点=" + discovery.anchors().stream()
                .filter(a -> a.kind() == RVP_WreckCookoffResolver.Kind.SEAM).count()
                + "，炮口灰烟=" + discovery.muzzles().size() + "（不喷火星）";
        RVP_WreckCookoffController.preview(selected);
        source.sendSuccess(() -> Component.literal("[RVP 殉燃] 预览 60 秒；" + counts + "；无独立舱盖时使用顶部近似位置"), false);
        return 1;
    }
}
