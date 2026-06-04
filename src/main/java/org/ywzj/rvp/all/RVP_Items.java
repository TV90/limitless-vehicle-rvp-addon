package org.ywzj.rvp.all;

import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.item.FixedProfileGunnerSpawnerItem;
import org.ywzj.rvp.item.GunnerSpawnerItem;

public class RVP_Items {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, RVP_MOD.MOD_ID);
    public static final RegistryObject<Item> GUNNER_SPAWNER = ITEMS.register("gunner_spawner",
            () -> new GunnerSpawnerItem(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> FRIENDLY_GUNNER = ITEMS.register("friendly_gunner",
            () -> new FixedProfileGunnerSpawnerItem(new Item.Properties().stacksTo(1), RVP_MOD.modLocation("friendly").toString(), true));
    public static final RegistryObject<Item> ENEMY_GUNNER = ITEMS.register("enemy_gunner",
            () -> new FixedProfileGunnerSpawnerItem(new Item.Properties().stacksTo(1), RVP_MOD.modLocation("enemy").toString(), false));
    public static final RegistryObject<Item> TEAM_GUNNER = ITEMS.register("team_gunner",
            () -> new FixedProfileGunnerSpawnerItem(new Item.Properties().stacksTo(1), RVP_MOD.modLocation("team").toString(), true));
    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
