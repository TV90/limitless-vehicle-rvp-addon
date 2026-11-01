package org.ywzj.rvp.client;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** 武器声音优先级：载具包自定义声音优先，其次使用可用的 RVP 默认声音。 */
final class RVP_WeaponFireSoundPolicy {

    /** 本体声音所属命名空间；其他命名空间均视为载具包明确指定的声音。 */
    private static final String VEHICLE_NAMESPACE = "ywzj_vehicle";
    /** 使用大口径火箭发射声的最低火箭口径，单位毫米；边界值 220 mm 也算大口径。 */
    private static final float LARGE_ROCKET_CALIBER_MM = 220.0F;

    private RVP_WeaponFireSoundPolicy() {
    }

    /**
     * 判断是否由 RVP 接管地面火箭的发射声。
     *
     * <p>只要武器的任意声音使用非本体命名空间，就保留整套载具包声音，
     * 包括观瞄声、装填声等；默认音频缺失时也保持原播放路径。</p>
     *
     * @param groundVehicle 是否为轮式或履带式地面载具
     * @param rocketWeapon 是否为火箭类型武器
     * @param caliberMm 已按根级 {@code caliber} → {@code effects_data.caliber} 解析出的口径，单位毫米
     * @param defaultSoundAvailable RVP 默认火箭音频是否存在
     * @param configuredSounds 武器表现文件配置的全部声音 ID；缺少表现文件时可为空
     * @return 应使用 RVP 默认火箭发射声时为 true
     */
    static boolean useGroundRocketSound(boolean groundVehicle, boolean rocketWeapon,
                                        @Nullable Float caliberMm,
                                        boolean defaultSoundAvailable,
                                        @Nullable Iterable<ResourceLocation> configuredSounds) {
        if (!groundVehicle || !rocketWeapon || !isLargeRocketCaliber(caliberMm)
                || !defaultSoundAvailable) {
            return false;
        }
        // 调用统一优先级判断，使火箭与新增导弹默认声遵守同一套自定义声音保护规则。
        return allowsDefaultSound(defaultSoundAvailable, configuredSounds);
    }

    /** 任意非本体声音均保护整套武器声音；缺少可用默认音频时必须让出原播放链。 */
    static boolean allowsDefaultSound(boolean defaultSoundAvailable,
                                      @Nullable Iterable<ResourceLocation> configuredSounds) {
        if (!defaultSoundAvailable) {
            return false;
        }
        if (configuredSounds != null) {
            for (ResourceLocation sound : configuredSounds) {
                if (sound != null && !VEHICLE_NAMESPACE.equals(sound.getNamespace())) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 根级口径有值时优先使用；根级为空时才回退到 effects_data.caliber。 */
    @Nullable
    static Float resolveRocketCaliber(@Nullable Float rootCaliberMm, @Nullable Float effectsCaliberMm) {
        return rootCaliberMm != null ? rootCaliberMm : effectsCaliberMm;
    }

    /** 判断根级武器口径是否为有效的 220 mm 及以上，避免缺省值或 NaN 意外触发大口径声音。 */
    static boolean isLargeRocketCaliber(@Nullable Float caliberMm) {
        return caliberMm != null && Float.isFinite(caliberMm)
                && caliberMm >= LARGE_ROCKET_CALIBER_MM;
    }
}
