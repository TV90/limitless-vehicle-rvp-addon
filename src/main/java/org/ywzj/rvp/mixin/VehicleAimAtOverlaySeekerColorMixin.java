package org.ywzj.rvp.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.GuiGraphics;
import org.ywzj.rvp.client.gui.RVP_ScopeOverlay;
import org.ywzj.rvp.util.RVP_WeaponResolveHelper;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.vehicle.client.gui.VehicleAimAtOverlay;
import org.ywzj.vehicle.client.gui.VehicleScopeOverlay;
import org.ywzj.vehicle.client.render.util.GuiHelper;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 武器级导引头圈颜色覆盖：武器 JSON 配置 {@code seeker_color} 后，
 * {@code VehicleAimAtOverlay.render} 中导引头块（IR 小圈 / RF 双圈 / 导引头大圈）
 * 的绘制颜色替换为配置色——未锁定显示配置色，锁定时统一红色指示
 * （对齐固定翼白→红语义；本体直升机绿无红通道，锁定后经红掩码会变黑）。
 *
 * <p>仅包装导引头块（位于编译产物 {@code lambda$render$2}，源码为 render 内
 * {@code getCurrentWeapon().ifPresent(...)} 的 lambda 体）的 4 个
 * {@code GuiHelper.drawCircle} 调用。未配置 {@code seeker_color} 的武器完全走
 * 本体原行为。client 数组：只碰绘制入参，无服务端风险。</p>
 */
@Mixin(value = VehicleAimAtOverlay.class, remap = false)
public class VehicleAimAtOverlaySeekerColorMixin {

    /** ordinal 1：IR 导引头小圈 */
    @WrapOperation(method = "lambda$render$2", remap = false, at = @At(value = "INVOKE", remap = false,
            target = "Lorg/ywzj/vehicle/client/render/util/GuiHelper;drawCircle(Lcom/mojang/blaze3d/vertex/PoseStack;FFFIFFF)V",
            ordinal = 0))
    private void rvp$seekerCircleIr(PoseStack poseStack, float x, float y, float radius, int color,
                                    float thickness, float start, float end, Operation<Void> original) {
        original.call(poseStack, x, y, radius, rvp$resolveSeekerColor(color), thickness, start, end);
    }

    /** ordinal 2：RF 导引头内圈 */
    @WrapOperation(method = "lambda$render$2", remap = false, at = @At(value = "INVOKE", remap = false,
            target = "Lorg/ywzj/vehicle/client/render/util/GuiHelper;drawCircle(Lcom/mojang/blaze3d/vertex/PoseStack;FFFIFFF)V",
            ordinal = 1))
    private void rvp$seekerCircleRfInner(PoseStack poseStack, float x, float y, float radius, int color,
                                         float thickness, float start, float end, Operation<Void> original) {
        original.call(poseStack, x, y, radius, rvp$resolveSeekerColor(color), thickness, start, end);
    }

    /** ordinal 3：RF 导引头外圈 */
    @WrapOperation(method = "lambda$render$2", remap = false, at = @At(value = "INVOKE", remap = false,
            target = "Lorg/ywzj/vehicle/client/render/util/GuiHelper;drawCircle(Lcom/mojang/blaze3d/vertex/PoseStack;FFFIFFF)V",
            ordinal = 2))
    private void rvp$seekerCircleRfOuter(PoseStack poseStack, float x, float y, float radius, int color,
                                         float thickness, float start, float end, Operation<Void> original) {
        original.call(poseStack, x, y, radius, rvp$resolveSeekerColor(color), thickness, start, end);
    }

    /** ordinal 4：导引头大圈（锁定后仍绘制的那一圈，黑色问题的主要来源） */
    @WrapOperation(method = "lambda$render$2", remap = false, at = @At(value = "INVOKE", remap = false,
            target = "Lorg/ywzj/vehicle/client/render/util/GuiHelper;drawCircle(Lcom/mojang/blaze3d/vertex/PoseStack;FFFIFFF)V",
            ordinal = 3))
    private void rvp$seekerCircleBig(PoseStack poseStack, float x, float y, float radius, int color,
                                     float thickness, float start, float end, Operation<Void> original) {
        original.call(poseStack, x, y, radius, rvp$resolveSeekerColor(color), thickness, start, end);
    }

    /**
     * 解析导引头圈最终颜色。
     *
     * <p>当前操作武器站的当前武器为 RVP 武器且配置了 {@code seeker_color} 时：
     * 未锁定返回配置色、锁定返回红色；否则原样透传本体传入色。</p>
     */
    @Unique
    private static int rvp$resolveSeekerColor(int incomingColor) {
        LocalVehiclePlayer localVehiclePlayer = LocalVehiclePlayer.instance;
        if (localVehiclePlayer == null || !localVehiclePlayer.onVehicle()) {
            return incomingColor;
        }
        WeaponUnit weaponUnit = localVehiclePlayer.getWeaponUnit();
        if (weaponUnit == null) {
            return incomingColor;
        }
        AbstractVehicleWeapon<?> weapon = RVP_WeaponResolveHelper.currentPrimary(weaponUnit);
        if (!(weapon instanceof RVP_WeaponBase rvpWeapon)) {
            return incomingColor;
        }
        Integer rgb = rvpWeapon.getData().getSeekerColorRgb();
        if (rgb == null) {
            return incomingColor;
        }
        boolean locked = weaponUnit.getLockedEntity() != null;
        // 保留本体计算的透明度通道（含冷却脉动）
        int alpha = incomingColor & 0xFF000000;
        if (locked) {
            // 锁定统一红色指示
            return 0x00FF0000 | alpha;
        }
        return (rgb & 0x00FFFFFF) | alpha;
    }

    /**
     * 武器级 {@code parent_weapon_unit_aim} 覆盖（准心/弹着点显示锚定方向）：
     * render 中按当前操作武器站选中的 RVP 武器的 {@code parent_weapon_unit_aim_override}
     * 决定准心跟随母武器站还是自身挂架；未配置回退本体静态值。
     * 处理器经 {@code LocalVehiclePlayer} 取操作武器站——与本 overlay 渲染的载具同源。
     */
    @Redirect(method = "lambda$render$2", remap = false,
            at = @At(value = "INVOKE", remap = false,
                    target = "Lorg/ywzj/vehicle/vehicle/part/WeaponUnit;isParentWeaponUnitAim()Z"))
    private boolean rvp$parentAimOverrideForOverlay(WeaponUnit aimingWeaponUnit) {
        LocalVehiclePlayer localVehiclePlayer = LocalVehiclePlayer.instance;
        if (localVehiclePlayer != null && localVehiclePlayer.onVehicle()) {
            WeaponUnit operatedUnit = localVehiclePlayer.getWeaponUnit();
            if (operatedUnit != null) {
                Boolean override = RVP_WeaponResolveHelper.resolveParentWeaponUnitAimOverride(
                        RVP_WeaponResolveHelper.currentPrimary(operatedUnit));
                if (override != null) {
                    return override;
                }
            }
        }
        return aimingWeaponUnit.isParentWeaponUnitAim();
    }

    /**
     * [RVP] HUD 锁定目标框接管：本体 VehicleAimAtOverlay（HUD 视角）会调用本体的
     * renderAimLockTarget 画锁定框，而 rvp_scope overlay 的非观瞄分支对同一目标
     * 也画一套 BVR 框/导引头圈——两套渲染器锚点不同（本体 bbox 中心、RVP 探测记录
     * 位置），近距 visibly 分离为"两个近乎重叠的框"。
     *
     * <p>处理：RVP 接管门控命中（RF+无光学瞄、或存在外置雷达条目）时跳过本体绘制，
     * 由 RVP_ScopeOverlay.renderAimLockTarget 统一绘制（额外含 NCTR 目标名与外置
     * 雷达框）；门控不命中（如 j15 观瞄 crt、机炮无传感器）保持本体原行为。
     * 本 Mixin 位于 client 数组、目标为纯客户端 HUD 类，服务端零加载。</p>
     */
    @Redirect(method = "render", remap = false, at = @At(value = "INVOKE", remap = false,
            target = "Lorg/ywzj/vehicle/client/gui/VehicleScopeOverlay;renderAimLockTarget(Lnet/minecraft/client/gui/GuiGraphics;F)V"))
    private void rvp$takeoverHudLockTarget(net.minecraft.client.gui.GuiGraphics guiGraphics, float partialTick) {
        if (!RVP_ScopeOverlay.hudLockTakeoverActive()) {
            VehicleScopeOverlay.renderAimLockTarget(guiGraphics, partialTick);
        }
    }

    /**
     * [RVP] 武器级准星样式覆盖（{@code crosshair_style_override}）：当前选中武器为配置了
     * 该字段的 RVP 武器时，HUD 准星样式用武器 JSON 的覆盖值替换所属武器站的
     * {@code crosshair_style}——适配"同一武器站内不同挂架武器需要不同准星"。
     * 未配置该字段的武器（含本体武器）原样透传站级样式。
     */
    @WrapOperation(method = "render", remap = false, at = @At(value = "FIELD", remap = false,
            target = "Lorg/ywzj/vehicle/vehicle/part/WeaponUnit;crosshairStyle:Lorg/ywzj/vehicle/custom/part/data/WeaponUnitData$CrosshairStyle;"))
    private WeaponUnitData.CrosshairStyle rvp$crosshairStyleOverride(WeaponUnit aimingWeaponUnit,
                                                                     Operation<WeaponUnitData.CrosshairStyle> original) {
        AbstractVehicleWeapon<?> weapon = RVP_WeaponResolveHelper.currentPrimary(aimingWeaponUnit);
        if (weapon instanceof RVP_WeaponBase rvpWeapon) {
            WeaponUnitData.CrosshairStyle override = rvpWeapon.getData().getCrosshairStyleOverride();
            if (override != null) {
                return override;
            }
        }
        return original.call(aimingWeaponUnit);
    }
}
