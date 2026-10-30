package org.ywzj.rvp.weapon.sound;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.entity.projectile.RVP_BulletEntity;
import org.ywzj.rvp.weapon.data.RVP_EffectsData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;

/** 小口径弹体声音的口径、爆炸和路径判定；不引用任何客户端类型。 */
public final class RVP_SmallCaliberSoundPolicy {

    /** 小口径上限，单位毫米；45 毫米本身不在范围内。 */
    public static final float CALIBER_LIMIT_MM = 45.0F;
    /** 大爆炸不叠加普通方块命中声的半径下限，单位格。 */
    public static final float EXPLOSION_RADIUS_LIMIT = 1.0F;
    /** 玩家附近的划空声触发距离，单位格。 */
    public static final double INCOMING_DISTANCE = 8.0D;
    /** 方块命中声向附近客户端发送的最大距离，单位格。 */
    public static final double IMPACT_DISTANCE = 32.0D;
    /** 避免零长度飞行段参与投影计算的最小长度平方。 */
    private static final double MIN_SEGMENT_LENGTH_SQUARED = 1.0E-12D;

    private RVP_SmallCaliberSoundPolicy() {
    }

    /** 检查有限、正数且小于 45 毫米的物理口径。 */
    public static boolean isSmallCaliber(float caliber) {
        return Float.isFinite(caliber) && caliber > 0.0F && caliber < CALIBER_LIMIT_MM;
    }

    /**
     * 按实际弹体数据判断口径。
     * 机枪/机炮弹使用已同步的物理口径；其他类型必须显式配置物理口径，
     * 避免导弹和炸弹因默认 7.62 毫米而被误认作小口径。
     */
    public static boolean isSmallProjectile(@Nullable RVP_BaseBullet projectile) {
        if (projectile == null) {
            return false;
        }
        if (projectile instanceof RVP_BulletEntity bullet) {
            // 调用 RVP 机枪弹物理口径入口，不使用仅控制曳光宽度的 tracer_caliber。
            return isSmallCaliber(bullet.getCaliber());
        }
        // 调用本项目弹体配置解析器；非机枪弹不把缺省 7.62 当作真实口径。
        RVP_WeaponData data = projectile.getResolvedWeaponConfig();
        if (data == null) {
            return false;
        }
        // 调用本项目效果数据入口，仅读取明确配置的物理口径。
        RVP_EffectsData effects = data.getEffectsData();
        return effects.hasCaliberOverride() && isSmallCaliber(effects.getCaliber());
    }

    /** 仅爆炸已启用且半径至少 1 格时屏蔽方块命中声。 */
    public static boolean allowsBlockImpact(float caliber, boolean explosionEnabled, float explosionRadius) {
        return isSmallCaliber(caliber)
                && !(explosionEnabled && explosionRadius >= EXPLOSION_RADIUS_LIMIT);
    }

    /** 穿透计算可能把起点推进到段末之外；此时不能把反向线段当成弹体飞行路径。 */
    public static boolean isForwardSegment(Vec3 start, Vec3 end, Vec3 velocity) {
        return finite(start) && finite(end) && finite(velocity)
                && end.subtract(start).dot(velocity) > MIN_SEGMENT_LENGTH_SQUARED;
    }

    /**
     * 计算飞行段中靠近听者的声音位置；弹体已远离听者或整段超过范围时返回 null。
     * 使用整段投影，保证单 Tick 跨越玩家附近的高速弹也能触发。
     */
    @Nullable
    public static Vec3 incomingPosition(Vec3 start, Vec3 end, Vec3 listener, double distance) {
        if (!finite(start) || !finite(end) || !finite(listener)
                || !Double.isFinite(distance) || distance <= 0.0D) {
            return null;
        }
        Vec3 direction = end.subtract(start);
        double lengthSquared = direction.lengthSqr();
        if (!Double.isFinite(lengthSquared) || lengthSquared < MIN_SEGMENT_LENGTH_SQUARED) {
            return null;
        }
        double projection = listener.subtract(start).dot(direction) / lengthSquared;
        if (projection <= 0.0D) {
            return null;
        }
        Vec3 nearest = start.add(direction.scale(Math.min(projection, 1.0D)));
        return nearest.distanceToSqr(listener) <= distance * distance ? nearest : null;
    }

    /** 防止非法坐标进入距离和网络计算。 */
    private static boolean finite(@Nullable Vec3 point) {
        return point != null && Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z);
    }
}
