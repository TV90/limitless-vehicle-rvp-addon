package org.ywzj.rvp.mixin;

import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.ywzj.rvp.ext.WeaponUnitDataExt;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeGroup;

/**
 * 开镜相机挂点跟随炮管俯仰（{@code rvp_optical_sight_follow_pitch}，默认 false）。
 *
 * <p>语义：观瞄类型 {@code crt_ui_operator}（解析期语法糖，见
 * {@code PartUnitTypeScopeCompatMixin}）展开后类型落 {@code crt}（HUD/滤镜天然正确），
 * 由本 Mixin 把"开镜相机挂点"从本体默认的"只随偏航骨（structureGroup）"改回
 * "随俯仰骨（xTurnGroup）"——即本体 {@code operator} 类型的位置行为。</p>
 *
 * <p>实现：注入本体 {@code WeaponUnit.worldOpticalSightPosition} 的 HEAD（cancellable），
 * 开关开启且 {@code opticalSightOffset} 非空时，用
 * {@code worldPositionWithGroupRot(getPivotOffset().add(opticalSightOffset), xTurnGroup, partialTick)}
 * 返回——这一行就是本体给 {@code operator} 用的算法（本体 {@code WeaponUnit.java:1004}），
 * 本 Mixin 只是把它从"依赖 {@code optical_sight_type == OPERATOR}"中独立出来。
 * {@code getPivotOffset()} / {@code worldPositionWithGroupRot(...)} 均为本体 public 访问器，
 * {@code xTurnGroup} / {@code opticalSightOffset} 用 {@code @Shadow}。</p>
 *
 * <p>边界：不碰渲染、不碰 OBB/命中箱、不碰发射方向、不碰座位
 * （{@code worldSeatPosition} 走 {@code worldPositionWithSelfRot}，不经本方法）。</p>
 *
 * <p><b>与 {@code rvp_optical_sight_pivot} 互斥</b>（见 {@link WeaponUnitOpticalSightPivotMixin}）：
 * 两者注入同一方法 HEAD 且均 cancellable，Mixin 不会因前一个 cancel 而跳过后续注入，
 * 本 Mixin 首行以 {@code cir.isCancelled()} 先到先得——但<b>同一武器站不要同时配置这两个字段</b>；
 * 若同时配置，最终行为取决于 Mixin 注册顺序，不作保证。</p>
 */
@Mixin(value = WeaponUnit.class, remap = false)
public class WeaponUnitOpticalSightFollowPitchMixin {

    @Shadow
    private Vec3 opticalSightOffset;

    @Shadow
    private VehicleCubeGroup xTurnGroup;

    @Inject(method = "worldOpticalSightPosition", at = @At("HEAD"), cancellable = true, remap = false)
    private void ywzj_rvp$applyFollowPitch(float partialTick, CallbackInfoReturnable<Vec3> cir) {
        // 与 rvp_optical_sight_pivot 撞同一个注入点：已被取消（先到先得）则直接返回
        if (cir.isCancelled()) {
            return;
        }
        WeaponUnit self = (WeaponUnit) (Object) this;
        // 开关未开启（默认 false）→ 完全交还本体原逻辑，存量载具行为零变化
        if (!(self.getData() instanceof WeaponUnitDataExt ext) || !ext.ywzj_rvp$opticalSightFollowPitch()) {
            return;
        }
        // 无观瞄偏移 → 交给本体走 worldOwnerViewPosition 分支
        if (opticalSightOffset == null) {
            return;
        }
        // 本体 operator 的位置算法（WeaponUnit.java:1004）：随俯仰旋转组 xTurnGroup
        Vec3 offsetFromVehicle = self.getPivotOffset().add(opticalSightOffset);
        cir.setReturnValue(self.worldPositionWithGroupRot(offsetFromVehicle, xTurnGroup, partialTick));
    }
}
