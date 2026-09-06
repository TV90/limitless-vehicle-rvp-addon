package org.ywzj.rvp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.vehicle.network.message.ServerVehicleWarn;
import org.ywzj.vehicle.vehicle.passenger.WarningReceiver;
import org.ywzj.vehicle.vehicle.pojo.WarnType;

/**
 * 截流本体 RADAR_SEARCH（被雷达搜索）告警的客户端处理（client 数组，仅物理客户端应用）。
 *
 * <p>本体 {@code RadarUnit.tickScan} 服务端每 20 tick 固定向被照射目标发一次
 * {@code ServerVehicleWarn(RADAR_SEARCH)}，{@code WarningReceiver.handle} 每包都播
 * 一次性告警音并刷新 targets——节奏与敌方雷达实际扫描周期无关，且其独立扫描只有
 * 方位判定（无高度窗/俯仰扇区门控），会造成"雷达实际看不到却每秒告警/文字闪烁"。
 * 本 Mixin 在 HEAD 取消 RADAR_SEARCH 分支（声音 + targets 写入一并跳过），
 * RADAR_LOCK / MISSILE_LAUNCH 分支不受影响（锁定循环音、导弹告警照常）。</p>
 *
 * <p>搜索告警改由 RVP 全链路接管：{@code RVP_WarnRelayService} 按敌方雷达扫描周期
 * 调度响声（S2CRvpWarn.audible），每 5 tick 刷新 targets 保 RWR 图标与机型文字常亮。
 * 回退方式：从 {@code ywzj_rvp.mixins.json} client 数组移除本类即恢复本体原始链路。</p>
 *
 * <p>带毒安全：client 数组 Mixin 只在物理客户端应用，服务端不转换该类、无帧重算风险；
 * 且目标方法 {@code handle} 本身标注 {@code @OnlyIn(Dist.CLIENT)}。
 * 2026-09-06 经用户批准新增（此前已逐级排除替代方案：本体 Channel 无公共拦截事件、
 * PlaySoundEvent 公共事件只能截声音、截不了 targets 写入故无法根治文字闪烁）。</p>
 */
@Mixin(value = WarningReceiver.class, remap = false)
public class WarningReceiverSearchWarnMixin {

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true, remap = false)
    private static void ywzj_rvp$cancelSearchWarn(ServerVehicleWarn message, CallbackInfo ci) {
        if (message.warnType == WarnType.RADAR_SEARCH) {
            ci.cancel();
        }
    }
}
