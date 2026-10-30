package org.ywzj.rvp.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager;
import org.ywzj.vehicle.api.event.VehicleFireEvent;
import org.ywzj.vehicle.audio.VehicleSound;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleMultiWeapons;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * [RVP] 车内/车外开火音效（2026-10-25，用户需求；2026-10-25 二次修订：视角切换实时跟随）：
 * "坐在车里和在车外听到的炮声不一样"——武器 display 用 {@code sounds.fire} 表示车外（普通）音轨、
 * {@code sounds.sight_fire} 表示观瞄视角音轨。
 *
 * <p>本处理器只接管<b>本地玩家自己乘坐的载具</b>的开火声：开火瞬间把两条音轨<b>同时</b>作为
 * {@link VehicleSound}（TickableSoundInstance，原版 SoundEngine 每 tick 重读 {@code getVolume()}）
 * 在炮口位置播放，其中一条静音；之后每 tick 依据
 * {@code LocalVehiclePlayer.instance.viewType == SCOPE} 切换哪条可听。因此：</p>
 *
 * <ul>
 *   <li>观瞄视角开火 → 切第三人称：车外音轨一直在静音播放，切换后直接<b>从当前位置</b>接上发声；</li>
 *   <li>第三人称开火 → 观瞄视角：同理，观瞄音轨已在静音播放就从当前位置接上。</li>
 * </ul>
 *
 * <p><b>不补播</b>：音轨一旦自然播完就是播完了（观瞄采样 1.22 s、车外采样约 5 s），切到已播完的那条
 * 音轨只会静音——绝不从头重放。Ogg Vorbis 无法定位，SoundInstance 也没有 seek，任何"从头补"都会明显
 * 重来一次炮响。</p>
 *
 * <p>本体 {@code AllEvents.onVehicleFire}（DEFAULT）仍会用原版 {@code level.playSound} 播
 * {@code sounds.fire}，本处理器以 HIGHEST 优先级先登记"待抑制"键（声音 id + 炮口坐标），
 * 由 {@link RVP_ClientCameraRelativeFireSound} 的 {@code PlayLevelSoundEvent.AtPosition} 处理器取消，
 * 不会双声。</p>
 *
 * <p>数据驱动：{@code BaseDisplay} 会把 {@code sounds} 下所有键原样装入 {@code soundEvents}，
 * display JSON 增加 {@code "sight_fire"} 即可，无需改动本体解析；未配置的武器走
 * {@link #resolveSightFireSound} 返回 null 的快速路径，继续由本体与相机相对处理器负责。</p>
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ClientSightFireSound {

    /** display JSON 中观瞄视角音轨的键名。 */
    private static final String SIGHT_FIRE_KEY = "sight_fire";

    /** 可听音量：与本体 onClientFire 的 volume=4 等效（gain 被 clamp 到 1；可听半径 = max(4,1)×16 = 64 格）。 */
    private static final float AUDIBLE_VOLUME = 4f;

    /** 静音音量：不能取 0——原版 SoundEngine.play 对音量为 0 的实例直接跳过，之后再也拉不起来。 */
    private static final float MUTED_VOLUME = 0.0001f;

    /** play 的登记宽限 tick 数：play 可能被排到下一 tick 的任务队列，期间 isActive 还不可靠。 */
    private static final int START_GRACE_TICKS = 3;

    /** 同时存活的炮击上限（防御性丢最旧，正常情况远达不到）。 */
    private static final int SHOT_MAX = 16;

    /** 仍在下落音阶段的本车炮击。 */
    private static final List<Shot> SHOTS = new ArrayList<>();

    private RVP_ClientSightFireSound() {}

    /**
     * 本车开火（客户端）：两条音轨同时起播、按当前视角决定初始可听项，并抑制本体将要播放的 fire。
     * HIGHEST 保证先于本体 AllEvents（DEFAULT）执行。
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onVehicleFirePost(VehicleFireEvent.Post event) {
        // 单机集成服务端也会 post 该事件，只处理客户端侧
        if (!event.isClientSide()) {
            return;
        }
        // 调用 RVP 武器声音管理器：默认音接管时不再同时启动原本的视角音轨。
        if (RVP_ClientWeaponFireSound.willTakeOver(event)) {
            return;
        }
        Shot shot = createShot(event);
        if (shot == null) {
            return;
        }
        // 抑制本体 AllEvents 随后用 level.playSound 播放的 sounds.fire（本处理器已自己重放两条音轨，
        // 不抑制会双声）；抑制键必须与本体同源：声音 id + worldPivotPosition 炮口坐标
        RVP_ClientCameraRelativeFireSound.registerPending(shot.fireSound.getLocation(), shot.muzzlePos);
        if (SHOTS.size() >= SHOT_MAX) {
            SHOTS.remove(0).stop();
        }
        SHOTS.add(shot);
        shot.start();
    }

    /** 每 tick 推进所有炮击：清理已播完的音轨组合（视角切换由各音轨自身的 tick 完成）。 */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || SHOTS.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Iterator<Shot> it = SHOTS.iterator();
        while (it.hasNext()) {
            if (!it.next().update(minecraft)) {
                it.remove();
            }
        }
    }

    /** 构造一次本车炮击；不满足"本地玩家乘坐该载具 + 武器配置了 sight_fire"时返回 null。 */
    private static Shot createShot(VehicleFireEvent.Post event) {
        LocalPlayer player = Minecraft.getInstance().player;
        // 只有本地玩家正坐着开火的载具才需要区分车内/车外听感；他人载具、步行旁观一律走本体
        if (player == null || player.getVehicle() != event.getVehicle()) {
            return null;
        }
        SoundEvent sightSound = resolveSightFireSound(event);
        if (sightSound == null) {
            return null;
        }
        AbstractVehicleWeapon<?> weapon = resolveSoundWeapon(event.getWeapon());
        // 炮口坐标来自武器挂点（WeaponUnit），与本体 onClientFire 的 level.playSound 坐标同源
        WeaponUnit weaponUnit = weapon == null ? null : weapon.getWeaponUnit();
        if (weaponUnit == null) {
            return null;
        }
        AbstractVehicle vehicle = event.getVehicle();
        Vec3 muzzlePos = weaponUnit.worldPivotPosition();
        // VehicleSound 需要相对载具原点的偏移，每 tick 随载具旋转重锚定
        Vec3 offset = muzzlePos.subtract(vehicle.position());
        // 相机相对距离因子：未配置（<=0）时取 4，与本体 volume 4 的 64 格衰减曲线等价
        float distance = RVP_VehicleExtendedConfigManager.getCameraRelativeFireSoundDistance(vehicle);
        if (distance <= 0f) {
            distance = 4f;
        }
        return new Shot(vehicle, weapon.getFireSound(), sightSound, muzzlePos, offset, distance);
    }

    /**
     * 解析该次开火对应的观瞄音轨；不满足"本车 + 配置了 sight_fire"时返回 null。
     * 相机相对开火声处理器也据此判断是否让出本次播放（视角无关：是否让出只看本车与配置）。
     */
    static SoundEvent resolveSightFireSound(VehicleFireEvent.Post event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || player.getVehicle() != event.getVehicle()) {
            return null;
        }
        AbstractVehicleWeapon<?> weapon = resolveSoundWeapon(event.getWeapon());
        if (weapon == null) {
            return null;
        }
        // BaseDisplay 已把 sounds 下所有键装入 soundEvents，sight_fire 无需本体解析支持
        SoundEvent sightSound = ClientAssetsManager.INSTANCE
                .getWeaponDisplay(weapon.getData().getWeaponId())
                .map(display -> display.getSoundEvents().get(SIGHT_FIRE_KEY))
                .orElse(null);
        SoundEvent fireSound = weapon.getFireSound();
        // 没有车外音轨或两条音轨实际是同一个声音时，没有区分意义
        if (sightSound == null || fireSound == null
                || sightSound.getLocation().equals(fireSound.getLocation())) {
            return null;
        }
        return sightSound;
    }

    /** 多武器组合解析为实际发声的当前选中子武器（与本体 onClientFire 委托一致）。 */
    private static AbstractVehicleWeapon<?> resolveSoundWeapon(AbstractVehicleWeapon<?> weapon) {
        if (weapon instanceof VehicleMultiWeapons multi) {
            return multi.getSelectedWeapon();
        }
        return weapon;
    }

    /** 本地玩家是否正在该载具上且处于观瞄视角（只有这种状态听观瞄音轨）。 */
    private static boolean isLocalSightView(AbstractVehicle vehicle) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || player.getVehicle() != vehicle) {
            return false;
        }
        LocalVehiclePlayer localVehiclePlayer = LocalVehiclePlayer.instance;
        return localVehiclePlayer != null
                && localVehiclePlayer.viewType == LocalVehiclePlayer.ViewType.SCOPE;
    }

    /** 原版 SoundManager#isActive：声音仍在原版声道表里（包括静音播放中的实例）。 */
    private static boolean isSoundActive(Minecraft minecraft, VehicleSound sound) {
        return sound != null && minecraft.getSoundManager().isActive(sound);
    }

    /** 一次本车炮击：同一炮口位置上并行播放的两条音轨 + 视角跟随状态。 */
    private static final class Shot {

        /** 开火的载具（本地玩家正乘坐的）。 */
        private final AbstractVehicle vehicle;
        /** 车外/普通视角音轨（display 的 sounds.fire）。 */
        private final SoundEvent fireSound;
        /** 观瞄视角音轨（display 的 sounds.sight_fire）。 */
        private final SoundEvent sightSound;
        /** 炮口世界坐标：仅用于与本体的 level.playSound 位置精确匹配。 */
        private final Vec3 muzzlePos;
        /** 炮口相对载具原点的偏移。 */
        private final Vec3 offset;
        /** 相机相对距离因子（VehicleSound 的 distance 参数）。 */
        private final float distance;
        /** 车外音轨实例。 */
        private ViewFireSound external;
        /** 观瞄音轨实例。 */
        private ViewFireSound sight;
        /** 已存活 tick 数：给 play 的异步登记留宽限。 */
        private int ageTicks;

        private Shot(AbstractVehicle vehicle, SoundEvent fireSound, SoundEvent sightSound,
                     Vec3 muzzlePos, Vec3 offset, float distance) {
            this.vehicle = vehicle;
            this.fireSound = fireSound;
            this.sightSound = sightSound;
            this.muzzlePos = muzzlePos;
            this.offset = offset;
            this.distance = distance;
        }

        private void start() {
            external = new ViewFireSound(this, false);
            sight = new ViewFireSound(this, true);
            external.start();
            sight.start();
        }

        /** 推进一次；返回 false 表示这次炮击已彻底结束、可以从列表移除。 */
        private boolean update(Minecraft minecraft) {
            // 载具已不在当前世界（切维度/卸载/被移除）：立即停掉两条音轨
            if (minecraft.level == null || minecraft.level.getEntity(vehicle.getId()) != vehicle) {
                stop();
                return false;
            }
            ageTicks++;
            if (ageTicks <= START_GRACE_TICKS) {
                return true;
            }
            // 两条音轨都自然播完即结束。采样不等长：观瞄音轨先结束时，之后再切回观瞄只会静音，
            // 绝不从头重放；车外音轨仍在静音走完剩余时长，切回第三人称照常从当前位置接上
            return isSoundActive(minecraft, external) || isSoundActive(minecraft, sight);
        }

        private void stop() {
            if (external != null) {
                external.stop();
            }
            if (sight != null) {
                sight.stop();
            }
        }
    }

    /**
     * 单条音轨实例：复用本体 {@link VehicleSound}（每 tick 把声源按载具朝向重锚定到相机附近，
     * 骑乘时恒 8 格，保证车内听感不被距离衰减吃掉），只额外按当前视角把音量在可听/静音之间切换。
     */
    private static final class ViewFireSound extends VehicleSound {

        /** 所属炮击（共享炮口偏移、载具与两条采样）。 */
        private final Shot shot;
        /** 是否观瞄音轨。 */
        private final boolean sightVariant;

        private ViewFireSound(Shot shot, boolean sightVariant) {
            // super：SoundEvent、炮口相对偏移、初始音量（先给静音值，start 里 tick 会立刻校正）、
            // 距离因子、pitch 1、不循环、无淡入淡出、绑定的载具实体 id
            super(sightVariant ? shot.sightSound : shot.fireSound, shot.offset, MUTED_VOLUME,
                    shot.distance, 1f, false, 0, false, false, shot.vehicle.getId());
            this.shot = shot;
            this.sightVariant = sightVariant;
            // 原版在 play 时就把"可听半径 = max(volume,1)×16"写进声道，之后不再更新：
            // 起播静音的那条音轨会永久停在 16 格，切到可听时会被距离衰减砍半。
            // 本路径只服务"本地玩家乘坐自己载具"，声源又被 VehicleSound 锚到相机附近，
            // 因此关掉距离衰减、只保留位置声像，两条音轨音量可控且与本体等响。
            this.attenuation = SoundInstance.Attenuation.NONE;
        }

        /** 先 tick 一次把 x/y/z 定位到炮口再 play：VehicleSound 构造时实体尚未解析，坐标还是 0,0,0。 */
        private void start() {
            tick();
            play();
        }

        @Override
        public void tick() {
            // super：重锚定声源位置（随载具旋转/相机相对），载具被移除时自行 stop
            super.tick();
            setVolume(isAudible() ? AUDIBLE_VOLUME : MUTED_VOLUME);
        }

        /** 当前视角该不该听到这条音轨。 */
        private boolean isAudible() {
            return sightVariant == isLocalSightView(shot.vehicle);
        }
    }
}
