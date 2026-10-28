package org.ywzj.rvp.mixin;

import net.minecraft.resources.ResourceLocation;
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
import java.util.Map;

/**
 * RVP 载具机身朝向图：失效模块骨近黑描边（2026-09-29 用户需求）。
 *
 * <p><b>2026-10-28 二次迁移（本体 bfdd69a5）：</b>本体把 {@code addHeadingCube} 的几何参数
 * 从「{@code Vec3 vehiclePos + axisX/axisZ + rotation + color/fillColor}」整体换成
 * 「{@code Map<VehicleCubeGroup, Quaternionf> rotations + heading + partialTick + fillColor}」——
 * 逐 cube 的旋转改由结构组旋转表 + {@code headingTransform/headingYRot} 推导，
 * 描边色也不再是入参，而由 {@code fillColor} 派生（{@code borderColor = fillColor == 0 ?
 * Color.WHITE : fillColor | 0xFF000000}）。原 @Redirect 的旧描述符已失配，重写为：</p>
 *
 * <ul>
 *   <li><b>失效模块近黑线框</b>：把 {@code fillColor} 换成「alpha=0 的近黑 RGB」
 *       （{@code 0x00000000 | DAMAGED_RGB}）——描边因此取到近黑、填充 alpha 为 0（透明），
 *       精确复刻旧版"近黑线框、不填充"的观感。</li>
 *   <li><b>竖直薄板修正：删除</b>。旧修正是为了绕开「本体用 cube 局部 +Z 轴 yaw + 立起前的 depth
 *       近似俯视投影」的退化；新版本体改为按结构组四元数整体变换（{@code rotations} 表），
 *       cube 的 x/y/rotation 全部由真实旋转推导，旧退化条件不复存在，继续篡改 {@code cube.depth}
 *       反而会污染本体几何。</li>
 * </ul>
 *
 * <p>类名沿用历史（VehicleScopeOverlay 时代），锚点早已在 VehicleStatusOverlay。</p>
 */
@Mixin(value = VehicleStatusOverlay.class, remap = false)
public class VehicleScopeOverlayHeadingFixMixin {

    @Shadow
    private static void addHeadingCube(List cubes, VehicleCubeOBB cube, Map rotations,
                                       float heading, float partialTick, int fillColor) {
        throw new AssertionError();
    }

    @Redirect(method = "renderVehicleHeading", remap = false, at = @At(value = "INVOKE", remap = false,
            target = "Lorg/ywzj/vehicle/client/gui/VehicleStatusOverlay;addHeadingCube(Ljava/util/List;Lorg/ywzj/vehicle/vehicle/structure/VehicleCubeOBB;Ljava/util/Map;FFI)V"))
    private static void ywzj_rvp$damagedCubeNearBlackOutline(
            List cubes, VehicleCubeOBB cube, Map rotations, float heading, float partialTick, int fillColor) {
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
            int rgb = (int) (org.ywzj.rvp.client.util.RVP_DamagedPartWireframeHelper.DAMAGED_RGB * 255f);
            // 只带 RGB、alpha 留 0：本体 borderColor = fillColor | 0xFF000000 ⇒ 描边近黑；
            // 填充直接用 fillColor（alpha=0）⇒ 视觉透明，保持线框观感。
            fillColor = (rgb << 16) | (rgb << 8) | rgb;
        }
        addHeadingCube(cubes, cube, rotations, heading, partialTick, fillColor);
    }

    /** 单帧 cube→部件 反查缓存：键=载具，值=[帧号, cube, 部件]（俯视图每帧自上而下顺序绘制）。 */
    private static final org.ywzj.rvp.client.util.RVP_ScopeCubePartResolver
            CUBE_PART_CACHE = new org.ywzj.rvp.client.util.RVP_ScopeCubePartResolver();
}
