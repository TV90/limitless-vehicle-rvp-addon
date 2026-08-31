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
    /** 主动ECM 干扰音效（assets/ywzj_rvp/sounds/misc/ecm_jammer.ogg）。 */
    public static final RegistryObject<SoundEvent> ECM_JAMMER = register("ecm_jammer");
    /** IR/AIR 导弹追踪告警音效（assets/rvp/sounds/misc/ir_alert.ogg）。 */
    public static final RegistryObject<SoundEvent> IR_ALERT = register("ir_alert");
    public static final RegistryObject<SoundEvent> LASER_ALERT = register("laser_alert");

    /**
     * IR 导引头锁定提示音（循环播放，锁定建立时起、脱锁时止）。
     * 移植自本体 {@code ir_track_alarm}（本体在 {@code WeaponUnit#setLockedEntity} 中
     * 仅当静态 {@code fire_control_sensor_type == IR} 时播放，而 RVP 载具部件从不配置 IR，
     * 故该分支在 RVP 侧永不触发，由 RVP 自行判定）。
     */
    public static final RegistryObject<SoundEvent> IR_TRACK_ALARM = register("ir_track_alarm");

    /**
     * 炸弹/航弹飞行时的划破空气哨音。移植自本体 {@code bomb_whistle}
     * （本体在 {@code AerialBombEntity#tickSound} 中于玩家 32 格内播放；
     * RVP 炸弹继承 {@code RVP_BaseBullet} 而非 {@code AerialBombEntity}，故需自行实现）。
     */
    public static final RegistryObject<SoundEvent> BOMB_WHISTLE = register("bomb_whistle");

    /**
     * 炸弹临近命中的来袭轰鸣（3 个变体随机）。移植自本体 {@code bombs_incoming}
     * （本体在 {@code AerialBombEntity#tickSound} 中于玩家 8 格内<b>且玩家不在载具上</b>时播放）。
     * 该条件使其只作用于<b>地面上将被命中的徒步目标</b>——投弹者自己在座舱里听不到，
     * 因此 RVP 侧保留同一限制，避免飞行员被自己的“来袭”音误惊。
     */
    public static final RegistryObject<SoundEvent> BOMBS_INCOMING = register("bombs_incoming");

    private RVP_Sounds() {}

    public static void register(IEventBus eventBus) {
        SOUNDS.register(eventBus);
    }

    private static RegistryObject<SoundEvent> register(String name) {
        return SOUNDS.register(name,
                () -> SoundEvent.createVariableRangeEvent(RVP_MOD.modLocation(name)));
    }
}
