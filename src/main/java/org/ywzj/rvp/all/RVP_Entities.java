package org.ywzj.rvp.all;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.network.PlayMessages;
import net.minecraftforge.registries.RegistryObject;

import java.util.function.BiFunction;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.rvp.entity.projectile.RVP_BombEntity;
import org.ywzj.rvp.entity.projectile.RVP_BulletEntity;
import org.ywzj.rvp.entity.projectile.RVP_DispensedEntity;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.entity.projectile.RVP_RocketEntity;
import org.ywzj.rvp.countermeasure.RVP_DecoyEntity;
import org.ywzj.rvp.countermeasure.RVP_SmokeEntity;

public class RVP_Entities {

    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, RVP_MOD.MOD_ID);

    public static final RegistryObject<EntityType<RVP_MissileEntity>> RVP_MISSILE =
            registerRvpProjectile("rvp_missile", RVP_MissileEntity::new, RVP_MissileEntity::new);

    public static final RegistryObject<EntityType<RVP_RocketEntity>> RVP_ROCKET =
            registerRvpProjectile("rvp_rocket", RVP_RocketEntity::new, RVP_RocketEntity::new);

    /**
     * Same tracking policy as official {@link org.ywzj.vehicle.entity.weapon.BulletEntity}:
     * client integrates from spawn velocity; do not apply network velocity packets (avoids jitter).
     */
    public static final RegistryObject<EntityType<RVP_BulletEntity>> RVP_BULLET =
            ENTITIES.register("rvp_bullet", () -> EntityType.Builder.<RVP_BulletEntity>of(RVP_BulletEntity::new, MobCategory.MISC)
                    .noSummon()
                    .noSave()
                    .fireImmune()
                    .sized(0.0625F, 0.0625F)
                    .clientTrackingRange(256)
                    .updateInterval(5)
                    .setShouldReceiveVelocityUpdates(false)
                    .setCustomClientFactory(RVP_BulletEntity::new)
                    .build("rvp_bullet"));

    public static final RegistryObject<EntityType<RVP_BombEntity>> RVP_BOMB =
            registerRvpProjectile("rvp_bomb", RVP_BombEntity::new, RVP_BombEntity::new);

    public static final RegistryObject<EntityType<RVP_DispensedEntity>> RVP_DISPENSED =
            registerRvpProjectile("rvp_dispensed", RVP_DispensedEntity::new, RVP_DispensedEntity::new);

    /**
     * 干扰物实体（热焰弹 / 箔条共用）。双端：服务端生成并运动，客户端渲染；
     * 不参与碰撞 / 不可拾取，小碰撞箱仅供导引头 / 雷达按类型统计。
     */
    public static final RegistryObject<EntityType<RVP_DecoyEntity>> RVP_DECOY =
            ENTITIES.register("rvp_decoy", () -> EntityType.Builder.<RVP_DecoyEntity>of(RVP_DecoyEntity::new, MobCategory.MISC)
                    .noSummon()
                    .noSave()
                    .fireImmune()
                    .sized(0.2F, 0.2F)
                    .clientTrackingRange(64)
                    .updateInterval(2)
                    .setShouldReceiveVelocityUpdates(false)
                    .setCustomClientFactory(RVP_DecoyEntity::new)
                    .build("rvp_decoy"));

    /**
     * 被动电子战假目标诱饵。隐形雷达幻影（世界内不渲染），AABB 按小型载具量级
     * 以通过本体雷达扫描过滤；可被弹药命中（击落反馈）。
     */
    public static final RegistryObject<EntityType<org.ywzj.rvp.entity.ecm.RVP_EcmDecoyEntity>> RVP_ECM_DECOY =
            ENTITIES.register("rvp_ecm_decoy", () -> EntityType.Builder.<org.ywzj.rvp.entity.ecm.RVP_EcmDecoyEntity>of(org.ywzj.rvp.entity.ecm.RVP_EcmDecoyEntity::new, MobCategory.MISC)
                    .noSummon()
                    .noSave()
                    .fireImmune()
                    .sized(1.5F, 1.5F)
                    .clientTrackingRange(128)
                    .updateInterval(4)
                    .setShouldReceiveVelocityUpdates(false)
                    .setCustomClientFactory(org.ywzj.rvp.entity.ecm.RVP_EcmDecoyEntity::new)
                    .build("rvp_ecm_decoy"));

    /**
     * 烟雾云实体（地面载具干扰物）。大 AABB（半径随时间膨胀，禁视区），跟踪范围放宽
     * 以便远处玩家看到烟幕；存活/目标半径经 SynchedEntityData 随生成包同步。
     */
    public static final RegistryObject<EntityType<RVP_SmokeEntity>> RVP_SMOKE =
            ENTITIES.register("rvp_smoke", () -> EntityType.Builder.<RVP_SmokeEntity>of(RVP_SmokeEntity::new, MobCategory.MISC)
                    .noSummon()
                    .noSave()
                    .fireImmune()
                    .sized(1.0F, 1.0F)
                    .clientTrackingRange(256)
                    .updateInterval(2)
                    .setShouldReceiveVelocityUpdates(false)
                    .setCustomClientFactory(RVP_SmokeEntity::new)
                    .build("rvp_smoke"));

    private static <T extends net.minecraft.world.entity.Entity> RegistryObject<EntityType<T>> registerRvpProjectile(
            String id,
            EntityType.EntityFactory<T> factory,
            BiFunction<PlayMessages.SpawnEntity, net.minecraft.world.level.Level, T> clientFactory) {
        return ENTITIES.register(id, () -> EntityType.Builder.<T>of(factory, MobCategory.MISC)
                .noSummon()
                .noSave()
                .fireImmune()
                .sized(0.0625F, 0.0625F)
                .clientTrackingRange(256)
                .updateInterval(1)
                .setShouldReceiveVelocityUpdates(false)
                .setCustomClientFactory(clientFactory)
                .build(id));
    }

    public static final RegistryObject<EntityType<GunnerEntity>> GUNNER = ENTITIES.register("gunner",
            () -> EntityType.Builder.of(GunnerEntity::new, MobCategory.CREATURE)
                    .sized(0.6f, 1.8f)
                    .clientTrackingRange(16)   // 骑乘时共享载具追踪范围；独立存在时16格
                    .updateInterval(1)
                    .build("gunner"));

    @SubscribeEvent
    public static void onEntityAttributeCreationEvent(EntityAttributeCreationEvent event) {
        event.put(GUNNER.get(), GunnerEntity.createAttributes().build());
    }

    public static void register(IEventBus eventBus) {
        ENTITIES.register(eventBus);
        eventBus.register(RVP_Entities.class);
    }
}
