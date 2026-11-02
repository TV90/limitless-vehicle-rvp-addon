package org.ywzj.rvp.entity.gunner.ai;

/** 普通索敌在同一优先层内的现行评分；分数越小越优先，游戏与仿真共用。 */
public final class RVP_GunnerTargetScore {
    private RVP_GunnerTargetScore() {}

    /** 距离单位格，夹角单位弧度；发射架不施加朝向惩罚。 */
    public static double score(double distance, double angleRadians, boolean noAnglePenalty) {
        return noAnglePenalty ? distance : distance + angleRadians * 32.0;
    }
}
