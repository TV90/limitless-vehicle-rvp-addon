package org.ywzj.rvp.client;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证武器自定义声音、RVP 默认声音和本体回退之间的优先级。 */
class RVP_WeaponFireSoundPolicyTest {

    /** 本体默认发射声。 */
    private static final ResourceLocation NATIVE_FIRE =
            ResourceLocation.fromNamespaceAndPath("ywzj_vehicle", "missile_launch");
    /** 本体默认装填声。 */
    private static final ResourceLocation NATIVE_RELOAD =
            ResourceLocation.fromNamespaceAndPath("ywzj_vehicle", "common_reload");
    /** 载具包自行指定的声音。 */
    private static final ResourceLocation CUSTOM_SOUND =
            ResourceLocation.fromNamespaceAndPath("rvp", "custom_weapon_sound");

    @Test
    void groundRocketUsesDefaultWhenDisplayIsMissingOrOnlyUsesNativeSounds() {
        // 调用声音策略：缺少表现文件和只使用本体声音均应触发默认火箭发射声。
        assertTrue(RVP_WeaponFireSoundPolicy.useGroundRocketSound(true, true, true, null));
        assertTrue(RVP_WeaponFireSoundPolicy.useGroundRocketSound(true, true, true, List.of()));
        assertTrue(RVP_WeaponFireSoundPolicy.useGroundRocketSound(true, true, true,
                List.of(NATIVE_FIRE, NATIVE_RELOAD)));
    }

    @Test
    void anyNonNativeSoundProtectsTheEntireWeaponSoundConfiguration() {
        // 调用声音策略：即使非本体声音不是 fire，也应保留武器原有的整套声音。
        assertFalse(RVP_WeaponFireSoundPolicy.useGroundRocketSound(true, true, true,
                List.of(NATIVE_FIRE, CUSTOM_SOUND)));
        assertFalse(RVP_WeaponFireSoundPolicy.useGroundRocketSound(true, true, true,
                List.of(ResourceLocation.fromNamespaceAndPath("minecraft", "entity.generic.explode"))));
    }

    @Test
    void unsupportedWeaponsVehiclesAndMissingSampleKeepTheirExistingSound() {
        // 调用声音策略：空中或水下载具、非火箭和缺失音频均回退原有播放链。
        assertFalse(RVP_WeaponFireSoundPolicy.useGroundRocketSound(false, true, true, List.of(NATIVE_FIRE)));
        assertFalse(RVP_WeaponFireSoundPolicy.useGroundRocketSound(true, false, true, List.of(NATIVE_FIRE)));
        assertFalse(RVP_WeaponFireSoundPolicy.useGroundRocketSound(true, true, false, List.of(NATIVE_FIRE)));
    }

    @Test
    void defaultRocketSoundResolvesTheExistingOggWithoutSoundJson() {
        // 调用声音管理器构造定位声音，模拟声音引擎解析并检查实际资源路径。
        var sound = RVP_ClientWeaponFireSound.createGroundRocketPositionSound(new Vec3(12.0D, 64.0D, -4.0D));
        var event = sound.resolve(null);
        assertNotNull(event);
        assertEquals(1, event.getWeight());
        assertEquals(ResourceLocation.fromNamespaceAndPath("ywzj_rvp",
                "sounds/default/rocket_launch/large_rocket_launch.ogg"), sound.getSound().getPath());
        assertEquals(SoundSource.PLAYERS, sound.getSource());
        assertEquals(4.0F, sound.getVolume());
        assertEquals(12.0D, sound.getX());
        assertEquals(64.0D, sound.getY());
        assertEquals(-4.0D, sound.getZ());
        assertTrue(Files.isRegularFile(Path.of("src/main/resources/assets/ywzj_rvp",
                sound.getSound().getPath().getPath())));
    }
}
