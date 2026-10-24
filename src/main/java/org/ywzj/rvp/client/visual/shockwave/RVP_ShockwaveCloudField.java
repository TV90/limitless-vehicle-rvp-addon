package org.ywzj.rvp.client.visual.shockwave;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import java.util.Random;

/**
 * 从核爆视觉抽出的云团运动、颜色与插值算法，不访问世界或渲染器。
 * 源算法改编自 HBM NTM Rebirth（LGPL-3.0），随项目 GPL-3.0 分发；见 THIRD_PARTY_NOTICES.md。
 */
public final class RVP_ShockwaveCloudField {
    private RVP_ShockwaveCloudField() { }

    /** 云团形态；垂直云柱使用 STANDARD 并限制运动倍率。 */
    public enum CloudType {
        /** 上升翻滚云与云柱支撑。 */ STANDARD,
        /** 贴地冲击尘云。 */ SHOCK,
        /** 围绕云柱的环云。 */ RING,
        /** 白色凝结云带。 */ CONDENSATION
    }

    /** 调用方提供的外观参数；各效果独立控制寿命、尺寸和灰化。 */
    public interface Appearance {
        /** 世界爆心。 */ Vec3 center();
        /** 云团视觉缩放。 */ float cloudScale();
        /** 效果年龄（tick）。 */ int age();
        /** 效果寿命（tick）。 */ int maxAge();
        /** 云团灰化倍率。 */ double greying();
    }

    /**
     * 每 tick 共享的只读运动输入，坐标与长度均以格计。
     * @param centerX 爆心 X
     * @param centerY 爆心 Y
     * @param centerZ 爆心 Z
     * @param coreHeight 云柱高度
     * @param torusWidth 翻滚环半径
     * @param rollerSize 翻滚截面半径
     * @param convectionHeight 上升目标高度
     * @param simulationSpeed 单 tick 运动倍率
     * @param heat 云团亮度热源参数（视觉量）
     * @param effectAge 整体效果年龄（tick）
     */
    public record UpdateContext(double centerX, double centerY, double centerZ,
            double coreHeight, double torusWidth, double rollerSize, double convectionHeight,
            double simulationSpeed, double heat, int effectAge) { }

    public static final class Cloud {
        /** 当前世界 X 坐标。 */
        private double x;
        /** 当前世界 Y 坐标。 */
        private double y;
        /** 当前世界 Z 坐标。 */
        private double z;
        /** 上一 tick 的 X 坐标，供绘制插值。 */
        private double previousX;
        /** 上一 tick 的 Y 坐标。 */
        private double previousY;
        /** 上一 tick 的 Z 坐标。 */
        private double previousZ;
        /** 当前横向 X 运动。 */
        private double motionX;
        /** 当前竖直运动。 */
        private double motionY;
        /** 当前横向 Z 运动。 */
        private double motionZ;
        /** 云团径向角度（弧度）。 */
        private final float angle;
        /** 云团寿命（tick）。 */
        private final int lifetime;
        /** 云团形态。 */
        private final CloudType type;
        /** 初始半尺寸（格）。 */
        private final float startScale;
        /** 寿命内增加的半尺寸（格）。 */
        private final float growScale;
        /** 确定性翻滚半径扰动。 */
        private final float rangeMod;
        /** 确定性亮度扰动。 */
        private final float colorMod;
        /** 固定抽样值，仅由调用方决定是否用于核爆细节筛选。 */
        public final float renderSample;
        /** 运动倍率；云柱支撑点可以设为 0。 */
        public double motionMult = 1.0D;
        /** 当前云团颜色分量。 */
        private double colorR = 0.25D;
        /** 当前云团颜色分量。 */
        private double colorG = 0.25D;
        /** 当前云团颜色分量。 */
        private double colorB = 0.25D;
        /** 上一帧颜色分量，供插值。 */
        private double previousColorR = 0.25D;
        /** 上一帧颜色分量，供插值。 */
        private double previousColorG = 0.25D;
        /** 上一帧颜色分量，供插值。 */
        private double previousColorB = 0.25D;
        /** 本次运动计算的临时分量。 */
        private double computedMotionX;
        /** 本次运动计算的临时分量。 */
        private double computedMotionY;
        /** 本次运动计算的临时分量。 */
        private double computedMotionZ;
        /** 已存活 tick 数。 */
        private int age;
        /** 寿命结束标记。 */
        public boolean dead;

        public Cloud(double x, double y, double z, float angle, int lifetime, CloudType type,
                float startScale, float growScale, Random random) {
            this.x = x;
            this.y = y;
            this.z = z;
            previousX = x;
            previousY = y;
            previousZ = z;
            this.angle = angle;
            this.lifetime = Math.max(1, lifetime);
            this.type = type;
            this.startScale = startScale;
            this.growScale = growScale;
            rangeMod = 0.3F + random.nextFloat() * 0.7F;
            colorMod = 0.8F + random.nextFloat() * 0.2F;
            long hash = stableHash(x, y, z, angle, type.ordinal());
            renderSample = (hash & 0xFFFFFFL) / 16777216.0F;
        }

        public void update(UpdateContext context) {
            age++;
            if (age > lifetime) {
                dead = true;
                return;
            }
            previousX = x;
            previousY = y;
            previousZ = z;
            double simDeltaX = context.centerX - x;
            double simDeltaZ = context.centerZ - z;
            double simPosX = context.centerX + Math.sqrt(simDeltaX * simDeltaX + simDeltaZ * simDeltaZ);
            double simPosZ = context.centerZ;

            if (type == CloudType.STANDARD) {
                computeConvectionMotion(context, simPosX, simPosZ);
                double convectionX = computedMotionX;
                double convectionY = computedMotionY;
                double convectionZ = computedMotionZ;
                computeLiftMotion(context, simPosX);
                double liftScale = Mth.clamp(1.0D - (simPosX - (context.centerX + context.torusWidth)), 0.0D, 1.0D);
                computedMotionX *= liftScale;
                computedMotionY *= liftScale;
                computedMotionZ *= liftScale;
                double factor = Mth.clamp((y - context.centerY) / Math.max(context.coreHeight, 0.001D), 0.0D, 1.0D);
                motionX = convectionX * factor + computedMotionX * (1.0D - factor);
                motionY = convectionY * factor + computedMotionY * (1.0D - factor);
                motionZ = convectionZ * factor + computedMotionZ * (1.0D - factor);
            } else if (type == CloudType.SHOCK) {
                double factor = Mth.clamp((y - context.centerY) / Math.max(context.coreHeight, 0.001D), 0.0D, 1.0D);
                motionX = Mth.cos(angle) * factor;
                motionY = 0.0D;
                motionZ = -Mth.sin(angle) * factor;
            } else if (type == CloudType.RING) {
                computeRingMotion(context, simPosX, simPosZ);
                motionX = computedMotionX;
                motionY = computedMotionY;
                motionZ = computedMotionZ;
            } else {
                double speed = 0.00002D * context.effectAge;
                motionX = (x - context.centerX) * speed;
                motionY = 0.0D;
                motionZ = (z - context.centerZ) * speed;
            }
            double mult = motionMult * context.simulationSpeed;
            x += motionX * mult;
            y += motionY * mult;
            z += motionZ * mult;
            updateColor(context);
        }

        private void computeLiftMotion(UpdateContext context, double simPosX) {
            setNormalizedMotion(context.centerX - x,
                    context.centerY + context.convectionHeight - y,
                    context.centerZ - z);
        }

        private void computeConvectionMotion(UpdateContext context, double simPosX, double simPosZ) {
            computeTorusMotion(context, simPosX, simPosZ,
                    context.centerY + context.coreHeight, context.rollerSize * rangeMod, 1.0D);
        }

        private void computeRingMotion(UpdateContext context, double simPosX, double simPosZ) {
            if (simPosX > context.centerX + context.torusWidth * 2.0D) {
                setComputedMotion(0.0D, 0.0D, 0.0D);
                return;
            }
            computeTorusMotion(context, simPosX, simPosZ,
                    context.centerY + context.coreHeight * 0.42D,
                    context.rollerSize * rangeMod * 0.25D, 0.001D);
        }

        private void computeTorusMotion(UpdateContext context, double simPosX, double simPosZ,
                double targetY, double roller, double targetScale) {
            if (roller < 1.0E-4D) {
                setComputedMotion(0.0D, 0.0D, 0.0D);
                return;
            }
            double targetX = context.centerX + context.torusWidth;
            double deltaX = targetX - simPosX;
            double deltaY = targetY - y;
            double deltaZ = context.centerZ - simPosZ;
            double dist = Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ) / roller - 1.0D;
            if (Math.abs(dist) < 1.0E-6D) {
                setComputedMotion(0.0D, 0.0D, 0.0D);
                return;
            }
            double func = 1.0D - Math.exp(-dist);
            float turn = (float) (func * Math.PI * 0.5D);
            double rotX = -deltaX / dist;
            double rotY = -deltaY / dist;
            double rotZ = -deltaZ / dist;
            double cos = Mth.cos(turn);
            double sin = Mth.sin(turn);
            double rotatedX = rotX * cos + rotY * sin;
            double rotatedY = rotY * cos - rotX * sin;
            setNormalizedMotion(
                    (targetX + rotatedX - simPosX) * targetScale,
                    (targetY + rotatedY - y) * targetScale,
                    (context.centerZ + rotZ - simPosZ) * targetScale);
            double outX = computedMotionX * Mth.cos(angle) + computedMotionZ * Mth.sin(angle);
            double outZ = computedMotionZ * Mth.cos(angle) - computedMotionX * Mth.sin(angle);
            computedMotionX = outX;
            computedMotionZ = outZ;
        }

        private void setNormalizedMotion(double x, double y, double z) {
            double length = Math.sqrt(x * x + y * y + z * z);
            if (length < 1.0E-4D) {
                setComputedMotion(0.0D, 0.0D, 0.0D);
                return;
            }
            setComputedMotion(x / length, y / length, z / length);
        }

        private void setComputedMotion(double x, double y, double z) {
            computedMotionX = x;
            computedMotionY = y;
            computedMotionZ = z;
        }

        private void updateColor(UpdateContext context) {
            previousColorR = colorR;
            previousColorG = colorG;
            previousColorB = colorB;
            double dx = context.centerX - x;
            double dy = context.centerY + context.coreHeight - y;
            double dz = context.centerZ - z;
            double distSq = (dx * dx + dy * dy + dz * dz) / Math.max(context.heat, 0.001D);
            double col = 2.0D / Math.max(Math.sqrt(distSq), 1.0D);
            colorR = Math.max(col * 2.0D, 0.25D);
            colorG = Math.max(col * 1.5D, 0.25D);
            colorB = Math.max(col * 0.5D, 0.25D);
        }

        public boolean decorative() {
            return type == CloudType.SHOCK || type == CloudType.CONDENSATION;
        }

        public float scale(Appearance effect) {
            float base = startScale + age / (float) lifetime * growScale;
            return type == CloudType.SHOCK ? base : base * effect.cloudScale();
        }

        public float alpha(Appearance effect) {
            float local = 1.0F - Mth.clamp(age / (float) lifetime, 0.0F, 1.0F);
            float global = effect.age() <= effect.maxAge() * 0.75F
                    ? 1.0F
                    : 1.0F - (effect.age() - effect.maxAge() * 0.75F) / (effect.maxAge() * 0.25F);
            float alpha = Mth.clamp(local * global, 0.0F, 1.0F);
            return type == CloudType.CONDENSATION ? alpha * 0.25F : alpha;
        }

        public void writeColor(Appearance effect, float partialTick, float[] output) {
            if (type == CloudType.CONDENSATION) {
                output[0] = 0.9F;
                output[1] = 0.9F;
                output[2] = 0.9F;
                return;
            }
            double greying = effect.greying() + (type == CloudType.RING ? 1.0D : 0.0D);
            float brightness = 0.75F * colorMod;
            output[0] = Mth.clamp((float) (Mth.lerp(partialTick, previousColorR, colorR) * greying) * brightness, 0.0F, 1.0F);
            output[1] = Mth.clamp((float) (Mth.lerp(partialTick, previousColorG, colorG) * greying) * brightness, 0.0F, 1.0F);
            output[2] = Mth.clamp((float) (Mth.lerp(partialTick, previousColorB, colorB) * greying) * brightness, 0.0F, 1.0F);
        }

        public double renderX(Appearance effect, float partialTick) {
            double value = Mth.lerp(partialTick, previousX, x);
            return type == CloudType.SHOCK ? value : (value - effect.center().x) * effect.cloudScale() + effect.center().x;
        }

        public double renderY(Appearance effect, float partialTick) {
            double value = Mth.lerp(partialTick, previousY, y);
            return type == CloudType.SHOCK ? value : (value - effect.center().y) * effect.cloudScale() + effect.center().y;
        }

        public double renderZ(Appearance effect, float partialTick) {
            double value = Mth.lerp(partialTick, previousZ, z);
            return type == CloudType.SHOCK ? value : (value - effect.center().z) * effect.cloudScale() + effect.center().z;
        }

        private static long stableHash(double x, double y, double z, float angle, int type) {
            long hash = Double.doubleToLongBits(x);
            hash = hash * 31L + Double.doubleToLongBits(y);
            hash = hash * 31L + Double.doubleToLongBits(z);
            hash = hash * 31L + Float.floatToIntBits(angle);
            hash = hash * 31L + type;
            hash ^= hash >>> 33;
            hash *= 0xff51afd7ed558ccdL;
            hash ^= hash >>> 33;
            return hash;
        }
    }
}
