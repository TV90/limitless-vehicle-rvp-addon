package org.ywzj.rvp.mixin;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
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
        // 竖直薄板修正：本体 0.6.2 用「中心 + halfWidth/halfDepth + 单个 Y 角」近似 cube，
        // 其角度取 cube 局部 +Z 轴的 yaw；当 cube 绕 X 轴立起（如 99B 首上 ERA 的 -90° 薄板）时
        // +Z 轴变为竖直，VectorUtil.vecToRot 的 atan2(x,z) 在 (0,·,0) 上退化为 0，
        // 角度变成 -vehicleYaw（行进间随车体朝向反向漂移），且 depth 用的是立起前的值导致框过厚。
        // 这里仅在俯视投影退化时接管：厚度改用 height（X 轴 ±90° 时 y/z 互换），
        // 角度改用水平面的局部 +Y 轴求 yaw（该轴在立起后仍保持水平，数值稳定）。
        if (vehicle != null && ywzj_rvp$isVerticalPlate(cube)) {
            float correctedRotation = ywzj_rvp$resolveVerticalPlateRotation(cube, vehicle, partialTick);
            double originalDepth = cube.depth;
            cube.depth = cube.height;
            try {
                addHeadingCube(cubes, cube, vehiclePos, axisX, axisZ, heading, correctedRotation,
                        partialTick, color, fillColor);
            } finally {
                cube.depth = originalDepth;
            }
            return;
        }
        addHeadingCube(cubes, cube, vehiclePos, axisX, axisZ, heading, rotation, partialTick, color, fillColor);
    }

    /**
     * 判定该 cube 是否属于「俯视投影退化」的竖直薄板：
     * 模型坐标系下局部 +Z 轴（本体用来算 rotation 的轴）几乎竖直，说明 depth 轴已立起，
     * 俯视投影无法由 width/depth + 单个 Y 角正确表达。
     */
    @org.spongepowered.asm.mixin.Unique
    private static boolean ywzj_rvp$isVerticalPlate(VehicleCubeOBB cube) {
        org.joml.Vector3f zAxis = ywzj_rvp$selfAxis(cube, 0f, 0f, 1f);
        return zAxis != null && Math.abs(zAxis.y) > 0.9f;
    }

    /**
     * 计算竖直薄板在俯视图里的正确 Y 旋转角（度）：取模型坐标系局部 +Y 轴在世界中的方向，
     * 该轴在 cube 绕 X 轴 ±90° 立起后转成水平朝前的 depth 方向，yaw 稳定。
     */
    @org.spongepowered.asm.mixin.Unique
    private static float ywzj_rvp$resolveVerticalPlateRotation(VehicleCubeOBB cube, AbstractVehicle vehicle,
                                                              float partialTick) {
        Quaternionf rotation = cube.rotation;
        if (rotation == null) {
            return 0f;
        }
        Vector3f yAxis = new Vector3f(0f, 1f, 0f);
        rotation.transform(yAxis);
        Vec3 dir = new Vec3(yAxis.x, yAxis.y, yAxis.z);
        float yaw = org.ywzj.vehicle.util.VectorUtil.vecToRot(dir).y;
        // AbstractVehicle 只暴露无参 getYRot()；partialTick 插值对本面板无观感差异
        return Mth.wrapDegrees(yaw - vehicle.getYRot());
    }

    /** 取模型坐标系（不含车体朝向）下局部轴的朝向；旋转缺失时返回 null。 */
    @org.spongepowered.asm.mixin.Unique
    private static Vector3f ywzj_rvp$selfAxis(VehicleCubeOBB cube, float x, float y, float z) {
        if (cube.group == null) {
            return null;
        }
        Quaternionf selfRot = cube.selfRot();
        if (selfRot == null) {
            return null;
        }
        Vector3f axis = new Vector3f(x, y, z);
        return selfRot.transform(axis);
    }

    /** 单帧 cube→部件 反查缓存：键=载具，值=[帧号, cube, 部件]（俯视图每帧自上而下顺序绘制）。 */
    private static final org.ywzj.rvp.client.util.RVP_ScopeCubePartResolver
            CUBE_PART_CACHE = new org.ywzj.rvp.client.util.RVP_ScopeCubePartResolver();
}
