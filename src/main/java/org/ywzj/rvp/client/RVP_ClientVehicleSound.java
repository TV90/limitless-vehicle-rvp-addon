package org.ywzj.rvp.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.SampledFloat;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager;
import org.ywzj.rvp.network.S2CVehicleSound;
import org.ywzj.rvp.network.S2CVehicleSound.Kind;
import org.ywzj.rvp.util.RVP_WeaponResolveHelper;
import org.ywzj.rvp.weapon.sound.RVP_VehicleSoundService;
import org.ywzj.vehicle.api.event.VehicleFireEvent;
import org.ywzj.vehicle.audio.VehicleSound;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 默认导弹发射声的客户端播放：优先本地播放，独立声音消息只做兜底。
 *
 * <p>主路径：本体 {@code ServerVehicleFire} 只发给追踪载具实体的玩家，客户端收到开火完成事件后
 * 按本地距离选组并直接播放，零额外数据包。兜底路径：服务端只向没有载具实体的听者补发
 * {@link S2CVehicleSound}，此类听者按世界坐标播放同一组音频。</p>
 */
public final class RVP_ClientVehicleSound {

    /** 音频原始音量与音调倍率，最终距离由样本的衰减距离控制。 */
    private static final SampledFloat UNIT_SAMPLE = random -> 1.0F;
    /** 每组声音的变体数量；冷发射近／远各 3 个，其余发射组同为 3 个。 */
    private static final int VARIANTS_PER_KIND = 3;
    /** 每种声音的近音或普通音样本，在内存中描述，不改写 sounds.json。 */
    private static final Map<Kind, List<SoundClip>> NEAR_CLIPS = createClips(false);
    /** 冷发射远音样本，其余类型复用普通样本。 */
    private static final Map<Kind, List<SoundClip>> FAR_CLIPS = createClips(true);

    private RVP_ClientVehicleSound() {
    }

    /** 兜底消息：没有载具实体的远距听者按世界坐标播放同一声音组。 */
    public static void accept(S2CVehicleSound message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !minecraft.level.dimension().location().equals(message.dimension())) {
            return;
        }
        // 调用统一声音优先级；只有默认音频可用且未配置非本体声音时才接管。
        if (!allowsMissileSound(message.kind(), message.weaponId(), message.position())) {
            return;
        }
        // 调用本类实体查询；兜底听者通常没有实体，数字 ID 被复用时继续走世界坐标播放。
        playLaunch(message.kind(), message.position(), findVehicle(message));
    }

    /** 客户端本地播放默认发射声；返回是否接管（接管时调用方抑制本体那一发开火声）。 */
    static boolean playLocalLaunch(VehicleFireEvent.Post event) {
        if (event == null || !event.isClientSide()) {
            return false;
        }
        // 调用本项目代理解包器，按实际发射弹种判断，不按武器 ID 或名称判断。
        AbstractVehicleWeapon<?> weapon = RVP_WeaponResolveHelper.unwrap(event.getWeapon());
        if (weapon == null || weapon.getWeaponUnit() == null) {
            return false;
        }
        // 调用双端共用分类入口，与服务端兜底消息采用同一套分类。
        Kind kind = RVP_VehicleSoundService.resolveMissileKind(event.getVehicle(), weapon);
        ResourceLocation weaponId = kind == null ? null : weapon.getData().getWeaponId();
        if (weaponId == null) {
            return false;
        }
        // 调用本体武器站获取发射位置，与本体开火声使用同一坐标。
        Vec3 position = weapon.getWeaponUnit().worldPivotPosition();
        if (!allowsMissileSound(kind, weaponId, position)) {
            return false;
        }
        playLaunch(kind, position, event.getVehicle());
        return true;
    }

    /** 按听者距离选组并播放；载具实体已加载且配置相机相对距离时沿用本体跟随定位。 */
    private static void playLaunch(Kind kind, Vec3 position, @Nullable AbstractVehicle vehicle) {
        // 调用同组可用音频选择器，只在实际播放时随机，不让优先级查询额外消费随机数。
        SoundClip clip = chooseClip(kind, kind.usesFarSound(listenerDistance(position)));
        if (clip == null) {
            return;
        }
        float relativeDistance = vehicle == null ? 0.0F
                : RVP_VehicleExtendedConfigManager.getCameraRelativeFireSoundDistance(vehicle);
        if (relativeDistance > 0.0F) {
            MissileVehicleSound sound = new MissileVehicleSound(clip, vehicle, position, relativeDistance);
            // 调用本体跟随更新，首帧即使用正确坐标；仅骑乘者沿用相机相对定位。
            sound.tick();
            sound.play();
        } else {
            Minecraft.getInstance().getSoundManager().play(new PositionSound(clip, position));
        }
    }

    /** 供现有发射管理器判断本次发射是否由默认声接管：接管时抑制本体声，其他处理器让出。 */
    static boolean willTakeOver(VehicleFireEvent.Post event) {
        if (event == null || !event.isClientSide()) {
            return false;
        }
        // 调用本项目代理解析与双端共用分类，保持客户端判断与服务端兜底一致。
        AbstractVehicleWeapon<?> weapon = RVP_WeaponResolveHelper.unwrap(event.getWeapon());
        Kind kind = RVP_VehicleSoundService.resolveMissileKind(event.getVehicle(), weapon);
        return kind != null && allowsMissileSound(kind, weapon.getData().getWeaponId(),
                weapon.getWeaponUnit().worldPivotPosition());
    }

    /** 先保护武器表现文件里的整套自定义声音，再检查当前距离音频组是否存在。 */
    private static boolean allowsMissileSound(Kind kind, @Nullable ResourceLocation weaponId, Vec3 position) {
        boolean far = kind.usesFarSound(listenerDistance(position));
        // 调用共用声音表读取和优先级判断；没有表现文件时也可使用默认导弹声。
        return RVP_WeaponFireSoundPolicy.allowsDefaultSound(!availableClips(kind, far).isEmpty(),
                RVP_ClientWeaponFireSound.getConfiguredSounds(weaponId));
    }

    /** 相机是原版声音引擎实际听者，第三人称也按实际听者位置选近远组。 */
    private static double listenerDistance(Vec3 position) {
        return Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().distanceTo(position);
    }

    /** 实体已加载时检查 UUID，实体未加载时声音完全不依赖此查询。 */
    @Nullable
    private static AbstractVehicle findVehicle(S2CVehicleSound message) {
        Entity entity = Minecraft.getInstance().level.getEntity(message.entityId());
        return entity instanceof AbstractVehicle vehicle && vehicle.getUUID().equals(message.vehicleId())
                ? vehicle : null;
    }

    /** 每次实际播放随机选择一个仍存在的音频，单个样本缺失不影响同组其余样本。 */
    @Nullable
    private static SoundClip chooseClip(Kind kind, boolean far) {
        List<SoundClip> clips = availableClips(kind, far);
        return clips.isEmpty() ? null : clips.get(Minecraft.getInstance().level.random.nextInt(clips.size()));
    }

    /** 音频缺失时不抑制本体声，资源重载后按新的资源管理器即时重新判断。 */
    private static List<SoundClip> availableClips(Kind kind, boolean far) {
        return (far ? FAR_CLIPS : NEAR_CLIPS).get(kind).stream()
                .filter(clip -> Minecraft.getInstance().getResourceManager()
                        .getResource(clip.sample().getPath()).isPresent()).toList();
    }

    /** 构造完整的内存样本表；音频名称逐一对应已有资源，不扫描或改写用户文件。 */
    private static Map<Kind, List<SoundClip>> createClips(boolean far) {
        Map<Kind, List<SoundClip>> clips = new HashMap<>();
        for (Kind kind : Kind.values()) {
            ResourceLocation eventId = ResourceLocation.fromNamespaceAndPath(RVP_MOD.MOD_ID,
                    "default_vehicle_" + kind.name().toLowerCase(Locale.ROOT) + (far ? "_far" : "_near"));
            SoundClip[] group = new SoundClip[VARIANTS_PER_KIND];
            for (int i = 0; i < VARIANTS_PER_KIND; i++) {
                // 调用本类路径规则生成原有文件名，并将最远距离直接交给原版线性衰减。
                Sound sample = new Sound(RVP_MOD.MOD_ID + ":" + samplePath(kind, far, i),
                        UNIT_SAMPLE, UNIT_SAMPLE, 1, Sound.Type.FILE, false, false, kind.maximumDistance());
                WeighedSoundEvents soundEvents = new WeighedSoundEvents(eventId, null);
                soundEvents.addSound(sample);
                group[i] = new SoundClip(eventId, sample, soundEvents);
            }
            clips.put(kind, List.copyOf(Arrays.asList(group)));
        }
        return clips;
    }

    /** 原有音频路径；编号从 0 开始。 */
    private static String samplePath(Kind kind, boolean far, int variant) {
        return switch (kind) {
            case COLD_LAUNCH -> "default/missile_launch/cold_launch/" + (far ? "far/cold_launch_far-" : "near/cold_launch-")
                    + String.format(Locale.ROOT, "%03d", variant + 1);
            case SURFACE_TO_AIR -> "default/missile_launch/surface_to_air/surface_to_air_missile_launch_" + (variant + 1);
            case AIR2AIR -> "default/missile_launch/air2air/air2air_missile_launch_" + (variant + 1);
        };
    }

    /** 供资源验证构造指定声音，不依赖真实游戏世界或 sounds.json 注册。 */
    static SoundInstance createPositionSound(Kind kind, boolean far, int variant, Vec3 position) {
        return new PositionSound((far ? FAR_CLIPS : NEAR_CLIPS).get(kind).get(variant), position);
    }

    /** 内存中的单个音频描述。 */
    private record SoundClip(
            /** 内部事件 ID。 */ ResourceLocation eventId,
            /** 指向已有 .ogg 的文件描述，内含最远距离。 */ Sound sample,
            /** 交给原版声音引擎解析的单样本事件。 */ WeighedSoundEvents event) {
    }

    /** 单次定位片段；每次发射只播放一个样本。 */
    private static final class PositionSound extends SimpleSoundInstance implements TickableSoundInstance {
        /** 本次选中的声音文件与内存事件。 */
        private final SoundClip clip;

        private PositionSound(SoundClip clip, Vec3 position) {
            super(clip.eventId(), SoundSource.PLAYERS, 1.0F, 1.0F, SoundInstance.createUnseededRandom(),
                    false, 0, Attenuation.LINEAR, position.x, position.y, position.z, false);
            this.clip = clip;
            this.sound = clip.sample();
        }

        @Override
        public WeighedSoundEvents resolve(SoundManager soundManager) {
            sound = clip.sample();
            return clip.event();
        }

        @Override
        public void tick() {
            // 单次发射声保留发射位置，不跟随载具。
        }

        @Override
        public boolean isStopped() {
            // 单次发射声由声音引擎按样本长度自然结束，不需要外部提前停声。
            return false;
        }
    }

    /** 沿用本体骑乘者相机相对定位；旁观者保持真实坐标，最远距离仍为 150 或 2048 格。 */
    private static final class MissileVehicleSound extends VehicleSound {
        /** 本次选中的声音文件与内存事件。 */
        private final SoundClip clip;

        private MissileVehicleSound(SoundClip clip, AbstractVehicle vehicle, Vec3 position, float distance) {
            super(SoundEvent.createVariableRangeEvent(clip.eventId()), position.subtract(vehicle.position()),
                    1.0F, distance, 1.0F, false, 0, false, false, vehicle.getId());
            this.clip = clip;
            this.sound = clip.sample();
        }

        @Override
        public Vec3 calRelativePos(Vec3 soundPos, Vec3 targetPos, boolean cameraEntityOnVehicle) {
            // 调用本体相机相对算法照顾骑乘者；旁观者不压缩距离，避免把最大可听距离放大。
            return cameraEntityOnVehicle ? super.calRelativePos(soundPos, targetPos, true) : soundPos;
        }

        @Override
        public WeighedSoundEvents resolve(SoundManager soundManager) {
            sound = clip.sample();
            return clip.event();
        }
    }
}
