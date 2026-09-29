package org.ywzj.rvp.client.visual.cookoff;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/** 纯几何计算；舱盖位置跟随车体而方向恒为世界向上。 */
public final class RVP_WreckCookoffGeometry {
    /** 世界竖直方向，与残骸是否侧翻无关；舱盖火星的圆锥轴即此方向。 */
    public static final Vec3 UP = new Vec3(0, 1, 0);
    /** 舱盖火星的圆锥半角，单位度；用户定版：沿世界 Y 轴向上做 30° 圆锥随机采样。 */
    public static final double HATCH_CONE_HALF_ANGLE_DEGREES = 30.0;
    /** 接缝火星相对水平面的抬升角，单位度；与舱盖圆锥半角同名同源（都是 30°）。 */
    public static final double SEAM_ELEVATION_DEGREES = HATCH_CONE_HALF_ANGLE_DEGREES;
    /**
     * 单个采样点的最短持续 tick 数。用户定版：单点停留 3～12 tick 后换点。
     * 舱盖与接缝共用同一套分段换点规则。
     */
    public static final int SAMPLE_BURST_MIN_TICKS = 3;
    /** 单个采样点的最长持续 tick 数；与最短值共同给出 3～12 的随机区间。 */
    public static final int SAMPLE_BURST_MAX_TICKS = 12;
    /** 接缝采样点的抖动上限，单位"点位间距的倍数"；0.5 = 最多偏移半个间距，保证点位顺序与最小间距。 */
    public static final double SEAM_POSITION_JITTER = 0.5;
    /** 接缝火星在水平面内的随机偏摆上限，单位度；让相邻采样点的火星不共面。 */
    public static final double SEAM_YAW_JITTER_DEGREES = 8.0;
    /** 非法/退化尺寸时的兜底半尺寸（格），避免零尺寸 OBB 导致除零与 NaN 方向。 */
    private static final double MIN_HALF_EXTENT = 0.05;

    private RVP_WreckCookoffGeometry() {}

    /**
     * AFTER_WEATHER 顶点只做相机位置相减，视角旋转/摇晃由现有着色器 ModelView 统一执行。
     * 先双精度相减再提交顶点，避免大世界坐标转 float 后丢失喷口局部精度。
     */
    public static Vec3 cameraRelative(Vec3 worldPosition, Vec3 cameraPosition) {
        return worldPosition.subtract(cameraPosition);
    }

    /**
     * 火柱方向：舱盖柱恒为世界向上，与它的火星采样方向解耦。
     *
     * <p>舱盖火星只在世界 Y 轴 30° 圆锥内随机（见 {@link #hatchConeDirection}），
     * 若火柱直接复用火星方向，柱子会跟着随机锥角歪掉；用户定版要求柱子保持原来的竖直行为。</p>
     *
     * @param hatch      是否为舱盖出口
     * @param sparkAxis  该出口本 tick 的火星采样方向
     */
    public static Vec3 columnDirection(boolean hatch, Vec3 sparkAxis) {
        return hatch ? UP : sparkAxis;
    }

    /**
     * 渲染插值方向：先对前后两帧方向做线性插值再归一化；退化时回退当前帧方向。
     *
     * @param previous      上一 tick 方向
     * @param current       当前 tick 方向
     * @param partialTick   帧内插值系数
     */
    public static Vec3 interpolateDirection(Vec3 previous, Vec3 current, float partialTick) {
        Vec3 blended = previous.lerp(current, partialTick);
        return blended.lengthSqr() < 1.0e-8 ? current : blended.normalize();
    }

    /** 将相对 OBB 中心的局部坐标转为世界坐标，不修改调用者的四元数。 */
    public static Vec3 point(Vec3 center, Quaternionf rotation, Vec3 local) {
        return center.add(new Vec3(rotation.transform(local.toVector3f())));
    }

    /**
     * 把单个种子散列成 [0,1) 随机数；同一 seed 恒定。
     *
     * <p>用 Murmur3 finalizer（整数混合器）而不是"乘黄金比例取小数部分"：后者在算术级数
     * {@code seed * a + b} 上并不均匀，实测会把采样点挤到炮塔底缘的一小段（左前/左后/右前），
     * 表现为"接缝喷口集中在某几个区间"。混合器把输入的每一位都扩散开，
     * 相邻 seed 的输出不相关，因此弧段用满整圈。</p>
     *
     * <p>注意入参可达 {@code Integer.MAX_VALUE}（种子乘系数后会溢出成负数），
     * 因此按无符号 32 位处理，不假设非负。</p>
     */
    public static double seedRandom(int seed) {
        int mixed = seed;
        mixed ^= mixed >>> 16;
        mixed *= 0x85EBCA6B;
        mixed ^= mixed >>> 13;
        mixed *= 0xC2B2AE35;
        mixed ^= mixed >>> 16;
        // 无符号右移后与 0xFFFFFF 相与得到 [0,1) 的 24 位尾数
        return (mixed >>> 8) / (double) (1 << 24);
    }

    /**
     * 单个接缝采样点的最短持续 tick 数。用户定版：3～12 tick 换点。
     */
    public static final int SEAM_BURST_MIN_TICKS = 3;
    /** 单个接缝采样点的最长持续 tick 数；与最短值共同给出 3～12 的随机区间。 */
    public static final int SEAM_BURST_MAX_TICKS = 12;

    /**
     * 单个采样点的持续 tick 数；同一 seed 恒定，取
     * {@link #SAMPLE_BURST_MIN_TICKS}～{@link #SAMPLE_BURST_MAX_TICKS}。
     * 这是"单个喷出点喷一小段时间之后转到新点"的时长来源，舱盖与接缝共用。
     */
    public static int sampleBurstTicks(int seed) {
        return SAMPLE_BURST_MIN_TICKS
                + Math.floorMod(seed, SAMPLE_BURST_MAX_TICKS - SAMPLE_BURST_MIN_TICKS + 1);
    }

    /**
     * 舱盖火星方向：以<b>世界 Y 轴</b>为轴、在 {@link #HATCH_CONE_HALF_ANGLE_DEGREES} 半角内
     * 均匀随机取一条母线。
     *
     * <p>用"圆盘均匀采样"的半径分布 {@code sinθ = √u · sinα}：若直接对极角线性取样，
     * 点会向圆锥中心聚集；开方后圆锥内各方向的立体角密度才均匀。</p>
     *
     * @param seed 采样种子
     * @return 归一化世界方向，与 {@link #UP} 的夹角恒 ≤ 30°
     */
    public static Vec3 hatchConeDirection(int seed) {
        double sinHalfAngle = Math.sin(Math.toRadians(HATCH_CONE_HALF_ANGLE_DEGREES));
        double polarSin = Math.sqrt(seedRandom(seed * 17 + 5)) * sinHalfAngle;
        double polarCos = Math.sqrt(Math.max(0.0, 1.0 - polarSin * polarSin));
        double azimuth = seedRandom(seed * 29 + 11) * 2.0 * Math.PI;
        // 以世界 Y 轴为锥轴：cosθ 取竖直分量，水平分量沿随机方位铺开
        return new Vec3(Math.cos(azimuth) * polarSin, polarCos, Math.sin(azimuth) * polarSin).normalize();
    }

    /**
     * 采样点在环绕炮塔一周中的归一化方位，取值 [0,1)，0 对应正前方（-Z）。
     *
     * <p>做法是"方位等分 + 槽内居中抖动"：把一周 360° 等分成 {@code points} 份，
     * 采样点序号决定它在哪一份，种子决定它在这一份内的方位。抖动居中展开
     * （{@code [0.5 ± 0.5·J]} 个槽宽），因此相邻点的方位差恒 ≥ {@code 1 - J} 个槽宽，
     * "绕满一圈、不留空档"由结构保证。</p>
     *
     * <p><b>为什么按"方位"而不是按"底缘弧长"等分</b>：底缘是矩形，四条边从中心张开的角
     * 差别很大（宽而浅的炮塔，前/后边张角远大于侧边）。若按弧长等分，采样点会在短边一侧
     * 挤成一团、在长边一侧拉稀——实测用户看到的"集中在左后/左前/右前"就是这个原因。
     * 按方位等分则从俯视看是均匀绕一圈，与"环要圆"的观感一致。</p>
     *
     * @param seed        采样种子
     * @param sampleIndex 采样点序号，从 0 开始
     * @param points      同时铺开的采样点总数
     */
    public static double seamAzimuthFraction(int seed, int sampleIndex, int points) {
        int count = Math.max(1, points);
        // 调用本项目散列，取该点的方位槽内抖动；以槽中心为基准左右展开 J 个槽宽
        double centered = 0.5 + (seedRandom(seed * 13 + sampleIndex * 7) - 0.5) * SEAM_POSITION_JITTER;
        return (Math.floorMod(sampleIndex, count) + centered) / count;
    }

    /**
     * 接缝点采样：按环绕方位向炮塔 OBB 投影出一条射线，取它与底缘的交点，位置与法线一起返回。
     *
     * @param width       炮塔 OBB 宽（格），即 X 向尺寸
     * @param depth       炮塔 OBB 深（格），即 Z 向尺寸
     * @param seed        采样种子
     * @param sampleIndex 采样点序号，从 0 开始
     * @param points      同时铺开的采样点总数
     * @return 局部坐标（相对 OBB 中心）与水平外法线（Y 恒为 0）
     */
    public static SeamSample seamSample(double width, double depth, int seed, int sampleIndex, int points) {
        // 退化尺寸兜底：零尺寸 OBB 会让射线求交退化
        double halfWidth = Math.max(Math.abs(width) * 0.49, MIN_HALF_EXTENT);
        double halfDepth = Math.max(Math.abs(depth) * 0.49, MIN_HALF_EXTENT);
        // 调用本类方位规则，得到该采样点的环绕方位（0 = 正前方 -Z，顺时针看向 +X）
        double azimuth = seamAzimuthFraction(seed, sampleIndex, points);
        double angle = azimuth * 2.0 * Math.PI;
        double dirX = Math.sin(angle);
        double dirZ = -Math.cos(angle);
        // 射线从 OBB 中心射出，按矩形参数化求它与边界的交点：|x|/halfWidth 与 |z|/halfDepth 谁先到 1
        double scaleX = Math.abs(dirX) < 1.0E-9 ? Double.POSITIVE_INFINITY : halfWidth / Math.abs(dirX);
        double scaleZ = Math.abs(dirZ) < 1.0E-9 ? Double.POSITIVE_INFINITY : halfDepth / Math.abs(dirZ);
        double scale = Math.min(scaleX, scaleZ);
        double x = dirX * scale;
        double z = dirZ * scale;
        // 法线取交点所在边的外法线；落在角落时两条边的权重同为 1，自然融合成 45° 对角外法线
        boolean onXEdge = scaleX <= scaleZ;
        boolean onZEdge = scaleZ <= scaleX;
        double lateralX = onXEdge ? 1.0 : Math.min(1.0, Math.abs(x) / halfWidth);
        double lateralZ = onZEdge ? 1.0 : Math.min(1.0, Math.abs(z) / halfDepth);
        double normalX = Math.signum(x) * lateralX;
        double normalZ = Math.signum(z) * lateralZ;
        double normalLength = Math.sqrt(normalX * normalX + normalZ * normalZ);
        if (!(normalLength > 0.0)) {
            // 极值兜底：交点在中心（退化尺寸）时按方位给外法线，避免 NaN
            normalX = dirX;
            normalZ = dirZ;
            normalLength = Math.sqrt(normalX * normalX + normalZ * normalZ);
            if (!(normalLength > 0.0)) {
                normalX = 0.0;
                normalZ = -1.0;
                normalLength = 1.0;
            }
        }
        return new SeamSample(x, z, normalX / normalLength, normalZ / normalLength);
    }

    /**
     * 环绕炮塔一周的归一化方位（0 = 正前方 -Z，顺时针看向 +X）。
     * 与 {@link #seamAzimuthFraction} 的输入同坐标系，供自动测试验证"俯视看是均匀绕一圈"。
     *
     * @param x 相对 OBB 中心的 X 坐标
     * @param z 相对 OBB 中心的 Z 坐标
     */
    public static double azimuthOf(double x, double z) {
        double angle = Math.atan2(x, -z);
        double fraction = angle / (2.0 * Math.PI);
        return fraction < 0 ? fraction + 1.0 : fraction;
    }

    /**
     * 单个采样点的接缝火星方向：世界水平面内的外法线，俯仰在
     * {@link #SEAM_ELEVATION_DEGREES} 的 0～1 倍之间随机取样，
     * 再叠加小幅度方位抖动。
     *
     * @param worldRotation 炮塔（或炮塔所属部件）的世界旋转
     * @param normal        局部水平外法线
     * @param seed          采样种子
     * @param sampleIndex   采样点序号，从 0 开始
     * @return 归一化世界方向；俯仰恒落在 0° ~ +30°，即"水平偏上、不倒喷向下"
     */
    public static Vec3 seamDirection(Quaternionf worldRotation, Vec3 normal, int seed, int sampleIndex) {
        // 调用本项目采样散列，得到互不相关的偏航抖动与抬升角
        double yaw = seamYaw(worldRotation, normal) + (seedRandom(seed * 31 + 17) * 2.0 - 1.0)
                * SEAM_YAW_JITTER_DEGREES;
        double elevation = SEAM_ELEVATION_DEGREES * seedRandom(seed * 71 + sampleIndex * 29 + 23);
        // 注意符号约定：本体 VectorUtil.rotToVec(pitch, yaw) 的 pitch 是"抬头为负"
        // （rotToVec(p, y).y == -sin(p)），与 WeaponUnit 里 aimContext.direction.x 的约定一致。
        // 这里要求的是"向上 0°~+30°"，因此取负后再传入；传正值会得到向下的倒喷。
        return org.ywzj.vehicle.util.VectorUtil.rotToVec((float) -elevation, (float) yaw).normalize();
    }

    /**
     * 局部水平外法线旋转到世界后的水平方位角，单位度；忽略竖直分量，只取水平朝向。
     *
     * @param worldRotation 炮塔（或炮塔所属部件）的世界旋转
     * @param normal        局部水平外法线
     */
    public static double seamYaw(Quaternionf worldRotation, Vec3 normal) {
        Vec3 worldNormal = new Vec3(worldRotation.transform(normal.toVector3f()));
        // 调用本体向量转角，取得该外法线的世界方位角；竖直分量由后续俯仰随机决定
        return org.ywzj.vehicle.util.VectorUtil.vecToRot(
                new Vec3(worldNormal.x, 0.0, worldNormal.z).normalize()).y;
    }

    /** 沿炮塔底缘采样到的接缝点：局部坐标与局部水平外法线。 */
    public record SeamSample(double x, double z, double normalX, double normalZ) {

        /** 局部坐标（Y 由调用方按 OBB 底缘高度补全）。 */
        public Vec3 local(double y) {
            return new Vec3(this.x, y, this.z);
        }

        /** 局部水平外法线。 */
        public Vec3 normal() {
            return new Vec3(this.normalX, 0.0, this.normalZ);
        }
    }
}
