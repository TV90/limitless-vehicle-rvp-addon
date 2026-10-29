package org.ywzj.rvp.client.visual;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.RVP_ClientVehicleExplosionSound;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.visual.RVP_DefaultExplosionEventData;
import org.ywzj.rvp.weapon.visual.api.RVP_VisualEffectEvent;

/** 单次 MCHR 默认爆炸的客户端武器分流、近远音选择与声速延迟控制器。 */
final class RVP_DefaultExplosionSoundController {
    /** 343 格/秒换算得到的每 tick 声速。 */
    static final double SPEED_OF_SOUND_BLOCKS_PER_TICK = 17.15D;
    /** 该距离内立即播放，避免近战爆炸反馈迟钝。 */
    static final double IMMEDIATE_SOUND_DISTANCE = 24.0D;
    /** 至少该距离内播放近音 */
    static final double CLOSE_SOUND_DISTANCE = 200.0D;
    /** 爆炸事件所属客户端世界。 */
    private final ClientLevel level;
    /** 服务端权威爆心，用于保留声音空间方向。 */
    private final Vec3 center;
    /** 按武器类型解析后的声音档案。 */
    private final SoundProfile profile;
    /** 玩家收到事件时到爆心的距离，单位格。 */
    private final double listenerDistance;
    /** 主爆音相对事件起点的抵达 tick。 */
    private final int arrivalAge;
    /** 当前客户端按听者距离选择的声音档位。 */
    private final SoundDistanceBand soundBand;
    /** 事件是否允许播放声音。 */
    private final boolean soundEnabled;
    /** 事件确定性随机种子，用于声音变体与轻微音高抖动。 */
    private final long seed;
    /** 事件参数携带的自定义爆炸音效 ID；非空时替换武器类别档案的近/远音（2026-09-22）。 */
    private final String customSoundId;
    /** 主爆音是否已播放或已确认无需播放。 */
    private boolean handled;
    /** 所属效果实例是否已经关闭。 */
    private boolean closed;

    RVP_DefaultExplosionSoundController(ClientLevel level, RVP_VisualEffectEvent event,
            RVP_EnumWeaponKind weaponKind, int initialAge) {
        this.level = level;
        center = event.position();
        boolean cannonSound = RVP_DefaultExplosionEventData.decodeCannonSound(event.canonicalPresetDataJson());
        boolean vehicleSound = RVP_DefaultExplosionEventData.decodeVehicleSoundProfile(
                event.canonicalPresetDataJson());
        profile = vehicleSound ? SoundProfile.VEHICLE
                : resolveProfile(weaponKind, event.baseExplosionRadius(), cannonSound);
        // 解析事件参数里的自定义爆炸音效（explosion_data.explosion_sound 经 preset_data 通道下发）。
        customSoundId = RVP_DefaultExplosionEventData.decodeExplosionSound(event.canonicalPresetDataJson());
        net.minecraft.client.player.LocalPlayer player = Minecraft.getInstance().player;
        listenerDistance = player == null ? 0.0D : player.position().distanceTo(center);
        arrivalAge = resolveArrivalTick(listenerDistance);
        soundBand = resolveSoundBand(profile, listenerDistance, event.baseExplosionRadius());
        soundEnabled = event.sound();
        seed = event.seed();
        if (soundEnabled && profile == SoundProfile.VEHICLE
                && soundBand != SoundDistanceBand.OUT_OF_RANGE) {
            // 登记一次性本体爆音抑制键：客户端先收到 RVP 事件时，随后到达的本体 GENERIC_EXPLODE 才会被吞掉。
            RVP_ClientVehicleExplosionSound.registerPending(level, center);
        }
        if (!soundEnabled || soundBand == SoundDistanceBand.OUT_OF_RANGE) {
            handled = true;
            return;
        }
        // 调用本控制器的补帧入口，让迟到的网络事件立即处理已经抵达的声波。
        tick(initialAge);
    }

    /** 推进声波抵达判定；每次事件最多播放一个主爆音。 */
    void tick(int age) {
        if (closed || handled || age < arrivalAge) {
            return;
        }
        handled = true;
        if (!soundEnabled) {
            return;
        }
        // 自定义爆炸音效优先（explosion_data.explosion_sound）；传播距离与音高抖动仍按武器类别档案。
        ResourceLocation sound = null;
        if (customSoundId != null) {
            sound = ResourceLocation.tryParse(customSoundId);
        }
        if (sound == null) {
            sound = switch (soundBand) {
                case NEAR -> profile.nearSound();
                case FAR -> profile.farSound();
                case VERY_FAR -> profile.veryFarSound();
                case OUT_OF_RANGE -> null;
            };
        }
        if (sound == null) {
            return;
        }
        float jitter = (RandomSource.create(seed).nextFloat() - 0.5F) * 0.06F;
        float pitch = Mth.clamp(profile.basePitch() + jitter, 0.5F, 1.5F);
        for (int layer = 0; layer < profile.loudnessLayers(); layer++) {
            // 调用客户端声音管理器，同步叠加同一爆音以突破单声源增益上限，并保留爆心方位与线性衰减。
            Minecraft.getInstance().getSoundManager().play(new SpatialExplosionSound(
                    SoundEvent.createVariableRangeEvent(sound), profile.maximumRange(), pitch, seed, center));
        }
    }

    /** 返回声波是否已经播放或被安全跳过。 */
    boolean isFinished() {
        return handled || closed || level != Minecraft.getInstance().level;
    }

    /** 关闭控制器，取消尚未抵达的声音。 */
    void close() {
        closed = true;
    }

    /** 24 格内立即播放，外部按真实声速计算抵达 tick。 */
    static int resolveArrivalTick(double distance) {
        double safeDistance = Double.isFinite(distance) ? Math.max(0.0D, distance) : 0.0D;
        if (safeDistance <= IMMEDIATE_SOUND_DISTANCE) {
            return 0;
        }
        double ticks = Math.ceil(safeDistance / SPEED_OF_SOUND_BLOCKS_PER_TICK);
        return ticks >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) ticks;
    }

    /** 近音覆盖至少 CLOSE_SOUND_DISTANCE 格，大爆炸按20倍爆炸半径扩展近音范围。 */
    static double resolveNearSoundDistance(float explosionRadius) {
        float safeRadius = Float.isFinite(explosionRadius) ? Math.max(0.0F, explosionRadius) : 0.0F;
        return Math.max(CLOSE_SOUND_DISTANCE, safeRadius * 20.0D);
    }

    /**
     * 按听者距离选择声音档位；炮弹档位固定使用 200/512/1536 格三段，其他档位保留旧逻辑。
     */
    static SoundDistanceBand resolveSoundBand(SoundProfile profile, double listenerDistance,
                                               float explosionRadius) {
        double safeDistance = Double.isFinite(listenerDistance) ? Math.max(0.0D, listenerDistance) : 0.0D;
        if (safeDistance > profile.maximumRange()) {
            return SoundDistanceBand.OUT_OF_RANGE;
        }
        if (profile == SoundProfile.CANNON) {
            if (safeDistance <= CLOSE_SOUND_DISTANCE) {
                return SoundDistanceBand.NEAR;
            }
            if (safeDistance <= 512.0D) {
                return SoundDistanceBand.FAR;
            }
            return SoundDistanceBand.VERY_FAR;
        }
        if (profile == SoundProfile.VEHICLE) {
            return safeDistance <= CLOSE_SOUND_DISTANCE
                    ? SoundDistanceBand.NEAR : SoundDistanceBand.FAR;
        }
        return safeDistance <= resolveNearSoundDistance(explosionRadius)
                ? SoundDistanceBand.NEAR : SoundDistanceBand.FAR;
    }

    /** 按 RVP 类型化武器分类选择声音档案；炮音效只由服务端传入的标记启用。 */
    static SoundProfile resolveProfile(RVP_EnumWeaponKind weaponKind, float explosionRadius) {
        return resolveProfile(weaponKind, explosionRadius, false);
    }

    /** 按武器类型和服务端炮音效标记选择声音档案，禁止通过武器 ID 字符串分流。 */
    static SoundProfile resolveProfile(RVP_EnumWeaponKind weaponKind, float explosionRadius,
                                       boolean cannonSound) {
        if (weaponKind == RVP_EnumWeaponKind.MACHINEGUN && cannonSound) {
            return SoundProfile.CANNON;
        }
        if (weaponKind == RVP_EnumWeaponKind.MACHINEGUN) {
            return SoundProfile.MACHINEGUN;
        }
        if (weaponKind == RVP_EnumWeaponKind.MISSILE) {
            return SoundProfile.MISSILE;
        }
        if (weaponKind == RVP_EnumWeaponKind.BOMB) {
            return SoundProfile.BOMB;
        }
        if (weaponKind == RVP_EnumWeaponKind.ROCKET) {
            return SoundProfile.ROCKET;
        }
        return explosionRadius >= 8.0F ? SoundProfile.MISSILE : SoundProfile.ROCKET;
    }

    /** 默认爆炸音效的距离档位；OUT_OF_RANGE 表示不应播放声音。 */
    enum SoundDistanceBand {
        NEAR,
        FAR,
        VERY_FAR,
        OUT_OF_RANGE
    }

    /** 各武器类别的独立资源入口、最大传播距离、基础音高与响度叠加层数。 */
    enum SoundProfile {
        /** 机枪/机炮弹：短促高音，小范围传播。 */
        MACHINEGUN("mchr_explosion_machinegun_near", "mchr_explosion_machinegun_far", 1.00F, 512.0D, 1),
        /** 大口径机炮/炮弹：使用 cannon_exp 的近、中、远三段声音。 */
        CANNON("mchr_explosion_cannon_near", "mchr_explosion_cannon_far",
                "mchr_explosion_cannon_very_far", 1.00F, 1536.0D, 1),
        /** 火箭弹：中性高爆音，中等范围传播 */
        ROCKET("mchr_explosion_rocket_near", "mchr_explosion_rocket_far", 0.98F, 1536.0D, 1),
        /** 导弹：厚重低音，远距离传播 */
        MISSILE("mchr_explosion_missile_near", "mchr_explosion_missile_far", 0.90F, 1536.0D, 1),
        /** 航弹：最低沉且传播最远。 */
        BOMB("mchr_explosion_bomb_near", "mchr_explosion_bomb_far", 0.80F, 1536.0D, 1),
        /** 完整载具击毁：200 格内近音，超过 200 格使用远音，最多传播 1536 格。 */
        VEHICLE("vehicle_explosion_near", "vehicle_explosion_far", 1.00F, 1536.0D, 1);

        /** 近距离声音事件资源 ID。 */
        private final ResourceLocation nearSound;
        /** 远距离声音事件资源 ID。 */
        private final ResourceLocation farSound;
        /** 炮弹专用的超远距离声音事件资源 ID；普通武器档案为空。 */
        @Nullable
        private final ResourceLocation veryFarSound;
        /** 该武器类别的基础音高。 */
        private final float basePitch;
        /** 该武器类别声音的最大传播距离，单位格。 */
        private final double maximumRange;
        /** 同步播放的相同爆音层数；每翻倍约增加 6 dB，建议保持在 1 至 3。 */
        private final int loudnessLayers;

        SoundProfile(String nearSound, String farSound, float basePitch, double maximumRange, int loudnessLayers) {
            this(nearSound, farSound, null, basePitch, maximumRange, loudnessLayers);
        }

        SoundProfile(String nearSound, String farSound, @Nullable String veryFarSound,
                     float basePitch, double maximumRange, int loudnessLayers) {
            // 调用 RVP 资源定位入口，为每类武器建立可由资源包独立替换的近远声音事件 ID。
            this.nearSound = RVP_MOD.modLocation(nearSound);
            this.farSound = RVP_MOD.modLocation(farSound);
            this.veryFarSound = veryFarSound == null ? null : RVP_MOD.modLocation(veryFarSound);
            this.basePitch = basePitch;
            this.maximumRange = maximumRange;
            this.loudnessLayers = Mth.clamp(loudnessLayers, 1, 3);
        }

        ResourceLocation nearSound() {
            return nearSound;
        }

        ResourceLocation farSound() {
            return farSound;
        }

        @Nullable
        ResourceLocation veryFarSound() {
            return veryFarSound;
        }

        float basePitch() {
            return basePitch;
        }

        double maximumRange() {
            return maximumRange;
        }

        int loudnessLayers() {
            return loudnessLayers;
        }
    }

    /** 使用高音量扩展线性衰减距离、但最终增益仍由声音引擎钳制的空间声音实例。 */
    private static final class SpatialExplosionSound extends AbstractSoundInstance {
        SpatialExplosionSound(SoundEvent soundEvent, double maximumRange, float pitch, long seed, Vec3 position) {
            super(soundEvent, SoundSource.HOSTILE, RandomSource.create(seed));
            // Minecraft 以 max(volume, 1)×16 计算线性衰减距离；较大值只扩展传播范围，最终增益会被钳制。
            volume = (float) Math.max(1.0D, maximumRange / 16.0D);
            this.pitch = pitch;
            x = position.x;
            y = position.y;
            z = position.z;
            looping = false;
            delay = 0;
            attenuation = SoundInstance.Attenuation.LINEAR;
            relative = false;
        }
    }
}
