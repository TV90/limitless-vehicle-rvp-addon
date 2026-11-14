package org.ywzj.rvp.mixin;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.ywzj.rvp.util.RVP_WeaponResolveHelper;
import org.ywzj.rvp.vehicle.RVP_FcsState;
import org.ywzj.rvp.weapon.core.RVP_FocusLockSnapState;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

/**
 * 武器级火控覆盖（公共端）：
 * <ul>
 *   <li><b>传感器覆盖</b>：当前 RVP 武器数据带 {@code fire_control_sensor_type_override} 时，
 *   {@link WeaponUnit#getFireControlSensorType()} 直接返回覆盖值——站级静态传感器被"替换"
 *   而非与覆盖并存。使本体内部消费点（RF 自动锁 tick、IR/RF 导引头圈、锁定分支等）与
 *   RVP 消费点（{@code RVP_WeaponSensorHelper}）看到同一个有效传感器。</li>
 *   <li><b>FCS 失效</b>：稳定器在物理层真正关闭（本体 {@code tickRot} 直接读私有字段，
 *   见 {@link #rvp$fcsGateStabilizerField}）。</li>
 * </ul>
 *
 * <p>安全说明：handler 只引用公共类（RVP_WeaponBase / RVP_WeaponData / 本体枚举），
 * 无任何 @OnlyIn(CLIENT) 类型；WeaponUnit 在公共数组已有 3 个既有 mixin（改脏状态
 * 与帧重算行为不变）。若当前武器非 RVP 或未配置覆盖，不干预返回值。</p>
 */
@Mixin(value = WeaponUnit.class, remap = false)
public class WeaponUnitSensorOverrideMixin {

    @Inject(method = "getFireControlSensorType", at = @At("HEAD"), cancellable = true, remap = false)
    private void ywzj_rvp$overrideSensorTypeByCurrentWeapon(CallbackInfoReturnable<WeaponUnitData.FireControlSensorType> cir) {
        WeaponUnit self = (WeaponUnit) (Object) this;
        // 焦点吸附锁定期间按 EO 口径校验：观瞄站静态传感器常为 NONE（实际的 EO 来自
        // 武器 eo_ccip 动态语义），若返回 NONE 本体实体验证分支会每刻把刚建立的吸附锁清掉。
        if (RVP_FocusLockSnapState.isSnapped(self)) {
            cir.setReturnValue(WeaponUnitData.FireControlSensorType.EO);
            return;
        }
        // 必须经 currentPrimary 解包：当前武器可能是 VehicleMultiWeapons（二选一组，
        // 如 AH64 导弹站的 ir/arh 组）或 Agent 代理——二者均非 RVP_WeaponBase，
        // 不解包会导致覆盖判定失败、站级静态值泄漏（表现为两种传感器并存）。
        AbstractVehicleWeapon<?> weapon = RVP_WeaponResolveHelper.currentPrimary(self);
        if (!(weapon instanceof RVP_WeaponBase rvpWeapon)) {
            return;
        }
        RVP_WeaponData data = rvpWeapon.getData();
        WeaponUnitData.FireControlSensorType override = data.getFireControlSensorTypeOverride();
        if (override != null) {
            cir.setReturnValue(override);
        }
    }

    /**
     * [RVP] FCS 失效时稳定器在<b>物理层</b>失效。
     *
     * <p>本体 {@code WeaponUnit.tickRot()}（0.6.2.1 源码 492 行）直接读私有字段
     * {@code withStabilizer}，不经过可覆写的 {@code withStabilizer()}——只覆写方法
     * （如 {@code RVP_OpticalWeaponUnit}）对使用本体武器站的载具无效，实测 VT4 上下坡时
     * 炮管仍保持世界俯仰。此处改该字段读取为「虚方法原值 && FCS 未失效」。</p>
     */
    @Redirect(method = "tickRot", remap = false, at = @At(value = "FIELD", remap = false,
            target = "Lorg/ywzj/vehicle/vehicle/part/WeaponUnit;withStabilizer:Z", opcode = Opcodes.GETFIELD))
    private boolean rvp$fcsGateStabilizerField(WeaponUnit self) {
        return self.withStabilizer() && !RVP_FcsState.isDestroyed(self);
    }

    /** FCS 失效时其余虚方法调用方（本体观瞄文字、乘员视角随车体转动等）同样读到稳定器关闭。 */
    @Inject(method = "withStabilizer", at = @At("HEAD"), cancellable = true, remap = false)
    private void rvp$fcsGateStabilizer(CallbackInfoReturnable<Boolean> cir) {
        if (RVP_FcsState.isDestroyed((WeaponUnit) (Object) this)) {
            cir.setReturnValue(false);
        }
    }
}
