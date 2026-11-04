package org.ywzj.rvp.client.state;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.ywzj.rvp.vehicle.RVP_OpticalSightMath;

/**
 * 焦点锁定的世界坐标瞄准状态，不依赖炮塔实际角度或渲染帧率。
 *
 * <p>真实地形命中保存世界坐标；未命中只保存世界方向，不把射线末端当成近处地面。
 * 鼠标从上一次请求继续累加，远景查询返回后仅接收同一次输入对应的真实命中。</p>
 */
public final class RVP_FocusLockAim {
    /** 判断观察点与锁点重合的平方距离下限，单位为方块平方。 */
    private static final double MIN_DISTANCE_SQUARED = 1.0E-12D;

    /** 未知距离时供本体位置式火控使用的方向代理长度，单位为方块，不代表真实测距。 */
    private static final double UNKNOWN_RANGE = 4096.0D;

    /** 同一射线未命中后的重查间隔，单位为纳秒；等待 DH 数据就绪时不逐刻扫描。 */
    private static final long TERRAIN_RETRY_NANOS = 1_000_000_000L;

    /** 真实世界锁点或方向代理位置；null 表示尚未建立有效焦点。 */
    @Nullable
    private Vec3 target;

    /** 未知距离时锁住的世界单位方向；null 表示已命中真实地形并锁世界坐标。 */
    @Nullable
    private Vec3 directionLock;

    /** 最近一次选点射线的观察原点，后台远景查询必须沿该原始射线完成。 */
    @Nullable
    private Vec3 rayOrigin;

    /** 锁定或鼠标请求的代次；仅观察位置变化不递增，用于拒绝过期远景结果。 */
    private long revision;

    /** 上次发出远景查询时的输入代次；新输入不受旧射线重试间隔限制。 */
    private long terrainQueryRevision = Long.MIN_VALUE;

    /** 上次发出远景查询的单调时间，单位为纳秒；观察点移动不重置它。 */
    private long terrainQueryNanos;

    /** 连续方位角，单位为度；跨过正负 180 度或经过正上/正下方时保留转动连续性。 */
    private double yawDeg;

    /** 可过竖直方向的观瞄使用完整画面姿态；null 表示仍使用普通站原有角度输入。 */
    @Nullable
    private Quaterniond viewRotation;

    /** 清除上一武器站或上一世界的瞄准记忆。 */
    public void clear() {
        target = null;
        directionLock = rayOrigin = null;
        yawDeg = 0.0D;
        viewRotation = null;
        revision++;
    }

    /** 接受初始锁点、外部改点或有效射线命中，不接受观察点自身及非法坐标。 */
    public void acceptTarget(Vec3 origin, @Nullable Vec3 requestedTarget) {
        // 调用坐标检查，避免无效射线或世界切换污染后续鼠标累加。
        if (!isFinite(origin) || !isFinite(requestedTarget)
                || requestedTarget.distanceToSqr(origin) < MIN_DISTANCE_SQUARED) {
            return;
        }
        target = requestedTarget;
        directionLock = rayOrigin = null;
        revision++;
        // 调用方向解析，使新锁点沿最短角度延续已有方位。
        direction(origin);
    }

    /** 接收保留命中语义的射线；只有真实表面命中才建立有限距离世界锁点。 */
    public void acceptRay(Vec3 origin, Vec3 direction, @Nullable Vec3 surfaceHit) {
        if (surfaceHit != null) {
            // 调用真实锁点接收，保持车辆移动时应有的地面点视差补偿。
            acceptTarget(origin, surfaceHit);
            return;
        }
        // 调用有限性检查，未知距离也不能接受无效观察原点或零方向。
        if (!isFinite(origin) || !isFinite(direction) || direction.lengthSqr() < 1.0E-8D) {
            return;
        }
        directionLock = direction.normalize();
        rayOrigin = origin;
        target = origin.add(directionLock.scale(UNKNOWN_RANGE));
        revision++;
        // 调用方向解析，初始化连续方位而不引入虚假近距离视差。
        direction(origin);
    }

    /** 是否只锁方向；该状态不能被当成已测得距离的地面点。 */
    public boolean isDirectionLocked() {
        return directionLock != null;
    }

    /** 读取选点代次，供后台查询与最新鼠标请求核对。 */
    public long revision() {
        return revision;
    }

    /** 为未知距离射线申请一次查询；新输入立即查询，同一输入未命中后每秒重试。 */
    public boolean tryBeginTerrainQuery(long nowNanos) {
        if (directionLock == null || rayOrigin == null
                || (terrainQueryRevision == revision && nowNanos - terrainQueryNanos < TERRAIN_RETRY_NANOS)) {
            return false;
        }
        terrainQueryRevision = revision;
        terrainQueryNanos = nowNanos;
        return true;
    }

    /** 读取未知距离射线的原始观察位置，车辆后续移动不改写它。 */
    @Nullable
    public Vec3 rayOrigin() {
        return rayOrigin;
    }

    /** 只移动本体火控需要的位置代理，不改变锁定方向、选点原点或输入代次。 */
    @Nullable
    public Vec3 rebase(Vec3 origin) {
        // 调用有限性检查，固定世界点不随观察位置移动。
        if (directionLock != null && isFinite(origin)) {
            target = origin.add(directionLock.scale(UNKNOWN_RANGE));
        }
        return target;
    }

    /** 仅将同一请求的前方真实远景命中升级为世界锁点，过期结果不能拉回镜头。 */
    public boolean acceptTerrainTarget(long requestedRevision, @Nullable Vec3 terrainHit) {
        // 调用有限性检查，后台失败、锁点已切换或已解锁时保持现有状态。
        if (directionLock == null || rayOrigin == null || revision != requestedRevision
                || !isFinite(terrainHit)
                || terrainHit.subtract(rayOrigin).dot(directionLock) <= 0.0D) {
            return false;
        }
        // 调用真实锁点接收，命中后不再跟随车辆移动位置代理。
        acceptTarget(rayOrigin, terrainHit);
        return true;
    }

    /** 返回真实锁点或本体火控所需的方向代理，具体语义由 isDirectionLocked 区分。 */
    @Nullable
    public Vec3 target() {
        return target;
    }

    /** 真实点按观察位置求方向；未知距离保持世界方向，同时保留极点附近的方位记忆。 */
    @Nullable
    public Vec3 direction(Vec3 origin) {
        // 调用坐标检查，拒绝断线或世界卸载期间的无效观察位置。
        if (target == null || !isFinite(origin)) {
            return null;
        }
        Vec3 offset = directionLock != null ? directionLock : target.subtract(origin);
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
     * 返回尚未碰撞检测的候选点；调用方随后用 acceptRay 区分真实命中和未知距离。
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
        double range = directionLock != null ? UNKNOWN_RANGE : target.distanceTo(origin);
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
        if (directionLock != null) {
            directionLock = requestedDirection;
            rayOrigin = origin;
        }
        revision++;
        return target;
    }

    /** 连续吊舱的屏幕轴输入，不把世界俯仰钳在九十度；保持原有命中距离与方向锁语义。 */
    @Nullable
    public Vec3 turnInView(Vec3 origin, Quaterniondc fallback, double yawDeltaDeg, double pitchDeltaDeg) {
        if (!Double.isFinite(yawDeltaDeg) || !Double.isFinite(pitchDeltaDeg)) return target;
        // 调用现有真实点/方向锁解析，观察位置移动时先补偿同一目标的视差。
        Vec3 current = direction(origin);
        if (current == null) return target;
        // 调用画面姿态对齐，使同一刻的多次输入真正累加，而不是重复读取旧炮口。
        viewRotation = RVP_OpticalSightMath.turnInView(viewRotation == null ? fallback : viewRotation,
                current, yawDeltaDeg, pitchDeltaDeg);
        if (yawDeltaDeg == 0.0D && pitchDeltaDeg == 0.0D) return target;
        double range = directionLock != null ? UNKNOWN_RANGE : target.distanceTo(origin);
        Vec3 requested = RVP_OpticalSightMath.direction(viewRotation);
        target = origin.add(requested.scale(range));
        if (directionLock != null) {
            directionLock = requested;
            rayOrigin = origin;
        }
        revision++;
        // 调用已有方向解析更新方位记忆；镜头本身不再由这个有极点的单独方位角重建。
        direction(origin);
        return target;
    }

    /** 每帧沿同一个世界点补偿移动视差，保留完整画面横轴并向调用方返回副本。 */
    public Quaterniond viewRotation(Vec3 origin, Quaterniondc fallback) {
        Vec3 current = direction(origin);
        if (current == null) return new Quaterniond(fallback);
        // 调用最短方向对齐，过正下方时不把左右与横滚各翻半圈。
        viewRotation = RVP_OpticalSightMath.followDirection(viewRotation == null ? fallback : viewRotation, current);
        return new Quaterniond(viewRotation);
    }

    /**
     * 将远景地形柱转换为原始视线与表面的交点，不能直接使用柱底部坐标。
     * 位置和高度单位为方块，detailLevel 每增加一级，水平格子宽度翻倍。
     */
    @Nullable
    public static Vec3 terrainColumnIntersection(Vec3 origin, Vec3 direction, double maxDistance,
                                                  int x, int z, int bottomY, int topY, int detailLevel) {
        // 调用有限性检查，缺失/非法远景数据不得伪造地面锁点。
        if (!isFinite(origin) || !isFinite(direction) || direction.lengthSqr() < 1.0E-8D
                || !Double.isFinite(maxDistance) || maxDistance <= 0.0D
                || bottomY >= topY || detailLevel < 0 || detailLevel > 30) {
            return null;
        }
        double width = Math.scalb(1.0D, detailLevel);
        double minX = Math.floor(x / width) * width;
        double minZ = Math.floor(z / width) * width;
        AABB column = new AABB(minX, bottomY, minZ, minX + width, topY, minZ + width);
        return column.clip(origin, origin.add(direction.normalize().scale(maxDistance))).orElse(null);
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
