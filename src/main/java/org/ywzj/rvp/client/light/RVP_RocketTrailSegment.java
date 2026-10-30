package org.ywzj.rvp.client.light;

import net.minecraft.world.phys.Vec3;

/**
 * 仅供一帧绘制的体积烟段；两端拥有各自的年龄、半径和浓度，转弯不重排旧轨迹。
 * @param source 弹体同步实体编号，近距与远距克隆共用
 * @param start 起点世界坐标，已包含烟自身缓慢漂移，单位格
 * @param end 终点世界坐标，单位格；与起点相同表示独立烟团
 * @param startRadius 起点半径，单位格
 * @param endRadius 终点半径，单位格
 * @param startDensity 起点剩余浓度，零表示已经消散
 * @param endDensity 终点剩余浓度
 * @param age 两端平均年龄，单位 tick，仅用于缓慢翻滚
 * @param seed 来源稳定的程序噪声相位，不随相机移动
 * @param grey 烟的基础灰度，不包含环境光和喷焰照明
 * @param noiseScale 世界噪声频率，随既有尺寸配置缩放
 */
public record RVP_RocketTrailSegment(int source, Vec3 start, Vec3 end, float startRadius, float endRadius,
        float startDensity, float endDensity, float age, float seed, float grey, float noiseScale) {
    /** 整段保守包围球中心，不能只检查喷口或最新位置。 */
    public Vec3 center() { return start.add(end).scale(0.5); }

    /** 包住整条线段和两端烟团的半径，供屏幕裁剪和光照筛选。 */
    public float boundRadius() {
        return (float) (start.distanceTo(end) * 0.5 + Math.max(startRadius, endRadius));
    }

    /** 按烟段两端插值半径判断眼睛是否在烟内，不能用松散包围球把烟旁边也当成内部。 */
    public boolean contains(Vec3 point) {
        Vec3 axis = end.subtract(start);
        double lengthSquared = axis.lengthSqr();
        double along = lengthSquared > 1.0E-12
                ? Math.max(0, Math.min(1, point.subtract(start).dot(axis) / lengthSquared)) : 0;
        double radius = startRadius + (endRadius - startRadius) * along;
        return point.distanceToSqr(start.lerp(end, along)) <= radius * radius;
    }
}
