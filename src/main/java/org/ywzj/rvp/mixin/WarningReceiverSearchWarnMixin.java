package org.ywzj.rvp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.vehicle.network.message.ServerVehicleWarn;
import org.ywzj.vehicle.vehicle.passenger.WarningReceiver;
import org.ywzj.vehicle.vehicle.pojo.WarnType;

/**
 * 截流本体 {@code WarningReceiver.handle} 的雷达类告警写入（client 数组，仅物理客户端应用）。
 *
 * <p><b>RADAR_SEARCH（2026-09-06，用户批准新增）</b>：本体 {@code RadarUnit.tickScan}
 * 服务端每 20 tick 固定向被照射目标发一次 {@code ServerVehicleWarn(RADAR_SEARCH)}，
 * {@code WarningReceiver.handle} 每包都播一次性告警音并刷新 targets——节奏与敌方雷达
 * 实际扫描周期无关，且其独立扫描只有方位判定（无高度窗/俯仰扇区门控），会造成
 * "雷达实际看不到却每秒告警/文字闪烁"。HEAD 取消 RADAR_SEARCH 分支（声音 + targets
 * 写入一并跳过），改由 RVP 全链路接管：{@code RVP_WarnRelayService} 按敌方雷达扫描
 * 周期调度响声（S2CRvpWarn.audible），每 5 tick 刷新 targets 保 RWR 图标与机型文字常亮。</p>
 *
 * <p><b>RADAR_LOCK（2026-10-04 扩展，同注入点）</b>：本体锁定生命周期是永久的——下车/
 * 登出/空车都没有解除逻辑（唯一自动清除=目标死亡），服务端雷达永远开机，玩家弃船后
 * 旧锁会驱动本体通道每 2 tick 持续发 RADAR_LOCK，目标载具登上人即无限循环锁定告警
 * （用户实测：052d 锁 ddg51 发射鹰击-20 后下车，登上 ddg51 仍持续响）。本通道截流后
 * RADAR_LOCK 的 targets 写入全权移交 {@code RVP_WarnRelayService}（服务端带"锁定者
 * 载具有人"乘员门，5 tick 补发 &lt; 500ms 过期窗，有人船告警无缝）——无条件截流是因为
 * 客户端无法可靠判定锁定者是否有人（超视距广播克隆只含驾驶员、无 gunner 信息）。
 * {@code MISSILE_LAUNCH} 不截流：在飞导弹是真威胁，与发射车是否有人无关。</p>
 *
 * <p>带毒安全：client 数组 Mixin 只在物理客户端应用，服务端不转换该类、无帧重算风险；
 * 且目标方法 {@code handle} 本身标注 {@code @OnlyIn(Dist.CLIENT)}。
 * 回退方式：从 {@code ywzj_rvp.mixins.json} client 数组移除本类即恢复本体原始链路
 * （RADAR_SEARCH/RADAR_LOCK 均回归本体通道，无人船循环告警随之回归）。</p>
 */
@Mixin(value = WarningReceiver.class, remap = false)
public class WarningReceiverSearchWarnMixin {

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true, remap = false)
    private static void ywzj_rvp$cancelRadarWarn(ServerVehicleWarn message, CallbackInfo ci) {
        if (message.warnType == WarnType.RADAR_SEARCH || message.warnType == WarnType.RADAR_LOCK) {
            ci.cancel();
        }
    }
}
