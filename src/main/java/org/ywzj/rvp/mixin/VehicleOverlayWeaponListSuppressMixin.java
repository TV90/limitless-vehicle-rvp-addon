package org.ywzj.rvp.mixin;

import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager;
import org.ywzj.vehicle.client.gui.VehicleOverlay;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * [RVP] 有人载具的武器改装列表隐藏（client 数组，仅客户端应用）。
 *
 * <p>背景：本体 {@code VehicleOverlay.renderWeaponList} 在准星命中可改装子武器站时绘制
 * 多弹种列表（改装选择 UI），不校验载具是否有乘员，且与手持物品无关——对已被玩家 /
 * gunner 乘员的载具依然展示"可改装"暗示，与 {@code RVP_ModdingInteractGuard} 的潜行右键
 * 动作拦截相矛盾（列表引诱改装、点击却被拦）。</p>
 *
 * <p>本 Mixin 在列表绘制前判定：载具有玩家 / gunner 乘员（{@code hasPilotOrGunnerPassenger}，
 * 与动作拦截共用同一"有人"语义源）即取消绘制，空手 / 手持任何物品均生效。
 * 调用点位于本体 {@code render()} 开头的 {@code renderLookAt} 内、骑乘判断之前，
 * 故步行 / 骑乘状态均生效，且仅取消列表这一块——骑乘时自身驾驶 HUD（乘员组 / 罗盘 /
 * 油量）与指向载具血条不受影响。</p>
 *
 * <p>合规说明（agents.md Mixin 纪律，2026-09-13 经用户批准）：目标 {@code VehicleOverlay}
 * 为本体纯客户端 GUI 类（非 {@code AbstractVehicle}/{@code WeaponUnit} 等带毒公共类），
 * 本 Mixin 仅注册于 client 数组、只在客户端应用，服务端不会触碰该类；
 * 仅最小 {@code @Inject(HEAD)} 转发取消，无共享状态。回退方式：从 client 数组移除本条目。</p>
 */
@Mixin(value = VehicleOverlay.class, remap = false)
public class VehicleOverlayWeaponListSuppressMixin {

    @Inject(method = "renderWeaponList", at = @At("HEAD"), cancellable = true, remap = false)
    private void ywzj_rvp$hideWeaponListForOccupiedVehicle(GuiGraphics guiGraphics, WeaponUnit weaponUnit, CallbackInfo ci) {
        if (weaponUnit == null || weaponUnit.getVehicle() == null) {
            return;
        }
        // 目的：有人（玩家/gunner 乘员）载具不可改装，列表一并隐藏；空手/持任何物品均生效，
        // 无人载具保持本体原行为（列表照常显示）
        if (RVP_VehicleExtendedConfigManager.INSTANCE.hasPilotOrGunnerPassenger(weaponUnit.getVehicle())) {
            ci.cancel();
        }
    }
}
