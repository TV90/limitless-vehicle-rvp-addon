package org.ywzj.rvp.weapon.impact;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CVehicleImpactSparks;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.weapon.data.RVP_EffectsData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/** 载具/方块表面命中火花的服务端出口与双端共用尺寸规则；不参与伤害结算。 */
public final class RVP_VehicleImpactSparks {
    /** 命中点附近的广播半径，单位格；512 与 RVP 自研火星一致，覆盖最大视距。 */
    public static final double VIEW_RANGE = 512.0;
    /** 视觉口径上限，单位毫米；异常大口径不会无限增加粒子尺寸或数量。 */
    public static final float MAX_CALIBER = 200.0f;

    private RVP_VehicleImpactSparks() {}

    /** 命中火花的统一触发规则：只看武器视觉开关（默认开启），不推测名称或爆炸能力。 */
    public static boolean shouldEmit(RVP_EffectsData effects) {
        // 读取武器数据分组中的视觉开关；缺省为 true，显式写 false 的弹种仍可单独关闭。
        return effects != null && effects.isVehicleImpactSparks();
    }

    /** 实体命中的广播入口：仅载具表面触发，生物与弹体命中不产生火花。 */
    public static void publish(Level level, Entity target, RVP_WeaponData data, Vec3 position,
                               Vec3 normal, Vec3 incomingVelocity) {
        // 仅接受真实载具命中；方块命中走 publishBlock，避免同一接触重复广播。
        if (!(target instanceof AbstractVehicle)) {
            return;
        }
        publishSurface(level, data, position, normal, incomingVelocity);
    }

    /** 方块表面命中的广播入口：法线取自命中面，其余规则与载具命中一致。 */
    public static void publishBlock(Level level, RVP_WeaponData data, Vec3 position,
                                    Vec3 normal, Vec3 incomingVelocity) {
        publishSurface(level, data, position, normal, incomingVelocity);
    }

    /** 服务端只广播一次接触快照；客户端预测碰撞不会重复生成。 */
    private static void publishSurface(Level level, RVP_WeaponData data, Vec3 position,
                                       Vec3 normal, Vec3 incomingVelocity) {
        // 调用统一触发规则，仅接受服务端真实表面命中。
        if (!(level instanceof ServerLevel server) || data == null) {
            return;
        }
        // 调用武器数据访问器读取视觉分组，再由统一规则判断本次接触是否允许火花。
        RVP_EffectsData effects = data.getEffectsData();
        if (!shouldEmit(effects)) {
            return;
        }
        // 读取物理口径并保留跳弹前的入射方向；法线来自载具碰撞盒或方块命中面的实际朝向。
        S2CVehicleImpactSparks message = new S2CVehicleImpactSparks(server.dimension().location(),
                position, outwardNormal(normal), incomingVelocity.normalize(), sanitizeCaliber(effects.getCaliber()));
        PacketDistributor.TargetPoint point = new PacketDistributor.TargetPoint(
                position.x, position.y, position.z, VIEW_RANGE, server.dimension());
        RVP_Network.CHANNEL.send(PacketDistributor.NEAR.with(() -> point), message);
    }

    /** 非有限或非正口径回退 7.62 mm；小于 1 mm 及大于 200 mm 的视觉效果收敛到边界。 */
    public static float sanitizeCaliber(float caliber) {
        return Float.isFinite(caliber) && caliber > 0f ? Math.max(1f, Math.min(MAX_CALIBER, caliber)) : 7.62f;
    }

    /** 返回火星面片半宽，单位格；随口径连续增大，避免档位切换突然变大。 */
    public static float sparkSize(float caliber) {
        // 调用口径边界保护；平方根让机炮与坦克炮可区分而不过分膨胀。
        return 0.035f + 0.095f * (float) Math.sqrt(sanitizeCaliber(caliber) / 125f);
    }

    /** 单次命中的火星数量；25 mm 约 10 颗，125 mm 约 30 颗。 */
    public static int sparkCount(float caliber) {
        // 调用统一口径保护，数量上限 45 颗。
        return 5 + Math.round(sanitizeCaliber(caliber) / 5f);
    }

    /** 飞溅基础速度，单位格/tick；更大口径有更大的火星覆盖范围。 */
    public static double sparkSpeed(float caliber) {
        // 调用统一口径保护，避免异常配置制造极高速粒子。
        return 0.58 + 0.36 * Math.sqrt(sanitizeCaliber(caliber) / 125.0);
    }

    /** 表面法线归一化；零向量回退向上，防止火星全部堆在命中点。 */
    public static Vec3 outwardNormal(Vec3 normal) {
        return normal.lengthSqr() > 1.0E-12 ? normal.normalize() : new Vec3(0, 1, 0);
    }

    /**
     * 按真实入射方向和装甲板朝向采样飞溅；u、v 为 [0,1) 的均匀随机数。
     * 以镜面反射方向为中心：保留沿板面的运动，将朝板内的分量翻到板外。
     * 正面命中散射半角 60°，擦板命中收窄到 12°，因此越擦板越沿来弹前进方向飞溅。
     * 越过装甲面的随机方向折回外侧；不采用伤害计算的入射角钳制，也不改变弹体跳弹方向。
     */
    public static Vec3 sparkDirection(Vec3 normal, Vec3 incomingDirection, double u, double v) {
        // 调用统一法线处理；无速度时按正面命中回退，避免归一化零向量。
        Vec3 surface = outwardNormal(normal);
        Vec3 incoming = incomingDirection.lengthSqr() > 1.0E-12
                ? incomingDirection.normalize() : surface.scale(-1);
        // 防御性校正法线正反面：所选表面必须迎着来弹，不能把火星喷回装甲内部。
        if (incoming.dot(surface) > 0.0) {
            surface = surface.scale(-1);
        }
        double incidenceCosine = Math.max(0.0, Math.min(1.0, -incoming.dot(surface)));
        Vec3 axis = incoming.add(surface.scale(2.0 * incidenceCosine)).normalize();
        Vec3 helper = Math.abs(axis.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 tangent = axis.cross(helper).normalize();
        Vec3 bitangent = axis.cross(tangent);
        double coneHalfAngle = Math.toRadians(12.0 + 48.0 * incidenceCosine);
        // 对圆锥立体角均匀采样，避免火星过度堆在轴心或圆锥边缘。
        double cosine = 1.0 - u * (1.0 - Math.cos(coneHalfAngle));
        double sine = Math.sqrt(1.0 - cosine * cosine);
        double angle = 2.0 * Math.PI * v;
        Vec3 direction = axis.scale(cosine).add(tangent.scale(sine * Math.cos(angle)))
                .add(bitangent.scale(sine * Math.sin(angle)));
        double outward = direction.dot(surface);
        if (outward < 0.0) {
            direction = direction.subtract(surface.scale(2.0 * outward));
        }
        return direction.normalize();
    }
}
