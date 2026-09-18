package org.ywzj.rvp.weapon.core;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * 载具射击与无载具投送共用的不可变弹体生成上下文。
 * 可变的 {@code Player}/{@code ItemStack} 等任务状态不得缓存于此对象。
 */
public record RVP_ProjectileSpawnContext(
        /** 生成实体的服务端世界。 */ ServerLevel level,
        /** 已从权威武器索引解析的 RVP 配置。 */ RVP_WeaponData weaponData,
        /** 决定实体实现和运行时行为的 RVP 武器类型。 */ RVP_EnumWeaponKind weaponKind,
        /** 显式实体类型；载具旧入口保留其注册器，null 时由统一工厂按 kind 解析。 */
        @Nullable EntityType<? extends Projectile> entityType,
        /** 真实发射载具；炮火支援为 null。 */ @Nullable AbstractVehicle sourceVehicle,
        /** 归属/可编程引信所用武器单元；炮火支援为 null。 */ @Nullable WeaponUnit sourceWeaponUnit,
        /** 冷发射和线导挂接单元；炮火支援为 null。 */ @Nullable WeaponUnit launchWeaponUnit,
        /** 弹体伤害、击杀和子弹药继承的权威 owner。 */ LivingEntity owner,
        /** 实体初始世界坐标。 */ Vec3 spawnPosition,
        /** 与初始速度一致的俯仰/偏航角，单位度。 */ RVP_BaseBullet.AimRot aim,
        /** 不含可选载具速度继承的初始运动，单位格/Tick。 */ Vec3 initialMotion,
        /** 可选实体锁定目标；无载具炮火首版不提供。 */ @Nullable Entity lockTarget,
        /** 可选坐标指示目标；无制导垂直投送为 null。 */ @Nullable Vec3 designatedTarget,
        /** 是否在初始运动上叠加 sourceVehicle 的速度。 */ boolean inheritVehicleVelocity,
        /** 是否从 sourceWeaponUnit 读取武器站可编程空爆距离。 */ boolean bindProgrammableAirburst,
        /** 线导挂接使用的真实管口坐标；无挂接时为 null。 */ @Nullable Vec3 wireLaunchFrom,
        /** 弹体动态 Chunk 路径策略；炮火远程 Bullet 必须显式选择远程策略。 */
        RVP_ProjectileChunkLoadingPolicy chunkLoadingPolicy,
        /** 观瞄视角射弹原点分离伪装数据；无伪装（普通出弹/炮火支援）为 null。 */
        @Nullable org.ywzj.rvp.sight.RVP_SightFireDisguise sightFireDisguise) {

    public RVP_ProjectileSpawnContext {
        if (level == null || weaponData == null || weaponKind == null || owner == null
                || spawnPosition == null || aim == null || initialMotion == null || chunkLoadingPolicy == null) {
            throw new IllegalArgumentException("弹体生成上下文的世界、武器、owner、坐标、姿态和速度不能为空");
        }
        if (!finite(spawnPosition) || !finite(initialMotion)
                || !Float.isFinite(aim.xRot()) || !Float.isFinite(aim.yRot())) {
            throw new IllegalArgumentException("弹体生成坐标、姿态和速度必须为有限数值");
        }
        if (owner.level() != level) {
            throw new IllegalArgumentException("弹体 owner 必须位于生成世界");
        }
        if (sourceVehicle != null && sourceVehicle.level() != level) {
            throw new IllegalArgumentException("发射载具必须位于生成世界");
        }
    }

    /**
     * 旧参兼容重载（无观瞄伪装）：炮火支援等无载具投送调用点签名不变，伪装数据为 null。
     */
    public RVP_ProjectileSpawnContext(ServerLevel level, RVP_WeaponData weaponData,
                                      RVP_EnumWeaponKind weaponKind,
                                      @Nullable EntityType<? extends Projectile> entityType,
                                      @Nullable AbstractVehicle sourceVehicle,
                                      @Nullable WeaponUnit sourceWeaponUnit,
                                      @Nullable WeaponUnit launchWeaponUnit,
                                      LivingEntity owner, Vec3 spawnPosition,
                                      RVP_BaseBullet.AimRot aim, Vec3 initialMotion,
                                      @Nullable Entity lockTarget, @Nullable Vec3 designatedTarget,
                                      boolean inheritVehicleVelocity, boolean bindProgrammableAirburst,
                                      @Nullable Vec3 wireLaunchFrom,
                                      RVP_ProjectileChunkLoadingPolicy chunkLoadingPolicy) {
        this(level, weaponData, weaponKind, entityType, sourceVehicle, sourceWeaponUnit, launchWeaponUnit,
                owner, spawnPosition, aim, initialMotion, lockTarget, designatedTarget,
                inheritVehicleVelocity, bindProgrammableAirburst, wireLaunchFrom, chunkLoadingPolicy, null);
    }

    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
}
