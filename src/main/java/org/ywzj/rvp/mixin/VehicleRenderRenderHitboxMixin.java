package org.ywzj.rvp.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.client.util.RVP_DamagedPartWireframeHelper;
import org.ywzj.vehicle.client.render.util.OBBRenderer;
import org.ywzj.vehicle.client.render.entity.vehicle.VehicleRender;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.all.AllKeys;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.DecorationUnit;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.structure.OBB;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeOBB;

import java.util.ArrayList;
import java.util.List;

/**
 * F3+B / O 键 OBB 调试线框：失效模块骨（爆反/设备/炮管）cube 画**近黑色**（2026-09-29
 * 用户需求，已审批的新 client Mixin）。
 *
 * <p><b>注入方式</b>：{@code VehicleRender.renderHitbox} 是 static 方法不可覆写，颜色为
 * 调用点硬编码字面量（车体绿/武器红/部件黄）且无事件钩子——逐 OBB 变色必须接管绘制。
 * {@code @Inject(HEAD, cancellable=true)}：仅 RVP 载具且存在失效模块骨时 {@code ci.cancel()}
 * 并按本体原逻辑复刻绘制（依赖全部 public：OBBRenderer.INSTANCE / getMainCubeOBB /
 * getVehicleCubeOBBs / getPartUnits / getOBBs——与本体原方法逐行同构），失效骨 cube 传
 * 近黑 RGB，其余照原色；其它 mod 载具/无失效时透传本体原路径，零影响。</p>
 *
 * <p><b>带毒纪律</b>：本 Mixin 在 mixins.json 的 <b>client 数组</b>（目标类纯客户端，
 * 不触发服务端字节码重算）；不触碰 AbstractVehicle/WeaponUnit 等带毒类（全部 public API
 * 直调，零 accessor）。</p>
 */
@Mixin(value = VehicleRender.class, remap = false)
public class VehicleRenderRenderHitboxMixin {

    @Inject(method = "renderHitbox", at = @At("HEAD"), cancellable = true, remap = false)
    private static void ywzj_rvp$renderHitboxWithDamagedBoneColor(
            AbstractVehicle vehicle, PoseStack poseStack, MultiBufferSource bufferSource, CallbackInfo ci) {
        // 快速门控：非 RVP 载具或无失效模块骨 → 透传本体原路径（零影响）
        var vehicleId = vehicle.getVehicleId();
        if (vehicleId == null || !"rvp".equals(vehicleId.getNamespace())) {
            return;
        }
        if (!RVP_DamagedPartWireframeHelper.hasDestroyedBone(vehicle)) {
            return;
        }

        VertexConsumer buffer = bufferSource.getBuffer(RenderType.lines());
        EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        boolean renderSelfVehicleHitbox = vehicle == LocalVehiclePlayer.instance.vehicle && AllKeys.INSPECT_VEHICLE.isDown();
        List<PartUnit<?>> partUnits = new ArrayList<>();
        if (dispatcher.shouldRenderHitBoxes() || renderSelfVehicleHitbox) {
            // 物理框（蓝）——本体原色
            if (vehicle.getMainCubeOBB() != null) {
                OBBRenderer.INSTANCE.render(vehicle.position(),
                        List.of(vehicle.getMainCubeOBB().obb()),
                        poseStack, buffer, 0, 0, 1, 1);
            }
            // 车体框（绿）——本体原色
            for (VehicleCubeOBB vehicleCubeOBB : vehicle.getVehicleCubeOBBs()) {
                OBBRenderer.INSTANCE.render(vehicle.position(),
                        List.of(vehicleCubeOBB.obb()),
                        poseStack, buffer, 0, 1, 0, 1);
            }
            if (vehicle instanceof FixedWingVehicle fixedWingVehicle
                    && fixedWingVehicle.aerodynamicCubeOBB != null) {
                // 气动框（青）——本体原色
                OBBRenderer.INSTANCE.render(vehicle.position(),
                        List.of(fixedWingVehicle.aerodynamicCubeOBB.obb()),
                        poseStack, buffer, 0, 1, 1, 1);
            }
            partUnits.addAll(vehicle.getPartUnits());
            partUnits.addAll(vehicle.getDecorationUnits().values());
        } else {
            PartUnit<?> partUnit = LocalVehiclePlayer.instance.lookAtPartUnit;
            if (AllKeys.INSPECT_VEHICLE.isDown() && partUnit != null) {
                partUnits.add(partUnit);
            }
        }
        for (PartUnit<?> partUnit : partUnits) {
            if (partUnit.isDetached()) {
                continue;
            }
            // [RVP] 按 cube 拆分绘制：失效模块骨的 cube 画近黑，其余保持本体原色
            //（WeaponUnit/DecorationUnit/其他部件三分支与本体原方法逐行同构，仅拆 List<OBB>）
            List<OBB> normalObbs = new ArrayList<>();
            List<OBB> damagedObbs = new ArrayList<>();
            for (VehicleCubeOBB cube : partUnit.getPartCubeOBBs()) {
                if (RVP_DamagedPartWireframeHelper.isDamagedPartCube(vehicle, partUnit, cube)) {
                    damagedObbs.add(cube.obb());
                } else {
                    normalObbs.add(cube.obb());
                }
            }
            float r = RVP_DamagedPartWireframeHelper.DAMAGED_RGB;
            float g = RVP_DamagedPartWireframeHelper.DAMAGED_RGB;
            float b = RVP_DamagedPartWireframeHelper.DAMAGED_RGB;
            if (partUnit instanceof WeaponUnit) {
                if (!normalObbs.isEmpty()) {
                    OBBRenderer.INSTANCE.render(vehicle.position(), normalObbs, poseStack, buffer, 1, 0, 0, 1);
                }
                if (!damagedObbs.isEmpty()) {
                    OBBRenderer.INSTANCE.render(vehicle.position(), damagedObbs, poseStack, buffer, r, g, b, 1);
                }
            } else if (partUnit instanceof DecorationUnit) {
                if (!normalObbs.isEmpty()) {
                    OBBRenderer.INSTANCE.render(vehicle.position(), normalObbs, poseStack, buffer, 1, 0, 1, 1);
                }
                if (!damagedObbs.isEmpty()) {
                    OBBRenderer.INSTANCE.render(vehicle.position(), damagedObbs, poseStack, buffer, r, g, b, 1);
                }
            } else {
                if (!normalObbs.isEmpty()) {
                    OBBRenderer.INSTANCE.render(vehicle.position(), normalObbs, poseStack, buffer, 1, 1, 0, 1);
                }
                if (!damagedObbs.isEmpty()) {
                    OBBRenderer.INSTANCE.render(vehicle.position(), damagedObbs, poseStack, buffer, r, g, b, 1);
                }
            }
        }
        ci.cancel();
    }
}
