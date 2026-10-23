package org.ywzj.rvp.mixin;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.ywzj.vehicle.client.gui.VehicleStatusOverlay;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeOBB;

import java.util.List;

/**
 * RVP 载具机身朝向图：失效模块骨近黑描边（2026-09-29 用户需求）。
 *
 * <p>2026-10-23 适配本体 27552d7：原 {@code VehicleScopeOverlay.renderCubeOBB} 逐 cube
 * 注入点随 renderVehicleHeading 一并迁入 {@code VehicleStatusOverlay}，且本体把逐 cube
 * 绘制重构为 {@code addHeadingCube} 收集 + {@code renderHeadingCube} 批绘（含自动缩放），
 * 原 @Inject(renderCubeOBB) 锚点消失导致 APPLY 崩溃。现改为 @Redirect 包裹
 * {@code addHeadingCube}（@Shadow 调原实现）：失效模块所属 cube 将描边色改为近黑、并清零
 * 填充（fillColor=0 ⇒ 描边用 color 且无填充），复刻旧版"近黑线框"观感；cube 位置/旋转
 * 数学全部沿用本体新实现（lerpZRotDiff 逐 cube 旋转已原生覆盖旧修正意图）。
 * 非失效 cube 与非 rvp 载具原样透传。</p>
 *
 * <p>类名沿用历史（VehicleScopeOverlay 时代），锚点已迁至 VehicleStatusOverlay。</p>
 */
@Mixin(value = VehicleStatusOverlay.class, remap = false)
public class VehicleScopeOverlayHeadingFixMixin {

    @Shadow
    private static void addHeadingCube(List cubes, VehicleCubeOBB cube, Vec3 vehiclePos,
                                       Vector3f axisX, Vector3f axisZ, float heading, float rotation,
                                       float partialTick, int color, int fillColor) {
        throw new AssertionError();
    }

    @Redirect(method = "renderVehicleHeading", remap = false, at = @At(value = "INVOKE", remap = false,
            target = "Lorg/ywzj/vehicle/client/gui/VehicleStatusOverlay;addHeadingCube(Ljava/util/List;Lorg/ywzj/vehicle/vehicle/structure/VehicleCubeOBB;Lnet/minecraft/world/phys/Vec3;Lorg/joml/Vector3f;Lorg/joml/Vector3f;FFFII)V"))
    private static void ywzj_rvp$damagedCubeNearBlackOutline(
            List cubes,
            VehicleCubeOBB cube,
            Vec3 vehiclePos,
            Vector3f axisX,
            Vector3f axisZ,
            float heading,
            float rotation,
            float partialTick,
            int color,
            int fillColor) {
        AbstractVehicle vehicle = LocalVehiclePlayer.instance == null ? null : LocalVehiclePlayer.instance.vehicle;
        PartUnit<?> ownerPart = null;
        if (vehicle != null) {
            ResourceLocation vehicleId = vehicle.getVehicleId();
            if (vehicleId != null && "rvp".equals(vehicleId.getNamespace())) {
                ownerPart = CUBE_PART_CACHE.resolve(vehicle, cube);
            }
        }
        if (ownerPart != null && org.ywzj.rvp.client.util.RVP_DamagedPartWireframeHelper
                .isDamagedPartCube(vehicle, ownerPart, cube)) {
            int c = (int) (org.ywzj.rvp.client.util.RVP_DamagedPartWireframeHelper.DAMAGED_RGB * 255f);
            color = 0xFF000000 | (c << 16) | (c << 8) | c;
            fillColor = 0;
        }
        addHeadingCube(cubes, cube, vehiclePos, axisX, axisZ, heading, rotation, partialTick, color, fillColor);
    }

    /** 单帧 cube→部件 反查缓存：键=载具，值=[帧号, cube, 部件]（俯视图每帧自上而下顺序绘制）。 */
    private static final org.ywzj.rvp.client.util.RVP_ScopeCubePartResolver
            CUBE_PART_CACHE = new org.ywzj.rvp.client.util.RVP_ScopeCubePartResolver();
}
