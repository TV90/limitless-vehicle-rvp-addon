package org.ywzj.rvp.client;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.PlayLevelSoundEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager;
import org.ywzj.vehicle.api.event.VehicleFireEvent;
import org.ywzj.vehicle.audio.VehicleSound;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleMultiWeapons;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;

/**
 * [RVP] 开火声"方向机同款"相机相对播放（2026-10-02，用户需求）：
 * 本体 {@code AbstractVehicleWeapon.onClientFire()} 用原版 {@code Level.playSound} 把开火声
 * 定位播放<b>在真实炮口世界坐标</b>（volume=4，OpenAL 线性衰减上限 = max(volume,1)×16 = 64 格）——
 * 第三人称把相机拉远超过 64 格后声音彻底消失。而方向机转动声走本体自研
 * {@link VehicleSound}（TickableSoundInstance，每 tick {@code updateRelativePos} 把声源位置
 * 向相机方向重锚定）：骑乘者声音恒渲染在相机 8 格内（{@code calRelativePos} 骑乘分支，
 * 与 distance 参数无关），拉多远都能听到。
 *
 * <p>本处理器把<b>配置了 {@code audio_info.camera_relative_fire_sound_distance}（>0）的载具</b>
 * 的开火声改走 {@link VehicleSound} 同一条路径（音量/pitch 与本体原值等效——原版 clamp(4,0,1)=1，
 * 此处直接 1），并用 Forge 事件抑制本体那次原版定位播放，不产生双重声音：</p>
 *
 * <ol>
 *   <li>{@code VehicleFireEvent.Post}（客户端，HIGHEST 优先级——必须先于本体
 *       {@code AllEvents.onVehicleFire} 的 DEFAULT 优先级执行）：
 *       解析实际发声武器（多武器组合 {@link VehicleMultiWeapons} 委托给当前选中子武器），
 *       登记一条"待抑制"键（声音 id + 炮口坐标，2 秒过期兜底），随后直接
 *       {@link VehicleSound#play()} 重放开火声；</li>
 *   <li>{@code PlayLevelSoundEvent.AtPosition}（客户端，本体 {@code level.playSound} 的必经
 *       Forge 事件，取消即不出声）：命中待抑制键（声音 id 相同 + 坐标 < 0.01 格）即消费该键并
 *       {@code setCanceled(true)}——只吞本体这一次定位播放，其余无关声音零影响。</li>
 * </ol>
 *
 * <p>非骑乘玩家的可听半径由 distance 因子保持：{@code VehicleSound} 非骑乘分支把真实距离压缩
 * 1/distance，衰减变为 {@code 1 − 真实距离/(distance×16)}——distance=4 即与本体现行 64 格
 * 远域衰减曲线完全一致，音量平衡不变（这也是不直接提高 volume 的原因：volume 还会改
 * AL_GAIN 基准且与本体数值耦合）。</p>
 *
 * <p>双端安全：{@code @EventBusSubscriber(Dist.CLIENT)} 仅客户端注册；单机集成服务端也会
 * post {@code VehicleFireEvent.Post}（服务端 {@code WeaponUnit.shoot}）与 AtPosition 事件
 * （{@code ServerLevel.playSeededSound}），两处入口均以 {@code level.isClientSide} 过滤，
 * 静态表只在客户端主线程读写。</p>
 *
 * <p>零 Mixin、零本体改动：配置来自 {@link RVP_VehicleExtendedConfigManager}（数据层），
 * 拦截走 Forge 事件总线（纪律第 3 级），重放复用本体 {@link VehicleSound} 公开客户端类。</p>
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ClientCameraRelativeFireSound {

    /** 待抑制项过期时限（毫秒）：正常情况下本体同 tick 内就会触发 AtPosition 事件被消费，超时即丢弃。 */
    private static final long PENDING_EXPIRE_MS = 2000L;
    /** 待抑制队列上限：异常情况下（抑制键一直没被消费）防止无限增长，超出丢最旧。 */
    private static final int PENDING_MAX = 64;

    /** 待抑制的原版开火声：声音 id + 炮口坐标（与本体 onClientFire 的 playSound 参数同源同值）。 */
    private static final List<PendingSuppress> PENDING = new ArrayList<>();

    private RVP_ClientCameraRelativeFireSound() {}

    /**
     * [RVP] 客户端开火完成（本体 {@code ServerVehicleFire} S2C → 本地 Post 事件）：
     * 配置开启的载具在此重放开火声（方向机同款 VehicleSound）并登记抑制键。
     * HIGHEST 优先级保证先于本体 AllEvents（DEFAULT）的 onClientFire → level.playSound 执行。
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onVehicleFirePost(VehicleFireEvent.Post event) {
        // 单机集成服务端同样会 post 该事件（WeaponUnit.shoot 服务端分支），只处理客户端侧
        if (!event.isClientSide()) {
            return;
        }
        // 调用 RVP 武器声音管理器：已由默认音接管时，本处理器不能重复重放本体声音。
        if (RVP_ClientWeaponFireSound.willTakeOver(event)) {
            return;
        }
        // 本车（本地玩家乘坐的载具）且武器配置了 sight_fire 时，开火声整体由
        // RVP_ClientSightFireSound 接管：它同时起播 fire/sight_fire 两条音轨并按视角切换（与开火瞬间的
        // 视角无关），此处必须让出，否则会再叠一层重放
        if (RVP_ClientSightFireSound.resolveSightFireSound(event) != null) {
            return;
        }
        AbstractVehicle vehicle = event.getVehicle();
        float distanceFactor = RVP_VehicleExtendedConfigManager.getCameraRelativeFireSoundDistance(vehicle);
        if (distanceFactor <= 0f) {
            return;
        }
        // 多武器组合（VehicleMultiWeapons）的开火声实际由当前选中子武器发出
        // （本体 VehicleMultiWeapons.onClientFire 即委托 getSelectedWeapon().onClientFire()），
        // 声音事件与炮口坐标都必须取子武器的，抑制键才能与本体原版播放精确对上
        AbstractVehicleWeapon<?> soundWeapon = resolveSoundWeapon(event.getWeapon());
        if (soundWeapon == null) {
            return;
        }
        SoundEvent fireSound = soundWeapon.getFireSound();
        if (fireSound == null) {
            return;
        }
        WeaponUnit weaponUnit = soundWeapon.getWeaponUnit();
        if (weaponUnit == null) {
            return;
        }
        // 炮口世界坐标：与本体 onClientFire 的 level.playSound 坐标同一来源（worldPivotPosition）
        Vec3 muzzlePos = weaponUnit.worldPivotPosition();
        registerPending(fireSound.getLocation(), muzzlePos);
        // 方向机同款重放：offset = 炮口相对载具原点（VehicleSound 每 tick 经 relativeRotPos
        // 随载具旋转），volume=1/pitch=1 与本体原版 clamp(4,0,1)=1 等效（响度不变），
        // distance = 配置因子（骑乘恒 8 格、非骑乘 16×distance 格远域）
        VehicleSound sound = new VehicleSound(fireSound,
                muzzlePos.subtract(vehicle.position()),
                1f, distanceFactor, 1f,
                false, 0, false, false, vehicle.getId());
        sound.play();
    }

    /**
     * [RVP] 本体 {@code level.playSound} 的必经 Forge 事件（{@code ClientLevel.playSeededSound} 头部），
     * 取消即不出声：命中本处理器登记的待抑制键时取消，吞掉本体那次原版定位播放——
     * 声音已由 {@link #onVehicleFirePost} 用 VehicleSound 重放，不产生双重声音。
     */
    @SubscribeEvent
    public static void onPlaySoundAtPosition(PlayLevelSoundEvent.AtPosition event) {
        if (PENDING.isEmpty()) {
            return;
        }
        // 单机下服务端 ServerLevel.playSeededSound 也会触发本事件，只处理客户端侧
        if (!event.getLevel().isClientSide()) {
            return;
        }
        // 本体开火声固定 PLAYERS 分类（onClientFire），其余分类直接放行
        if (event.getSource() != SoundSource.PLAYERS) {
            return;
        }
        Vec3 pos = event.getPosition();
        ResourceLocation soundLocation = event.getSound().value().getLocation();
        // 调用一次性消费入口；延迟的本体冷发射声按实际播放时的发射站坐标匹配。
        if (consumePending(soundLocation, pos, System.currentTimeMillis())) {
            event.setCanceled(true);
        }
    }

    /** 多武器组合解析为实际发声的当前选中子武器；普通武器原样返回（与本体 onClientFire 委托一致）。 */
    private static AbstractVehicleWeapon<?> resolveSoundWeapon(AbstractVehicleWeapon<?> weapon) {
        if (weapon instanceof VehicleMultiWeapons multi) {
            return multi.getSelectedWeapon();
        }
        return weapon;
    }

    /** 登记待抑制键（先清理过期项；超上限丢最旧）。仅客户端主线程调用（观瞄开火声处理器共用）。 */
    static void registerPending(ResourceLocation soundLocation, Vec3 pos) {
        // 调用统一登记入口；普通发射固定使用开火完成时的坐标，不额外等待。
        registerPending(soundLocation, () -> pos, 0L, System.currentTimeMillis());
    }

    /** 本体导弹在冷发射时长后才播放原声；须跟随当时炮口坐标，不能套用普通两秒过期键。 */
    static void registerDelayedPending(ResourceLocation soundLocation, WeaponUnit unit) {
        // 调用本体武器站配置与坐标 getter，跟随原 onClientFire 的延迟和实际取点方式。
        registerPending(soundLocation, unit::worldPivotPosition,
                Math.max(0, unit.getColdLaunchTimeTick()) * 50L, System.currentTimeMillis());
    }

    /** 共用登记入口；时间与位置供应器可控，便于验证长冷发射和载具移动。 */
    static void registerPending(ResourceLocation soundLocation, Supplier<Vec3> position, long delayMillis, long now) {
        Iterator<PendingSuppress> it = PENDING.iterator();
        while (it.hasNext()) {
            if (it.next().isExpired(now)) {
                it.remove();
            }
        }
        if (PENDING.size() >= PENDING_MAX) {
            PENDING.remove(0);
        }
        PENDING.add(new PendingSuppress(soundLocation, position, now + delayMillis,
                now + delayMillis + PENDING_EXPIRE_MS));
    }

    /** 只消费当前时间窗口内声音 ID 与坐标都匹配的一次原声。 */
    static boolean consumePending(ResourceLocation soundLocation, Vec3 pos, long now) {
        Iterator<PendingSuppress> iterator = PENDING.iterator();
        while (iterator.hasNext()) {
            PendingSuppress pending = iterator.next();
            if (pending.isExpired(now)) {
                iterator.remove();
            } else if (pending.matches(soundLocation, pos, now)) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    /** 切换世界时释放延迟抑制键里的武器站引用，避免匹配到新世界的同名声音。 */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            PENDING.clear();
        }
    }

    /** 声音、位置供应器与有效时间窗；延迟声保留武器站引用，过期后立即释放。 */
    private record PendingSuppress(
            /** 本体实际播放的声音 ID。 */ ResourceLocation soundLocation,
            /** 固定炮口坐标，或延迟冷发射时的当前炮口坐标。 */ Supplier<Vec3> position,
            /** 本体允许开始播放的客户端毫秒时间。 */ long readyAtMillis,
            /** 此条一次性抑制键失效的客户端毫秒时间。 */ long expireAtMillis) {
        boolean isExpired(long now) {
            return now >= expireAtMillis;
        }

        boolean matches(ResourceLocation location, Vec3 pos, long now) {
            if (now < readyAtMillis || !soundLocation.equals(location)) {
                return false;
            }
            return position.get().distanceToSqr(pos) < 1.0E-4;
        }
    }
}
