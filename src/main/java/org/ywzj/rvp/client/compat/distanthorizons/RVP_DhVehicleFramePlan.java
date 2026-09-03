package org.ywzj.rvp.client.compat.distanthorizons;

import org.ywzj.rvp.client.compat.distanthorizons.realvehicleprotect.RVP_DhTrackedVehicleFramePlan;
import org.ywzj.rvp.client.render.remotevisibility.RVP_RemoteVehicleFramePlan;

/** 把远距代理和客户端真实载具保护层组合成同一帧的 DH 合成信封。 */
public record RVP_DhVehicleFramePlan(RVP_RemoteVehicleFramePlan remotePlan,
                                     RVP_DhTrackedVehicleFramePlan trackedPlan,
                                     long frameId) {
    /** 返回至少包含一个非空绘制层的帧信封，否则返回 {@code null}。 */
    public static RVP_DhVehicleFramePlan of(RVP_RemoteVehicleFramePlan remotePlan,
                                            RVP_DhTrackedVehicleFramePlan trackedPlan) {
        return remotePlan == null && trackedPlan == null
                ? null
                : new RVP_DhVehicleFramePlan(remotePlan, trackedPlan, System.nanoTime());
    }

    /** 返回远距代理候选数。 */
    public int remoteSelectedCount() {
        return remotePlan == null ? 0 : remotePlan.selectedCount();
    }

    /** 返回客户端真实载具基础有效数。 */
    public int trackedLoadedCount() {
        return trackedPlan == null ? 0 : trackedPlan.loadedCount();
    }

    /** 返回进入保护层的客户端真实载具数。 */
    public int trackedSelectedCount() {
        return trackedPlan == null ? 0 : trackedPlan.selectedCount();
    }

    /** 返回两个来源的最终候选总数。 */
    public int selectedCount() {
        return remoteSelectedCount() + trackedSelectedCount();
    }
}
