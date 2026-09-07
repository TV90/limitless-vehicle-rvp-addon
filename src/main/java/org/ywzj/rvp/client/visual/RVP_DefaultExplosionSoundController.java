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
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
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
    /** 当前客户端是否应选择近距离素材。 */
    private final boolean useNearSound;
    /** 事件是否允许播放声音。 */
    private final boolean soundEnabled;
    /** 事件确定性随机种子，用于声音变体与轻微音高抖动。 */
    private final long seed;
    /** 主爆音是否已播放或已确认无需播放。 */
    private boolean handled;
    /** 所属效果实例是否已经关闭。 */
    private boolean closed;

    RVP_DefaultExplosionSoundController(ClientLevel level, RVP_VisualEffectEvent event,
            RVP_EnumWeaponKind weaponKind, int initialAge) {
        this.level = level;
        center = event.position();
        profile = resolveProfile(weaponKind, event.baseExplosionRadius());
        net.minecraft.client.player.LocalPlayer player = Minecraft.getInstance().player;
        listenerDistance = player == null ? 0.0D : player.position().distanceTo(center);
        arrivalAge = resolveArrivalTick(listenerDistance);
        useNearSound = listenerDistance <= resolveNearSoundDistance(event.baseExplosionRadius());
        soundEnabled = event.sound();
        seed = event.seed();
        if (!soundEnabled || listenerDistance > profile.maximumRange()) {
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
        ResourceLocation sound = useNearSound ? profile.nearSound() : profile.farSound();
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

    /** 按 RVP 类型化武器分类选择声音档案；非爆炸型类别按半径安全回退。 */
    static SoundProfile resolveProfile(RVP_EnumWeaponKind weaponKind, float explosionRadius) {
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

    /** 各武器类别的独立资源入口、最大传播距离、基础音高与响度叠加层数。 */
    enum SoundProfile {
        /** 机枪/机炮弹：短促高音，小范围传播。 */
        MACHINEGUN("mchr_explosion_machinegun_near", "mchr_explosion_machinegun_far", 1.00F, 512.0D, 1),
        /** 火箭弹：中性高爆音，中等范围传播 */
        ROCKET("mchr_explosion_rocket_near", "mchr_explosion_rocket_far", 0.98F, 1536.0D, 1),
        /** 导弹：厚重低音，远距离传播 */
        MISSILE("mchr_explosion_missile_near", "mchr_explosion_missile_far", 0.90F, 1536.0D, 1),
        /** 航弹：最低沉且传播最远。 */
        BOMB("mchr_explosion_bomb_near", "mchr_explosion_bomb_far", 0.80F, 1536.0D, 1);

        /** 近距离声音事件资源 ID。 */
        private final ResourceLocation nearSound;
        /** 远距离声音事件资源 ID。 */
        private final ResourceLocation farSound;
        /** 该武器类别的基础音高。 */
        private final float basePitch;
        /** 该武器类别声音的最大传播距离，单位格。 */
        private final double maximumRange;
        /** 同步播放的相同爆音层数；每翻倍约增加 6 dB，建议保持在 1 至 3。 */
        private final int loudnessLayers;

        SoundProfile(String nearSound, String farSound, float basePitch, double maximumRange, int loudnessLayers) {
            // 调用 RVP 资源定位入口，为每类武器建立可由资源包独立替换的近远声音事件 ID。
            this.nearSound = RVP_MOD.modLocation(nearSound);
            this.farSound = RVP_MOD.modLocation(farSound);
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
