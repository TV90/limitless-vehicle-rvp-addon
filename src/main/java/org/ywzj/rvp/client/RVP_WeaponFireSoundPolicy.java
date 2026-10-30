package org.ywzj.rvp.client;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** 武器声音优先级：载具包自定义声音优先，其次使用可用的 RVP 默认声音。 */
final class RVP_WeaponFireSoundPolicy {

    /** 本体声音所属命名空间；其他命名空间均视为载具包明确指定的声音。 */
    private static final String VEHICLE_NAMESPACE = "ywzj_vehicle";

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
     * @param defaultSoundAvailable RVP 默认火箭音频是否存在
     * @param configuredSounds 武器表现文件配置的全部声音 ID；缺少表现文件时可为空
     * @return 应使用 RVP 默认火箭发射声时为 true
     */
    static boolean useGroundRocketSound(boolean groundVehicle, boolean rocketWeapon,
                                        boolean defaultSoundAvailable,
                                        @Nullable Iterable<ResourceLocation> configuredSounds) {
        if (!groundVehicle || !rocketWeapon || !defaultSoundAvailable) {
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
}
