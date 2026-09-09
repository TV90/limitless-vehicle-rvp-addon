package org.ywzj.rvp.mixin;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.client.state.RVP_ViewRestriction;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;

/**
 * 被标记座位（{@code rvp_no_cockpit_view: true}）禁止进入座舱第一人称（OPERATOR）。
 *
 * <p>注入点为本体 {@code LocalVehiclePlayer.switchViewType(ViewType)} 内
 * {@code viewType = toViewType} 字段写入（原 337 行，方法体内唯一 PUTFIELD；类内另有
 * 66 行初始化与 583 行 checkState() 下车重置 THIRD_PERSON 均为安全方向写入，不影响本拦截）。
 * 必须指定 {@code opcode = PUTFIELD}：viewType 在方法体内另有 5 处读引用，不加 opcode
 * 会在读点提前取消、跳过本体的相机瞄准与旋转保存副作用。</p>
 *
 * <p>行为：toViewType == OPERATOR 且当前座位被 {@link RVP_ViewRestriction} 标记时取消写入，
 * viewType 保持 THIRD_PERSON/SCOPE；其余视角（THIRD_PERSON/SCOPE）不受影响。经由此方法
 * 的两条 OPERATOR 来源均被覆盖——VIEW 键循环与 {@code tickAim()} 内 SCOPE 状态下
 * 无光学瞄具时的自动降级（显式传 OPERATOR）。</p>
 *
 * <p>带毒安全：LocalVehiclePlayer 为类级 {@code @OnlyIn(Dist.CLIENT)}，本 Mixin 注册于
 * client 数组仅物理客户端应用，服务端无帧重算风险（同类先例：LocalVehiclePlayerTVMissileTurnMixin）。
 * Mixin 只做最小拦截，判定逻辑全部在 {@link RVP_ViewRestriction}。</p>
 */
@Mixin(value = LocalVehiclePlayer.class, remap = false)
public class LocalVehiclePlayerViewTypeRestrictMixin {

    @Inject(method = "switchViewType",
            at = @At(value = "FIELD",
                    target = "Lorg/ywzj/vehicle/vehicle/LocalVehiclePlayer;viewType:Lorg/ywzj/vehicle/vehicle/LocalVehiclePlayer$ViewType;",
                    opcode = Opcodes.PUTFIELD),
            cancellable = true, remap = false)
    private void ywzj_rvp$blockOperatorOnRestrictedSeat(LocalVehiclePlayer.ViewType toViewType, CallbackInfo ci) {
        // 仅拦截「进入座舱第一人称」：被标记座位上跳过 viewType 写入，其余视角放行
        if (toViewType == LocalVehiclePlayer.ViewType.OPERATOR
                && RVP_ViewRestriction.isCockpitViewBlocked()) {
            ci.cancel();
        }
    }
}
