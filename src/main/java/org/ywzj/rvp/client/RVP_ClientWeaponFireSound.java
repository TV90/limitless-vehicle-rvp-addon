package org.ywzj.rvp.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.SampledFloat;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager;
import org.ywzj.rvp.util.RVP_WeaponResolveHelper;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_EffectsData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.api.event.VehicleFireEvent;
import org.ywzj.vehicle.audio.VehicleSound;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;
import org.ywzj.vehicle.client.resource.vehicle.BaseDisplay;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.TrackedVehicle;
import org.ywzj.vehicle.entity.vehicle.WheeledVehicle;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleMultiWeapons;
import org.ywzj.vehicle.vehicle.weapon.VehicleMissile;
import org.ywzj.vehicle.vehicle.weapon.VehicleRocket;
import org.ywzj.vehicle.vehicle.weapon.VehicleWeaponAgent;

import java.util.List;
import java.util.Objects;

/**
 * 客户端武器发射声音管理：优先保留载具包自定义声音，其次接管已实现的默认声音。
 * 地面大口径火箭与导弹默认声都在本地开火完成事件播放；服务端只向追踪范围外的听者补发导弹声。
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ClientWeaponFireSound {

    /** 管理器内部使用的声音事件 ID，不要求修改已有 sounds.json。 */
    private static final ResourceLocation GROUND_ROCKET_EVENT_ID =
            ResourceLocation.fromNamespaceAndPath(RVP_MOD.MOD_ID, "default_ground_rocket_launch");
    /** 用户提供的火箭音频资源路径，不含 sounds/ 前缀和 .ogg 后缀。 */
    private static final String GROUND_ROCKET_SAMPLE_ID =
            "ywzj_rvp:default/rocket_launch/large/large_rocket_launch";
    /** 音频文件自身的音量和音调倍率，固定为 1，由播放实例控制最终音量。 */
    private static final SampledFloat UNIT_SAMPLE = random -> 1.0F;
    /** 单个火箭音频的原版播放描述；原版资源系统仍负责读取和缓存对应的 .ogg。 */
    private static final Sound GROUND_ROCKET_SAMPLE = new Sound(GROUND_ROCKET_SAMPLE_ID,
            UNIT_SAMPLE, UNIT_SAMPLE, 1, Sound.Type.FILE, false, false, 16);
    /** 内存中的声音事件描述，避免覆盖用户现有的 sounds.json。 */
    private static final WeighedSoundEvents GROUND_ROCKET_EVENT = createGroundRocketEvent();
    /** 本体普通开火音量；同时决定原版声音距离衰减的基础范围。 */
    private static final float NATIVE_FIRE_VOLUME = 4.0F;

    private RVP_ClientWeaponFireSound() {
    }

    /** 构造内存中的声音事件，并绑定用户已有的火箭音频文件。 */
    private static WeighedSoundEvents createGroundRocketEvent() {
        WeighedSoundEvents soundEvents = new WeighedSoundEvents(GROUND_ROCKET_EVENT_ID, null);
        soundEvents.addSound(GROUND_ROCKET_SAMPLE);
        return soundEvents;
    }

    /**
     * 服务端确认开火后，在客户端播放 RVP 默认声，并只抑制本体即将播放的同一次开火声。
     * 事件优先级必须先于本体开火处理器；未接管时不改变原有播放。
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onVehicleFirePost(VehicleFireEvent.Post event) {
        // 调用客户端本地默认发射声：本客户端已播放时只取消同一发本体声，避免叠音。
        // 追踪范围外的听者由服务端兜底声音消息播放，二者不会在同一条客户端上重复。
        if (RVP_ClientVehicleSound.playLocalLaunch(event)) {
            AbstractVehicleWeapon<?> missile = RVP_WeaponResolveHelper.unwrap(event.getWeapon());
            SoundEvent original = getOriginalFireSound(event.getWeapon());
            if (original != null) {
                if (missile instanceof VehicleMissile && missile.getWeaponUnit().getColdLaunchTimeTick() > 0) {
                    // 调用延时抑制入口：本体冷发射会等待后才取当前炮口坐标，必须跟随发射站匹配。
                    RVP_ClientCameraRelativeFireSound.registerDelayedPending(original.getLocation(),
                            missile.getWeaponUnit());
                } else {
                    // 调用既有一次性抑制队列，与本体实际播放位置匹配，不影响其他声音。
                    RVP_ClientCameraRelativeFireSound.registerPending(original.getLocation(),
                            missile.getWeaponUnit().worldPivotPosition());
                }
            }
            return;
        }
        // 调用本管理器选择发声武器：失败时允许现有声音处理器和本体正常工作。
        AbstractVehicleWeapon<?> weapon = resolveGroundRocket(event);
        if (weapon == null) {
            return;
        }
        // 调用本体武器站获取开火位置，保证替换声和待抑制的本体声使用同一位置。
        WeaponUnit unit = weapon.getWeaponUnit();
        Vec3 position = unit.worldPivotPosition();
        // 按本体实际调用的武器对象读取声音；代理对象本身不会转发 onClientFire。
        SoundEvent original = getOriginalFireSound(event.getWeapon());
        if (original != null) {
            // 复用已有的一次性抑制队列，避免本体声与 RVP 声叠加。
            RVP_ClientCameraRelativeFireSound.registerPending(original.getLocation(), position);
        }

        AbstractVehicle vehicle = event.getVehicle();
        // 调用本项目载具配置：已有的相机相对开火距离配置仍应对替换声生效。
        float distance = RVP_VehicleExtendedConfigManager.getCameraRelativeFireSoundDistance(vehicle);
        if (distance > 0.0F) {
            GroundRocketVehicleSound sound = new GroundRocketVehicleSound(vehicle, position, distance);
            // 调用本体声音定位逻辑，先解析载具并更新声源坐标，避免首帧停在原点。
            sound.tick();
            // 调用本体声音播放入口，使相机相对声继续跟随载具。
            sound.play();
        } else {
            // 使用本管理器创建定位音，继续交给原版声音引擎管理音量和资源加载。
            Minecraft.getInstance().getSoundManager().play(createGroundRocketPositionSound(position));
        }
    }

    /** 构造普通定位火箭声，也供测试验证音频路径和原版声音解析。 */
    static SoundInstance createGroundRocketPositionSound(Vec3 position) {
        return new GroundRocketPositionSound(position);
    }

    /** 供现有开火声音处理器判断是否应让出此次播放。 */
    static boolean willTakeOver(VehicleFireEvent.Post event) {
        // 调用同一个选择入口，保证让出条件与实际播放条件一致。
        return RVP_ClientVehicleSound.willTakeOver(event) || resolveGroundRocket(event) != null;
    }

    /** 返回应由 RVP 接管的实际火箭武器；不满足条件时返回 null。 */
    @Nullable
    private static AbstractVehicleWeapon<?> resolveGroundRocket(VehicleFireEvent.Post event) {
        if (event == null || !event.isClientSide()) {
            return null;
        }
        AbstractVehicle vehicle = event.getVehicle();
        boolean groundVehicle = vehicle instanceof WheeledVehicle || vehicle instanceof TrackedVehicle;
        if (!groundVehicle) {
            return null;
        }
        // 调用本项目代理解析器，取得武器站或多弹种容器里真正发射的武器。
        AbstractVehicleWeapon<?> weapon = RVP_WeaponResolveHelper.unwrap(event.getWeapon());
        if (weapon == null || weapon.getWeaponUnit() == null) {
            return null;
        }
        // 调用 RVP 武器数据模型识别类型，也支持本体的火箭武器，不依赖具体武器 ID。
        boolean rocketWeapon = weapon instanceof VehicleRocket
                || weapon instanceof RVP_WeaponBase rvpWeapon
                && rvpWeapon.getData().getWeaponKind() == RVP_EnumWeaponKind.ROCKET;
        if (!rocketWeapon) {
            return null;
        }

        // 调用本体武器数据获取 ID，再从客户端表现管理器读取该武器配置的全部声音。
        ResourceLocation weaponId = weapon.getData().getWeaponId();
        List<ResourceLocation> configuredSounds = getConfiguredSounds(weaponId);
        // 调用本项目口径解析：优先根级 caliber，根级缺失时才回退 effects_data.caliber。
        Float caliberMm = resolveRocketCaliber(weapon);
        // 音频不存在时不接管，避免把已配置的本体声音抑制掉。
        boolean sampleAvailable = Minecraft.getInstance().getResourceManager()
                .getResource(GROUND_ROCKET_SAMPLE.getPath()).isPresent();
        // 调用本项目声音策略：只有解析后的有效口径达到 220 mm 且无自定义声音时才接管发射声。
        return RVP_WeaponFireSoundPolicy.useGroundRocketSound(groundVehicle, rocketWeapon,
                caliberMm, sampleAvailable, configuredSounds) ? weapon : null;
    }

    /** 读取武器表现文件里的全部声音；火箭和导弹共用同一套自定义声音保护规则。 */
    static List<ResourceLocation> getConfiguredSounds(@Nullable ResourceLocation weaponId) {
        // 调用本体客户端表现管理器，缺少表现文件时返回空表，允许 RVP 默认声接管。
        return weaponId == null ? List.of() : ClientAssetsManager.INSTANCE.getWeaponDisplay(weaponId)
                .map(BaseDisplay::getSoundEvents)
                .map(sounds -> sounds.values().stream().filter(Objects::nonNull)
                        .map(SoundEvent::getLocation).toList())
                .orElseGet(List::of);
    }

    /**
     * 解析当前火箭的有效口径。
     * RVP 基类根级 caliber 缺省为 5.8 mm，因此必须用加载器记录的配置标记，
     * 否则这个基类默认值会错误地阻止 effects_data.caliber 回退。
     */
    @Nullable
    private static Float resolveRocketCaliber(AbstractVehicleWeapon<?> weapon) {
        if (weapon.getData() instanceof RVP_WeaponData rvpData) {
            Float rootCaliberMm = rvpData.hasRootCaliberOverride() ? rvpData.getCaliber() : null;
            RVP_EffectsData effectsData = rvpData.getEffectsData();
            Float effectsCaliberMm = effectsData.hasCaliberOverride() ? effectsData.getCaliber() : null;
            // 调用本项目声音策略的口径回退方法，保持根级与 effects_data 的优先级集中定义。
            return RVP_WeaponFireSoundPolicy.resolveRocketCaliber(rootCaliberMm, effectsCaliberMm);
        }
        // 调用本体武器数据 getter：本体火箭没有 effects_data，直接使用根级 caliber。
        return weapon.getData().getCaliber();
    }

    /**
     * 读取本体 onClientFire 实际会播放的声音。
     * 多弹种容器会转发给选中武器，而武器站代理不会转发声音播放。
     */
    @Nullable
    private static SoundEvent getOriginalFireSound(@Nullable AbstractVehicleWeapon<?> weapon) {
        while (weapon instanceof VehicleMultiWeapons multi) {
            // 调用本体多弹种容器，追踪实际执行 onClientFire 的选中武器。
            weapon = multi.getSelectedWeapon();
        }
        if (weapon == null || weapon instanceof VehicleWeaponAgent || weapon.getData().getWeaponId() == null) {
            return null;
        }
        // 调用本体声音读取方法，只抑制它本来确实会播放的声音。
        return weapon.getFireSound();
    }

    /** 原版定位声音：直接使用已有音频，保持本体开火音量和位置。 */
    private static final class GroundRocketPositionSound extends SimpleSoundInstance {

        private GroundRocketPositionSound(Vec3 position) {
            super(GROUND_ROCKET_EVENT_ID, SoundSource.PLAYERS, NATIVE_FIRE_VOLUME, 1.0F,
                    SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.LINEAR,
                    position.x, position.y, position.z, false);
            this.sound = GROUND_ROCKET_SAMPLE;
        }

        @Override
        public WeighedSoundEvents resolve(SoundManager soundManager) {
            this.sound = GROUND_ROCKET_SAMPLE;
            return GROUND_ROCKET_EVENT;
        }
    }

    /** 配置了相机相对播放时，复用本体载具声音的跟随定位。 */
    private static final class GroundRocketVehicleSound extends VehicleSound {

        private GroundRocketVehicleSound(AbstractVehicle vehicle, Vec3 position, float distance) {
            super(SoundEvent.createVariableRangeEvent(GROUND_ROCKET_EVENT_ID),
                    position.subtract(vehicle.position()), 1.0F, distance, 1.0F,
                    false, 0, false, false, vehicle.getId());
            this.sound = GROUND_ROCKET_SAMPLE;
        }

        @Override
        public WeighedSoundEvents resolve(SoundManager soundManager) {
            this.sound = GROUND_ROCKET_SAMPLE;
            return GROUND_ROCKET_EVENT;
        }
    }
}
