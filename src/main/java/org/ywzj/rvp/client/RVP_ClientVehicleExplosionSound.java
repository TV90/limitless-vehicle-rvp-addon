package org.ywzj.rvp.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.PlayLevelSoundEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** 客户端只抑制完整载具击毁对应的本体通用爆音，避免和 RVP 载具爆炸声叠加。 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ClientVehicleExplosionSound {

    /** 本体 VehicleExplosion.effect 使用的原版通用爆炸声音 ID。 */
    static final ResourceLocation NATIVE_GENERIC_EXPLOSION =
            ResourceLocation.fromNamespaceAndPath("minecraft", "entity.generic.explode");
    /** 本体声音包到达前允许抑制键存活的最长时间，单位毫秒。 */
    private static final long PENDING_EXPIRE_MILLIS = 2000L;
    /** 异常网络顺序下的待抑制队列上限。 */
    private static final int PENDING_MAX = 64;
    /** 声音坐标匹配容差的平方，约等于 0.01 格欧氏距离。 */
    private static final double POSITION_TOLERANCE_SQUARED = 1.0E-4D;
    /** 待抑制的本体声音；每条记录只允许消费一次。 */
    private static final List<PendingSuppress> PENDING = new ArrayList<>();

    private RVP_ClientVehicleExplosionSound() {
    }

    /**
     * 登记一条载具击毁爆音抑制键。
     *
     * @param level 事件所属客户端世界，用于防止切世界后误吞新世界的同坐标声音
     * @param position 载具击毁爆心
     */
    public static void registerPending(ClientLevel level, Vec3 position) {
        if (level == null || position == null) {
            return;
        }
        registerPending(level, position, System.currentTimeMillis());
    }

    /** 写入待抑制队列；时间参数独立出来，便于单元测试验证过期和一次性消费。 */
    static void registerPending(ClientLevel level, Vec3 position, long nowMillis) {
        if (position == null) {
            return;
        }
        removeExpired(nowMillis);
        if (PENDING.size() >= PENDING_MAX) {
            PENDING.remove(0);
        }
        PENDING.add(new PendingSuppress(level, position.x, position.y, position.z,
                nowMillis + PENDING_EXPIRE_MILLIS));
    }

    /**
     * Forge 本体位置声音事件：只取消已登记位置的一次 HOSTILE 通用爆音。
     * {@code playLocalSound} 不经过此事件，因此残骸余火仍由现有最小 Mixin 处理。
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlaySoundAtPosition(PlayLevelSoundEvent.AtPosition event) {
        if (!(event.getLevel() instanceof ClientLevel level) || event.getSound() == null) {
            return;
        }
        ResourceLocation soundLocation = event.getSound().value().getLocation();
        if (consumePending(level, soundLocation, event.getSource(), event.getPosition(),
                System.currentTimeMillis())) {
            event.setCanceled(true);
        }
    }

    /** 消费一次待抑制键；声音 ID、音源、世界和位置全部匹配才会返回 true。 */
    static boolean consumePending(ClientLevel level, ResourceLocation soundLocation,
                                  SoundSource source, Vec3 position, long nowMillis) {
        if (!isNativeVehicleExplosion(soundLocation, source) || position == null) {
            return false;
        }
        removeExpired(nowMillis);
        Iterator<PendingSuppress> iterator = PENDING.iterator();
        while (iterator.hasNext()) {
            PendingSuppress pending = iterator.next();
            if (pending.level() == level && pending.matches(position)) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    /** 判断声音是否恰好是本体载具爆炸使用的 HOSTILE 通用爆音。 */
    static boolean isNativeVehicleExplosion(ResourceLocation soundLocation, SoundSource source) {
        return SoundSource.HOSTILE == source && NATIVE_GENERIC_EXPLOSION.equals(soundLocation);
    }

    /** 清理超时键，避免异常情况下静态列表无限增长。 */
    private static void removeExpired(long nowMillis) {
        PENDING.removeIf(pending -> pending.expireAtMillis() <= nowMillis);
    }

    /** 客户端切换世界时清空旧坐标，避免跨世界误抑制。 */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            PENDING.clear();
        }
    }

    /** 供同包测试和世界清理使用的待抑制键清空入口。 */
    static void clearPending() {
        PENDING.clear();
    }

    /**
     * 一次性待抑制键：位置只允许在短时间内消费一次。
     *
     * @param level 事件所属客户端世界，用于隔离换世界后的同坐标声音
     * @param x 爆心 X 坐标，单位格
     * @param y 爆心 Y 坐标，单位格
     * @param z 爆心 Z 坐标，单位格
     * @param expireAtMillis 抑制键失效的客户端毫秒时间
     */
    record PendingSuppress(ClientLevel level, double x, double y, double z, long expireAtMillis) {
        boolean matches(Vec3 position) {
            double dx = position.x - x;
            double dy = position.y - y;
            double dz = position.z - z;
            return dx * dx + dy * dy + dz * dz < POSITION_TOLERANCE_SQUARED;
        }
    }
}
