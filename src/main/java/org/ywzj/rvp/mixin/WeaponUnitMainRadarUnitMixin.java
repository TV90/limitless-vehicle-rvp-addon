package org.ywzj.rvp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.ywzj.rvp.radar.RVP_RadarRoleHelper;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * 本体 {@link WeaponUnit#getMainRadarUnit()} 固定返回 {@code radarUnits.get(0)}。
 * 多雷达载具（如 cssa5/ps1）的 {@code sub_part_unit_ids} 是 ["scan_radar", "lock_radar"]，
 * 第 0 个是 search 搜索雷达；而 RVP 角色路由把锁定放到 fire_control 火控雷达上。
 * 本体依赖 getMainRadarUnit() 的链路（服务端 RADAR_LOCK 告警、ClientRadarAction
 * LOCK/DETECT 处理、半主动制导、本体观瞄锁定框）因此读不到火控雷达上的锁定：
 * 表现为被锁目标不触发锁定告警、本体锁定框不显示。
 *
 * <p>本 mixin 把主雷达重定向为实际承担锁定的雷达（优先 fire_control），使本体链路
 * 与 RVP 角色路由一致：先返回"自身已带锁定"的雷达（可能是回退落到搜索雷达上的锁），
 * 再退回角色解析出的火控雷达；无非搜索可锁雷达时退回本体行为（第 0 个雷达）。</p>
 */
@Mixin(value = WeaponUnit.class, remap = false)
public abstract class WeaponUnitMainRadarUnitMixin {

    @Inject(method = "getMainRadarUnit", at = @At("HEAD"), cancellable = true, remap = false)
    private void rvp$preferLockRadarAsMainRadar(CallbackInfoReturnable<RadarUnit> cir) {
        WeaponUnit self = (WeaponUnit) (Object) this;
        RadarUnit locked = RVP_RadarRoleHelper.getLockedRadar(self);
        if (locked != null && locked.getLockedEntity() != null) {
            cir.setReturnValue(locked);
            return;
        }
        RadarUnit preferred = RVP_RadarRoleHelper.getPreferredLockRadar(self);
        if (preferred != null) {
            cir.setReturnValue(preferred);
        }
    }
}
