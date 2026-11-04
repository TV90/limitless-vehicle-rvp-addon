package org.ywzj.rvp.client.state;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 焦点锁定的世界坐标瞄准状态，不依赖炮塔实际角度或渲染帧率。
 *
 * <p>鼠标每次移动都从上一次请求的锁点继续累加；观察位置改变时只重算指向，
 * 不移动世界锁点。射线未命中时保留当前距离，避免在天空与地形边缘突然跳远。</p>
 */
public final class RVP_FocusLockAim {
    /** 判断观察点与锁点重合的平方距离下限，单位为方块平方。 */
    private static final double MIN_DISTANCE_SQUARED = 1.0E-12D;

    /** 当前世界锁点；null 表示尚未建立有效焦点。 */
    @Nullable
    private Vec3 target;

    /** 连续方位角，单位为度；跨过正负 180 度或经过正上/正下方时保留转动连续性。 */
    private double yawDeg;

    /** 清除上一武器站或上一世界的瞄准记忆。 */
    public void clear() {
        target = null;
        yawDeg = 0.0D;
    }

    /** 接受初始锁点、外部改点或有效射线命中，不接受观察点自身及非法坐标。 */
    public void acceptTarget(Vec3 origin, @Nullable Vec3 requestedTarget) {
        // 调用坐标检查，避免无效射线或世界切换污染后续鼠标累加。
        if (!isFinite(origin) || !isFinite(requestedTarget)
                || requestedTarget.distanceToSqr(origin) < MIN_DISTANCE_SQUARED) {
            return;
        }
        target = requestedTarget;
        // 调用方向解析，使新锁点沿最短角度延续已有方位。
        direction(origin);
    }

    /** 返回当前世界锁点；观察位置变化不会改写这个坐标。 */
    @Nullable
    public Vec3 target() {
        return target;
    }

    /** 由当前观察位置解析指向锁点的单位向量，保留极点附近的方位记忆。 */
    @Nullable
    public Vec3 direction(Vec3 origin) {
        // 调用坐标检查，拒绝断线或世界卸载期间的无效观察位置。
        if (target == null || !isFinite(origin)) {
            return null;
        }
        Vec3 offset = target.subtract(origin);
        if (offset.lengthSqr() < MIN_DISTANCE_SQUARED) {
            return null;
        }
        if (Math.hypot(offset.x, offset.z) > 1.0E-8D) {
            double principalYaw = Math.toDegrees(Math.atan2(-offset.x, offset.z));
            // 调用角度环绕工具，避免 179 度到 -179 度被解释为反向转一整圈。
            yawDeg += wrapDegrees(principalYaw - yawDeg);
        }
        return offset.normalize();
    }

    /**
     * 累加鼠标请求，参数已经是角度增量（度），不可再乘帧间时间。
     * 返回尚未碰撞检测的锁点；没有新命中时直接保留该点及其距离。
     */
    @Nullable
    public Vec3 turn(Vec3 origin, double yawDeltaDeg, double pitchDeltaDeg) {
        if (!Double.isFinite(yawDeltaDeg) || !Double.isFinite(pitchDeltaDeg)) {
            return target;
        }
        // 调用世界锁点方向解析，补偿观察位置变化，而非读取尚未跟上的炮口方向。
        Vec3 currentDirection = direction(origin);
        if (currentDirection == null || (yawDeltaDeg == 0.0D && pitchDeltaDeg == 0.0D)) {
            return target;
        }
        double range = target.distanceTo(origin);
        double pitchDeg = -Math.toDegrees(Math.atan2(currentDirection.y,
                Math.hypot(currentDirection.x, currentDirection.z)));
        pitchDeg = Math.max(-90.0D, Math.min(90.0D, pitchDeg + pitchDeltaDeg));
        // 调用环绕工具限制累计数值大小；方向跨越正负 180 度仍连续。
        yawDeg = wrapDegrees(yawDeg + yawDeltaDeg);
        double pitchRadians = Math.toRadians(pitchDeg);
        double yawRadians = Math.toRadians(yawDeg);
        double horizontal = Math.cos(pitchRadians);
        Vec3 requestedDirection = new Vec3(-Math.sin(yawRadians) * horizontal,
                -Math.sin(pitchRadians), Math.cos(yawRadians) * horizontal);
        target = origin.add(requestedDirection.scale(range));
        return target;
    }

    /** 返回连续方位角（度），供相机使用；先调用 direction 适配本帧观察位置。 */
    public float yaw() {
        return (float) yawDeg;
    }

    /**
     * 按实际显示视野计算灵敏度比例，等价于当前有效倍率的倒数。
     * 视野角单位为度；非法视野回退到武器站倍率，最大比例为 1。
     */
    public static double sensitivityScale(double renderedFov, double baseFov, double fallbackZoom) {
        if (Double.isFinite(renderedFov) && Double.isFinite(baseFov)
                && renderedFov > 0.0D && renderedFov < 180.0D
                && baseFov > 0.0D && baseFov < 180.0D) {
            return Math.min(1.0D, Math.tan(Math.toRadians(renderedFov * 0.5D))
                    / Math.tan(Math.toRadians(baseFov * 0.5D)));
        }
        return Double.isFinite(fallbackZoom) && fallbackZoom > 1.0D ? 1.0D / fallbackZoom : 1.0D;
    }

    /** 解锁过渡的剩余比例，按真实秒数衰减，避免不同帧率下回交速度不同。 */
    public static double handoffRetention(double elapsedSeconds) {
        return Math.exp(-Math.max(0.0D, Math.min(0.1D, elapsedSeconds)) / 0.05D);
    }

    /** 将角度差折回半圈内，单位为度。 */
    public static double wrapDegrees(double degrees) {
        return degrees - Math.floor((degrees + 180.0D) / 360.0D) * 360.0D;
    }

    /** 检查世界坐标是否存在且所有分量有限。 */
    public static boolean isFinite(@Nullable Vec3 position) {
        return position != null && Double.isFinite(position.x)
                && Double.isFinite(position.y) && Double.isFinite(position.z);
    }
}
