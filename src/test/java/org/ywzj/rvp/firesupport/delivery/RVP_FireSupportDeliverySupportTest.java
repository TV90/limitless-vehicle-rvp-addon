package org.ywzj.rvp.firesupport.delivery;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.firesupport.api.RVP_FireSupportDeliveryResult;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 炮火投送结果合并规则回归测试。 */
class RVP_FireSupportDeliverySupportTest {
    @Test
    void combinePreservesRetryLaterInsteadOfReportingChunkWait() {
        RVP_FireSupportDeliveryResult retry = new RVP_FireSupportDeliveryResult(
                RVP_FireSupportDeliveryResult.Status.RETRY_LATER, null, null);
        RVP_FireSupportDeliveryResult prepared = new RVP_FireSupportDeliveryResult(
                RVP_FireSupportDeliveryResult.Status.PREPARED, null, null);

        assertEquals(RVP_FireSupportDeliveryResult.Status.RETRY_LATER,
                RVP_FireSupportDeliverySupport.combine(retry, prepared).status());
        assertEquals(RVP_FireSupportDeliveryResult.Status.RETRY_LATER,
                RVP_FireSupportDeliverySupport.combine(prepared, retry).status());
    }

    @Test
    void combineStillReportsChunkWaitWhenNoTrajectoryRetryExists() {
        RVP_FireSupportDeliveryResult waiting = new RVP_FireSupportDeliveryResult(
                RVP_FireSupportDeliveryResult.Status.WAITING_FOR_CHUNK, null, null);
        RVP_FireSupportDeliveryResult prepared = new RVP_FireSupportDeliveryResult(
                RVP_FireSupportDeliveryResult.Status.PREPARED, null, Vec3.ZERO);

        assertEquals(RVP_FireSupportDeliveryResult.Status.WAITING_FOR_CHUNK,
                RVP_FireSupportDeliverySupport.combine(waiting, prepared).status());
    }
}
