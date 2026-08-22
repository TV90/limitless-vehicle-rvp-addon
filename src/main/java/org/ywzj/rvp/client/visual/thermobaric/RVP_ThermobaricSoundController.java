package org.ywzj.rvp.client.visual.thermobaric;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.config.RVP_ClientConfig;
import org.ywzj.rvp.weapon.visual.api.RVP_VisualEffectEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** 单次温压事件的声速延迟、近远音选择、尾音与客户端事件去重控制器。 */
final class RVP_ThermobaricSoundController {
    /** 343 格/秒换算得到的每 tick 声速。 */
    static final double SPEED_OF_SOUND_BLOCKS_PER_TICK = 17.15D;
    /** 该距离内为避免近战反馈迟钝而立即播放主音。 */
    static final double IMMEDIATE_SOUND_DISTANCE = 24.0D;
    /** 已接收事件键的保留时长，防止重发包重复播放。 */
    static final long DEDUPLICATION_TTL_TICKS = 200L;
    /** 事件位置量化精度，每格分为八份。 */
    private static final double POSITION_QUANTIZATION = 8.0D;
    /** 最近接收的声音事件及其过期世界时间。 */
    private static final Map<EventKey, Long> RECENT_EVENTS = new HashMap<>();

    /** 当前事件所属客户端世界。 */
    private final ClientLevel level;
    /** 用于声音空间定位的服务端权威爆心。 */
    private final Vec3 center;
    /** 温压预设中的近距离声音资源 ID。 */
    private final ResourceLocation nearSound;
    /** 温压预设中的远距离声音资源 ID。 */
    private final ResourceLocation farSound;
    /** 温压预设中的尾音资源 ID。 */
    private final ResourceLocation tailSound;
    /** 当前事件的稳定去重键。 */
    private final EventKey eventKey;
    /** 当前事件的确定性随机种子。 */
    private final long seed;
    /** 玩家在接收事件时到爆心的距离。 */
    private final double listenerDistance;
    /** 基础爆炸半径与视觉倍率合并后的尺寸。 */
    private final float visualRadius;
    /** 主爆音相对事件起点的到达年龄。 */
    private final int mainArrivalAge;
    /** 当前距离是否选择近距离声音。 */
    private final boolean useNearSound;
    /** 服务端事件是否允许声音。 */
    private final boolean soundEnabled;
    /** 服务端事件是否允许镜头震动。 */
    private final boolean shakeEnabled;
    /** 当前控制器是否成功取得事件去重所有权。 */
    private final boolean accepted;
    /** 主音是否已经处理，关闭声音时也会置位以避免重试。 */
    private boolean mainHandled;
    /** 尾音是否已经处理。 */
    private boolean tailHandled;
    /** 主音实际处理后计算出的尾音年龄。 */
    private int tailArrivalAge = Integer.MAX_VALUE;
    /** 控制器是否已经关闭。 */
    private boolean closed;

    RVP_ThermobaricSoundController(ClientLevel level, RVP_VisualEffectEvent event,
            RVP_ThermobaricPreset preset, float visualRadius, double listenerDistance,
            int initialAge) {
        this.level = level;
        center = event.position();
        nearSound = preset.nearSound();
        farSound = preset.farSound();
        tailSound = preset.tailSound();
        eventKey = createEventKey(event);
        seed = event.seed();
        this.listenerDistance = finiteNonNegative(listenerDistance);
        this.visualRadius = Math.max(0.0F, visualRadius);
        mainArrivalAge = resolveMainArrivalTick(this.listenerDistance);
        useNearSound = this.listenerDistance <= resolveNearSoundDistance(this.visualRadius);
        soundEnabled = event.sound();
        shakeEnabled = event.shake();
        accepted = claimEvent(eventKey, level.getGameTime());
        // 调用本控制器的补帧入口，使迟到事件立即处理已经到达的声波，而不是从第 0 tick 重放。
        tick(initialAge);
    }

    /** 返回该实例是否取得事件所有权，供闪光与声音共用同一去重结果。 */
    boolean accepted() {
        return accepted;
    }

    /** 返回当前事件键，供独立屏幕反馈服务建立与清理对应脉冲。 */
    EventKey eventKey() {
        return eventKey;
    }

    /** 推进当前实例的主音、尾音和声波到达反馈。 */
    void tick(int age) {
        if (closed || !accepted) {
            return;
        }
        purgeExpired(level.getGameTime());
        if (!mainHandled && age >= mainArrivalAge) {
            mainHandled = true;
            if (soundEnabled) {
                // 调用动态声音播放入口，让资源包作者配置的任意合法声音 ID 都可直接使用。
                // visualRadius内播放nearSound，外播放farSound
                play(useNearSound ? nearSound : farSound, seed);
                tailArrivalAge = saturatedAdd(age, resolveTailDelayTicks(seed));
            } else {
                tailHandled = true;
            }
            if (shakeEnabled) {
                // 调用独立屏幕反馈服务，在声波实际抵达客户端时启动镜头震动。
                RVP_ThermobaricScreenFeedback.triggerShake(
                        eventKey, level, seed, listenerDistance, visualRadius);
            }
        }
        if (!tailHandled && age >= tailArrivalAge) {
            tailHandled = true;
            play(tailSound, seed ^ 0x6A09E667F3BCC909L);
        }
    }

    /** 关闭当前控制器并撤销尚未结束的对应屏幕反馈。 */
    void close() {
        closed = true;
        if (accepted) {
            // 调用温压屏幕反馈清理接口，避免被全局预算淘汰的实例留下震动或闪光。
            RVP_ThermobaricScreenFeedback.cancel(eventKey);
        }
    }

    /** 清空跨实例声音去重状态；切世界或断开连接时调用。 */
    static void clear() {
        RECENT_EVENTS.clear();
    }

    /** 计算主音到达 tick；24 格以内固定为零。 */
    static int resolveMainArrivalTick(double distance) {
        double normalizedDistance = finiteNonNegative(distance);
        if (normalizedDistance <= IMMEDIATE_SOUND_DISTANCE) {
            return 0;
        }
        double ticks = Math.ceil(normalizedDistance / SPEED_OF_SOUND_BLOCKS_PER_TICK);
        return ticks >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) ticks;
    }

    /** 计算近距离声音阈值。 */
    static double resolveNearSoundDistance(float visualRadius) {
        return Math.max(IMMEDIATE_SOUND_DISTANCE,
                Math.max(0.0D, finiteNonNegative(visualRadius)) * 4.0D);
    }

    /** 从事件 seed 确定性派生 3 至 6 tick 的尾音延迟。 */
    static int resolveTailDelayTicks(long seed) {
        return 3 + Math.floorMod((int) (seed ^ (seed >>> 32)), 4);
    }

    /** 按当前协议创建维度、量化爆心和 seed 组成的稳定事件键。 */
    static EventKey createEventKey(RVP_VisualEffectEvent event) {
        return createEventKey(event.dimension().location(), event.position(), event.seed());
    }

    /** 从已解析的维度、爆心与 seed 创建稳定事件键。 */
    static EventKey createEventKey(ResourceLocation dimension, Vec3 position, long seed) {
        return new EventKey(
                dimension,
                quantize(position.x),
                quantize(position.y),
                quantize(position.z),
                seed);
    }

    /** 尝试取得事件去重所有权。 */
    static boolean claimEvent(EventKey key, long gameTime) {
        purgeExpired(gameTime);
        if (RECENT_EVENTS.containsKey(key)) {
            return false;
        }
        RECENT_EVENTS.put(key, saturatedAdd(gameTime, DEDUPLICATION_TTL_TICKS));
        return true;
    }

    /** 删除已经过期的事件键。 */
    private static void purgeExpired(long gameTime) {
        Iterator<Map.Entry<EventKey, Long>> iterator = RECENT_EVENTS.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue() <= gameTime) {
                iterator.remove();
            }
        }
    }

    /** 播放不再二次按距离衰减、但仍保留爆心空间方向的客户端声音。 */
    private void play(ResourceLocation sound, long soundSeed) {
        // 调用客户端配置读取实时音量，使用户无需重建活动实例即可静音或调低温压声音。
        float volume = RVP_ClientConfig.getThermobaricSoundVolume();
        if (volume <= 0.0F) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        SoundEvent dynamicSound = SoundEvent.createVariableRangeEvent(sound);
        minecraft.getSoundManager().play(new DelayedThermobaricSound(
                dynamicSound, volume, soundSeed, center));
    }

    /** 把坐标量化为八分之一格。 */
    private static long quantize(double coordinate) {
        if (!Double.isFinite(coordinate)) {
            return 0L;
        }
        double quantized = Math.rint(coordinate * POSITION_QUANTIZATION);
        if (quantized >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        if (quantized <= Long.MIN_VALUE) {
            return Long.MIN_VALUE;
        }
        return (long) quantized;
    }

    /** 把非有限值和负值规范为零。 */
    private static double finiteNonNegative(double value) {
        return Double.isFinite(value) ? Math.max(0.0D, value) : 0.0D;
    }

    /** 对整数 tick 做饱和加法。 */
    private static int saturatedAdd(int left, int right) {
        long sum = (long) left + right;
        return sum >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }

    /** 对世界时间做饱和加法。 */
    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    /** 温压声音与反馈共享的稳定事件键。 */
    record EventKey(ResourceLocation dimension, long x, long y, long z, long seed) {
    }

    /** 已自行完成距离时序计算、因此不再二次距离衰减的空间声音实例。 */
    private static final class DelayedThermobaricSound extends AbstractSoundInstance {
        DelayedThermobaricSound(SoundEvent soundEvent, float volume, long seed, Vec3 position) {
            super(soundEvent, SoundSource.BLOCKS, RandomSource.create(seed));
            this.volume = volume;
            pitch = 1.0F;
            x = position.x;
            y = position.y;
            z = position.z;
            looping = false;
            delay = 0;
            attenuation = SoundInstance.Attenuation.NONE;
            relative = false;
        }
    }
}
