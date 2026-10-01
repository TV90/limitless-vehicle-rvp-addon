package org.ywzj.rvp.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.VesselVehicle;

/**
 * [RVP] 船转向 depth 倍率因子（2026-10-01，方案与审计：
 * {@code docs/plan/RVP船转向depth倍率_方案与提示词_20261001.md}）：
 * 本体 {@code VesselVehicle.tickMove()} 用 {@code mainCubeOBB.depth}（结构主 cube 的 Z 尺寸，
 * {@code public double}）作转向角速度 atan2 分母——052D 等 depth 120+ 格的船满舵掉头 250 秒+
 * 不可用。但 mainCubeOBB 同时承担碰撞职责（PhysicsEngine.collectContacts 采样点 /
 * AbstractVehicle.push 载具互撞 / makeBoundingBox 包围盒 / tickPower 流体采样），缩小结构
 * cube 会让碰撞一起缩——两船互穿、贴岸探不到方块。
 *
 * <p>本 Mixin 用 MixinExtras {@code @ModifyExpressionValue} 对 tickMove 内该字段的两处
 * GETFIELD（{@code depth > 0} 判定 与 atan2 分母，均为预期缩放点）做单点表达式缩放：
 * {@code depth × physics_info.turn_depth_scale}——只影响转向计算，碰撞路径零触碰。
 * 未配置 / 非法值（≤0）按 1.0 原值返回，转向手感与本体完全一致。</p>
 *
 * <p>双端安全：{@code tickMove} 仅服务端执行（{@code AbstractVehicle.tick} 服务端分支），
 * 配置只读 RVP 服务端安全管理器（{@link RVP_VehicleExtendedConfigManager}，双端类）；
 * 本类零客户端类型引用，注册于公共数组。{@code VesselVehicle} 类级虽含客户端类型引用
 * （VesselVehicleContext 字段等"带毒"成员），但按项目方法级带毒判定，帧重算只发生在被
 * 注入的 {@code tickMove}（方法体纯双端引用），安全。</p>
 */
@Mixin(value = VesselVehicle.class, remap = false)
public abstract class RVP_VesselTurnDepthMixin {

    /**
     * 缩放 tickMove 内对 {@code VehicleCubeOBB.depth}（double，描述符 D）的每次读取。
     * handler 只做乘法与配置查询，无状态；两处读取点共用本规则（预期行为）。
     */
    @ModifyExpressionValue(method = "tickMove", remap = false,
            at = @At(value = "FIELD", opcode = Opcodes.GETFIELD, remap = false,
                    target = "Lorg/ywzj/vehicle/vehicle/structure/VehicleCubeOBB;depth:D"))
    private double rvp$scaleTurnDepth(double original) {
        return original * RVP_VehicleExtendedConfigManager.getTurnDepthScale((AbstractVehicle) (Object) this);
    }
}
