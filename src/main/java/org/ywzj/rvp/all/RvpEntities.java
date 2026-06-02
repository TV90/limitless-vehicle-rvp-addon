package org.ywzj.rvp.all;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.ywzj.rvp.YwzjRvp;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.rvp.entity.weapon.GPSBombEntity;
import org.ywzj.rvp.entity.weapon.AntiRadiationMissileEntity;
import org.ywzj.rvp.entity.weapon.TVMissileEntity;

public class RvpEntities {

    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, YwzjRvp.MOD_ID);

    public static final RegistryObject<EntityType<GPSBombEntity>> GPS_BOMB = ENTITIES.register("gps_bomb",
            () -> EntityType.Builder.<GPSBombEntity>of(GPSBombEntity::new, MobCategory.MISC)
                    .noSummon()
                    .noSave()
                    .fireImmune()
                    .sized(1F, 1F)
                    .clientTrackingRange(16)
                    .updateInterval(1)
                    .setShouldReceiveVelocityUpdates(false)
                    .setCustomClientFactory(GPSBombEntity::new)
                    .build("gps_bomb"));

    public static final RegistryObject<EntityType<AntiRadiationMissileEntity>> ANTI_RADIATION_MISSILE = ENTITIES.register("anti_radiation_missile",
            () -> EntityType.Builder.<AntiRadiationMissileEntity>of(AntiRadiationMissileEntity::new, MobCategory.MISC)
                    .noSummon()
                    .noSave()
                    .fireImmune()
                    .sized(1F, 1F)
                    .clientTrackingRange(16)
                    .updateInterval(1)
                    .setShouldReceiveVelocityUpdates(false)
                    .setCustomClientFactory(AntiRadiationMissileEntity::new)
                    .build("anti_radiation_missile"));

    public static final RegistryObject<EntityType<TVMissileEntity>> TV_MISSILE = ENTITIES.register("tv_missile",
            () -> EntityType.Builder.<TVMissileEntity>of(TVMissileEntity::new, MobCategory.MISC)
                    .noSummon()
                    .noSave()
                    .fireImmune()
                    .sized(1F, 1F)
                    .clientTrackingRange(16)
                    .updateInterval(1)
                    .setShouldReceiveVelocityUpdates(false)
                    .setCustomClientFactory(TVMissileEntity::new)
                    .build("tv_missile"));

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
        eventBus.register(RvpEntities.class);
    }
}
