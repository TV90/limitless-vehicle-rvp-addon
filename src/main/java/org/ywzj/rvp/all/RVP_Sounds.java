package org.ywzj.rvp.all;

import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.ywzj.rvp.RVP_MOD;

public final class RVP_Sounds {

    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, RVP_MOD.MOD_ID);

    public static final RegistryObject<SoundEvent> NUCLEAR_EXPLOSION = SOUNDS.register(
            "nuclear_explosion",
            () -> SoundEvent.createVariableRangeEvent(RVP_MOD.modLocation("nuclear_explosion")));

    public static final RegistryObject<SoundEvent> EXPLOSION_LARGE_NEAR = register("explosion_large_near");
    public static final RegistryObject<SoundEvent> EXPLOSION_LARGE_FAR = register("explosion_large_far");
    public static final RegistryObject<SoundEvent> EXPLOSION_SMALL_NEAR = register("explosion_small_near");
    public static final RegistryObject<SoundEvent> EXPLOSION_SMALL_FAR = register("explosion_small_far");

    /** 热焰弹抛洒音效（assets/rvp/sounds/misc/flare.ogg）。 */
    public static final RegistryObject<SoundEvent> COUNTERMEASURE_FLARE = register("countermeasure_flare");
    /** 箔条抛洒音效（assets/rvp/sounds/misc/chaff.ogg）。 */
    public static final RegistryObject<SoundEvent> COUNTERMEASURE_CHAFF = register("countermeasure_chaff");
    /** IR/AIR 导弹追踪告警音效（assets/rvp/sounds/misc/ir_alert.ogg）。 */
    public static final RegistryObject<SoundEvent> IR_ALERT = register("ir_alert");

    private RVP_Sounds() {}

    public static void register(IEventBus eventBus) {
        SOUNDS.register(eventBus);
    }

    private static RegistryObject<SoundEvent> register(String name) {
        return SOUNDS.register(name,
                () -> SoundEvent.createVariableRangeEvent(RVP_MOD.modLocation(name)));
    }
}
