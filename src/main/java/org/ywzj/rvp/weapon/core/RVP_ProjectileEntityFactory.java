package org.ywzj.rvp.weapon.core;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.entity.projectile.RVP_BombEntity;
import org.ywzj.rvp.entity.projectile.RVP_BulletEntity;
import org.ywzj.rvp.entity.projectile.RVP_DispensedEntity;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.entity.projectile.RVP_RocketEntity;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;

/** 载具射击、炮火投送与 RVP 子弹药共用的 kind → 实体构造工厂。 */
public final class RVP_ProjectileEntityFactory {
    private RVP_ProjectileEntityFactory() {}

    /** @return 该 kind 是否对应真实 RVP 弹体实体。 */
    public static boolean supports(@Nullable RVP_EnumWeaponKind kind) {
        return kind != null && kind != RVP_EnumWeaponKind.LASER && kind != RVP_EnumWeaponKind.TARGETING_POD;
    }

    /** @return 该 kind 的 RVP 实体实现类；不支持时返回 null，供无世界单测审计完整映射。 */
    @Nullable
    public static Class<? extends RVP_BaseBullet> projectileClassFor(@Nullable RVP_EnumWeaponKind kind) {
        if (kind == null) return null;
        return switch (kind) {
            case MISSILE -> RVP_MissileEntity.class;
            case ROCKET -> RVP_RocketEntity.class;
            case MACHINEGUN -> RVP_BulletEntity.class;
            case BOMB -> RVP_BombEntity.class;
            case DISPENSER -> RVP_DispensedEntity.class;
            case LASER, TARGETING_POD -> null;
        };
    }

    /** 调用本项目实体注册表，为指定 kind 解析类型化 RVP EntityType。 */
    @Nullable
    public static EntityType<? extends Projectile> entityTypeFor(@Nullable RVP_EnumWeaponKind kind) {
        if (kind == null) return null;
        return switch (kind) {
            case MISSILE -> RVP_Entities.RVP_MISSILE.get();
            case ROCKET -> RVP_Entities.RVP_ROCKET.get();
            case MACHINEGUN -> RVP_Entities.RVP_BULLET.get();
            case BOMB -> RVP_Entities.RVP_BOMB.get();
            case DISPENSER -> RVP_Entities.RVP_DISPENSED.get();
            case LASER, TARGETING_POD -> null;
        };
    }

    /** 创建尚未加入世界的类型化 RVP 弹体；弹体配置仍由生成器统一初始化。 */
    @Nullable
    public static RVP_BaseBullet create(RVP_EnumWeaponKind kind,
                                        @Nullable EntityType<? extends Projectile> explicitType,
                                        Level level, RVP_WeaponData data) {
        if (!supports(kind) || level == null || data == null) return null;
        EntityType<? extends Projectile> type = explicitType != null ? explicitType : entityTypeFor(kind);
        if (type == null) return null;
        ResourceLocation weaponId = data.getWeaponId();
        return switch (kind) {
            case MISSILE -> new RVP_MissileEntity(type, level, weaponId);
            case ROCKET -> new RVP_RocketEntity(type, level, weaponId);
            case MACHINEGUN -> new RVP_BulletEntity(type, level, weaponId);
            case BOMB -> new RVP_BombEntity(type, level, weaponId);
            case DISPENSER -> new RVP_DispensedEntity(type, level, weaponId);
            case LASER, TARGETING_POD -> null;
        };
    }
}
