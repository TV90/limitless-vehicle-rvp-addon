package org.ywzj.rvp.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.vehicle.api.event.VehicleFireEvent;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * [RVP] 载具开火音的距离衰减（2026-10-30 替代 {@code AbstractVehicleWeaponSoundMixin}）。
 *
 * <p>背景：载具实体 {@code clientTrackingRange = 32 chunks}（约 512 格），{@code ServerVehicleFire}
 * 包会分发给 512 格内所有玩家；而本体 {@code AbstractVehicleWeapon.onClientFire()} 用
 * {@code level.playSound(..., 4f, 1f)} 播放，OpenAL 线性衰减的最大可听距离只有 {@code 16 × volume = 64} 格，
 * 于是"远处玩家也收到包、但听不到声音"——本类把 64~500 格按二次方压低音量，500 格外直接不播。</p>
 *
 * <p>为什么不用 mixin：本体 {@code onClientFire} 由公共事件 {@link VehicleFireEvent.Post}（客户端侧）驱动，
 * 且真正落到声卡前会经过 Forge 的 {@link PlaySoundEvent}。因此在开火事件里登记"即将播放的那个音效"
 * （音效 id + 播放坐标），在 {@link PlaySoundEvent} 里按 id + 坐标匹配并改写音量，语义与原来的
 * {@code @Redirect} 一一对应，零 mixin。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class RVP_VehicleFireSoundAttenuator {

    /** 与旧 mixin 一致的保持原样半径（格）。 */
    private static final double NEAR_DIST = 64.0;
    /** 超过该距离直接不播放（格）。 */
    private static final double MAX_DIST = 500.0;
    /** 登记项有效期（毫秒）：开火事件到 SoundEngine 播放之间只有几毫秒，200ms 足够且不会误配后续同音效。 */
    private static final long PENDING_TTL_MS = 200L;
    /** 坐标匹配容差（格）：同一位置可能有多发弹药，容差内视为同一次开火。 */
    private static final double POS_EPSILON = 0.05;

    private static final List<Pending> PENDING = new ArrayList<>();

    private RVP_VehicleFireSoundAttenuator() {}

    private record Pending(ResourceLocation soundId, double x, double y, double z, long expireAt) {}

    /** 本体开火（客户端侧）→ 登记即将播放的开火音。 */
    @SubscribeEvent
    public static void onVehicleFire(VehicleFireEvent.Post event) {
        if (!event.isClientSide()) {
            return;
        }
        AbstractVehicleWeapon<?> weapon = event.getWeapon();
        if (weapon == null) {
            return;
        }
        SoundEvent fireSound = weapon.getFireSound();
        if (fireSound == null) {
            return;
        }
        Vec3 pos = weapon.getWeaponUnit().worldPivotPosition();
        long now = System.currentTimeMillis();
        synchronized (PENDING) {
            PENDING.removeIf(p -> p.expireAt() < now);
            PENDING.add(new Pending(fireSound.getLocation(), pos.x, pos.y, pos.z, now + PENDING_TTL_MS));
        }
    }

    /** 声音即将送入 SoundEngine → 若是刚登记的开火音，按玩家距离改写音量。 */
    @SubscribeEvent
    public static void onPlaySound(PlaySoundEvent event) {
        SoundInstance sound = event.getSound();
        if (sound == null) {
            return;
        }
        ResourceLocation id = sound.getLocation();
        if (!consume(id, sound.getX(), sound.getY(), sound.getZ())) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        double distSq = mc.player.distanceToSqr(sound.getX(), sound.getY(), sound.getZ());
        if (distSq <= NEAR_DIST * NEAR_DIST) {
            return; // 64 格内保持原样
        }
        if (distSq > MAX_DIST * MAX_DIST) {
            event.setSound(null); // 500 格外不播放
            return;
        }
        double dist = Math.sqrt(distSq);
        float t = (float) ((dist - NEAR_DIST) / (MAX_DIST - NEAR_DIST));
        float volume = sound.getVolume() * (1.0f - t) * (1.0f - t);
        if (volume < 0.01f) {
            event.setSound(null);
            return;
        }
        // 用注册表把音效 id 还原成 SoundEvent，再构造定位音；拿不到（如自定义音被移除）则保持原样，不冒险。
        SoundEvent soundEvent = ForgeRegistries.SOUND_EVENTS.getValue(id);
        if (soundEvent == null) {
            return;
        }
        event.setSound(new SimpleSoundInstance(soundEvent, sound.getSource(), volume, sound.getPitch(),
                SoundInstance.createUnseededRandom(), sound.getX(), sound.getY(), sound.getZ()));
    }

    /** 命中登记项则消费掉（一次开火只衰减一次，避免同音效的其它载具被连带处理）。 */
    private static boolean consume(ResourceLocation id, double x, double y, double z) {
        long now = System.currentTimeMillis();
        synchronized (PENDING) {
            Iterator<Pending> it = PENDING.iterator();
            while (it.hasNext()) {
                Pending p = it.next();
                if (p.expireAt() < now) {
                    it.remove();
                    continue;
                }
                if (p.soundId().equals(id)
                        && Math.abs(p.x() - x) <= POS_EPSILON
                        && Math.abs(p.y() - y) <= POS_EPSILON
                        && Math.abs(p.z() - z) <= POS_EPSILON) {
                    it.remove();
                    return true;
                }
            }
        }
        return false;
    }
}
