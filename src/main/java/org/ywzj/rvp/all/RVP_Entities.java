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
                    .clientTrackingRange(16)
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
