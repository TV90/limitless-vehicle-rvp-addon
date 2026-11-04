package org.ywzj.rvp.vehicle;

import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3f;

/** 吊舱的连续转角计算；同一视线有两组等价转角，不能只保留俯仰在正负九十度内的一组。 */
public final class RVP_OpticalSightMath {
    /** 单位方向水平分量低于此值视为竖直，避免浮点尾差决定左右朝向。 */
    private static final double POLE_EPSILON = 1.0E-6D;

    /** 工具类不允许实例化。 */
    private RVP_OpticalSightMath() {}

    /** 以完整画面姿态解释鼠标：向右绕画面上轴转，向下绕画面横轴转，不限制世界俯仰。 */
    public static Quaterniond turnInView(Quaterniondc view, Vec3 direction, double yawDelta, double pitchDelta) {
        // 调用最短旋转对齐，先补偿观察位置移动或尚未追上的炮口，再在当前画面局部轴累加输入。
        return followDirection(view, direction).rotateY(Math.toRadians(-yawDelta))
                .rotateX(Math.toRadians(pitchDelta)).normalize();
    }

    /** 只转动看向目标所需的最小角度，保留画面横轴；经过正下方不由机械偏航强制翻滚。 */
    public static Quaterniond followDirection(Quaterniondc view, Vec3 direction) {
        if (direction.lengthSqr() < 1.0E-16D) return new Quaterniond(view);
        Vector3d from = new Quaterniond(view).transform(new Vector3d(0, 0, 1));
        Vec3 to = direction.normalize();
        return new Quaterniond().rotationTo(from.x, from.y, from.z, to.x, to.y, to.z)
                .mul(view).normalize();
    }

    /** 从完整姿态读取世界视线，使用双精度避免极点附近先拆角再重建的损失。 */
    public static Vec3 direction(Quaterniondc rotation) {
        Vector3d direction = new Quaterniond(rotation).transform(new Vector3d(0, 0, 1));
        return new Vec3(direction.x, direction.y, direction.z);
    }

    /** 将相机的俯仰/左右/横滚（度）还原为完整画面姿态。 */
    public static Quaterniond rotation(double pitch, double yaw, double roll) {
        return new Quaterniond().rotateY(Math.toRadians(-yaw)).rotateX(Math.toRadians(pitch))
                .rotateZ(Math.toRadians(roll));
    }

    /**
     * 在两组等价角度中先选择机械运动连续的一组，所有角度单位为度；调用方随后执行机械限位。
     * 只有转动距离完全相等时才比较范围，不能为绕过止挡切到相隔半圈的另一组角度。
     * 结果围绕参考角展开，允许暂时超过正负一百八十度，避免钳制时把 +181 错当成 -179。
     */
    public static Vec2 localAngles(Vec3 direction, float referencePitch, float referenceYaw,
                                   float minPitch, float maxPitch, float minYaw, float maxYaw) {
        double horizontal = Math.hypot(direction.x, direction.z);
        double pitch = Math.toDegrees(Math.atan2(-direction.y, horizontal));
        double yaw = horizontal <= POLE_EPSILON * direction.length() ? referenceYaw
                : Math.toDegrees(Math.atan2(-direction.x, direction.z));
        // 调用角度折返后围绕各自参考角展开，两组候选都保持与当前机械状态的最短表示。
        double otherPitch = referencePitch + wrap(180.0D - pitch - referencePitch);
        double otherYaw = referenceYaw + wrap(yaw + 180.0D - referenceYaw);
        pitch = referencePitch + wrap(pitch - referencePitch);
        yaw = referenceYaw + wrap(yaw - referenceYaw);
        // 调用机械距离评分；触及边缘时仍保留当前分支，不能因另一分支可达就翻转目标角。
        double firstScore = branchScore(pitch, yaw, referencePitch, referenceYaw);
        double otherScore = branchScore(otherPitch, otherYaw, referencePitch, referenceYaw);
        boolean first = firstScore <= otherScore;
        if (Math.abs(firstScore - otherScore) <= 1.0E-8D) {
            // 极点两侧若机械距离相同，才用范围作平局判定，避免无意义地选到不可达的一侧。
            first = outsideDistance(pitch, yaw, minPitch, maxPitch, minYaw, maxYaw)
                    <= outsideDistance(otherPitch, otherYaw, minPitch, maxPitch, minYaw, maxYaw);
        }
        return first ? new Vec2((float) pitch, (float) yaw) : new Vec2((float) otherPitch, (float) otherYaw);
    }

    /** 比较已展开候选与当前机械状态的转动距离，不把范围惩罚叠加成跳分支的动力。 */
    private static double branchScore(double pitch, double yaw, double referencePitch, double referenceYaw) {
        double dp = pitch - referencePitch;
        double dy = yaw - referenceYaw;
        return dp * dp + dy * dy;
    }

    /** 仅用于机械距离相同的候选，衡量超出配置范围的程度。 */
    private static double outsideDistance(double pitch, double yaw, double minPitch, double maxPitch,
                                          double minYaw, double maxYaw) {
        double outsidePitch = Math.max(minPitch - pitch, Math.max(0.0D, pitch - maxPitch));
        double outsideYaw = Math.max(minYaw - yaw, Math.max(0.0D, yaw - maxYaw));
        return outsidePitch * outsidePitch + outsideYaw * outsideYaw;
    }

    /**
     * 将完整姿态拆成连续相机角度；正下方用上一帧左右角，再由右向量解出横滚。
     * 不使用在极点处不稳定的 getEulerAnglesYXZ，且俯仰可以连续经过九十度。
     */
    public static Angles cameraAngles(Quaternionf rotation, @Nullable Angles previous) {
        Vector3f forward = rotation.transform(new Vector3f(0.0F, 0.0F, 1.0F));
        double horizontal = Math.hypot(forward.x, forward.z);
        double pitch = Math.toDegrees(Math.atan2(-forward.y, horizontal));
        double yaw;
        if (horizontal <= POLE_EPSILON) {
            Vector3f right = rotation.transform(new Vector3f(1.0F, 0.0F, 0.0F));
            yaw = previous == null ? Math.toDegrees(Math.atan2(right.z, right.x)) : previous.yaw;
        } else {
            yaw = Math.toDegrees(Math.atan2(-forward.x, forward.z));
        }
        // 调用横滚解析，三个角必须来自同一个完整姿态，不能把反号俯仰的横滚拼到当前视线上。
        double roll = roll(rotation, (float) pitch, (float) yaw);
        if (previous != null) {
            // 调用分支评分，避免穿过竖直方向时左右角和横滚各跳半圈。
            double firstScore = cameraScore(pitch, yaw, roll, previous);
            double otherPitch = 180.0D - pitch;
            double otherYaw = yaw + 180.0D;
            double otherRoll = roll + 180.0D;
            if (cameraScore(otherPitch, otherYaw, otherRoll, previous) < firstScore) {
                pitch = otherPitch;
                yaw = otherYaw;
                roll = otherRoll;
            }
            pitch = previous.pitch + wrap(pitch - previous.pitch);
            yaw = previous.yaw + wrap(yaw - previous.yaw);
            roll = previous.roll + wrap(roll - previous.roll);
        }
        return new Angles((float) pitch, (float) yaw, (float) roll);
    }

    /** 根据已选俯仰/左右角求同一姿态的横滚，竖直方向也不除以接近零的数。 */
    public static float roll(Quaternionf rotation, float pitch, float yaw) {
        Vector3f right = rotation.transform(new Vector3f(1.0F, 0.0F, 0.0F));
        double sinYaw = Math.sin(Math.toRadians(yaw));
        double cosYaw = Math.cos(Math.toRadians(yaw));
        double sinPitch = Math.sin(Math.toRadians(pitch));
        double cosPitch = Math.cos(Math.toRadians(pitch));
        // 直接以双精度构造零横滚的右/上轴；避免半角接近九十度时由 float 正弦反求余弦丢失精度。
        double alongRight = right.x * cosYaw + right.z * sinYaw;
        double alongUp = -right.x * sinYaw * sinPitch + right.y * cosPitch + right.z * cosYaw * sinPitch;
        return (float) Math.toDegrees(Math.atan2(alongUp, alongRight));
    }

    /** 已锁世界焦点的连续镜头；保持锁定前横滚，前进越过脚下锁点时也不翻转画面。 */
    public static Angles pointCamera(Vec3 direction, Angles previous) {
        // 调用同一双分支求解；此处为世界相机角，不受吊舱局部机械角范围约束。
        Vec2 angles = localAngles(direction, previous.pitch, previous.yaw, -180, 180, -180, 180);
        return new Angles((float) (previous.pitch + wrap(angles.x - previous.pitch)),
                (float) (previous.yaw + wrap(angles.y - previous.yaw)), previous.roll);
    }

    /** 比较完整姿态的两个等价表示与上一帧的角度距离。 */
    private static double cameraScore(double pitch, double yaw, double roll, Angles previous) {
        double dp = wrap(pitch - previous.pitch);
        double dy = wrap(yaw - previous.yaw);
        double dr = wrap(roll - previous.roll);
        return dp * dp + dy * dy + dr * dr;
    }

    /** 将角度差折回正负一百八十度内，单位为度。 */
    private static double wrap(double angle) {
        return angle - Math.floor((angle + 180.0D) / 360.0D) * 360.0D;
    }

    /** 相机完整姿态，三个分量均为度。 */
    public record Angles(
            /** 俯仰，可连续经过正下方。 */ float pitch,
            /** 左右转向，经过极点时保留连续性。 */ float yaw,
            /** 画面横滚，与同一组俯仰和左右角配套。 */ float roll) {}
}
