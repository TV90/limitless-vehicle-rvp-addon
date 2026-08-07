package org.ywzj.rvp.virtualflight.trajectory;

/** 一步积分结果；invalid 用于隔离 NaN/Infinity，防止污染服务器状态。 */
public record RVP_VirtualTrajectoryResult(RVP_VirtualTrajectoryState state,
                                          double turnAngleRadians,
                                          boolean invalid) {
}
