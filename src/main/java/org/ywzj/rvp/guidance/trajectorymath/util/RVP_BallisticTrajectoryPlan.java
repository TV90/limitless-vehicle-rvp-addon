package org.ywzj.rvp.guidance.trajectorymath.util;

import net.minecraft.world.phys.Vec3;

/**
 * 实体与纯数学入口之间的只读弹道参数，不携带世界或实体。
 * @param launchPosition 点火位置，世界坐标
 * @param cruiseAltitude 相对点火点的最高抬升，格
 * @param maxAscentLead 配置前视距离，格；攻角模式可按响应时间扩大
 * @param diveRadius 俯冲最小水平半径，格
 * @param diveAltitudeFactor 高度差换算俯冲距离倍率
 * @param diveLeadFactor 转弯半径换算俯冲距离倍率
 * @param tacticalManeuverAmplitude 中段横向机动幅度，格
 */
public record RVP_BallisticTrajectoryPlan(Vec3 launchPosition, double cruiseAltitude,
        double maxAscentLead, double diveRadius, double diveAltitudeFactor,
        double diveLeadFactor, double tacticalManeuverAmplitude) implements RVP_BallisticTrajectoryProfile { }
