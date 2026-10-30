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
import static org.junit.jupiter.api.Assertions.assertNull;
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
    /** 大口径边界值，单位毫米。 */
    private static final Float LARGE_CALIBER = 220.0F;

    @Test
    void groundRocketUsesDefaultWhenDisplayIsMissingOrOnlyUsesNativeSounds() {
        // 调用声音策略：缺少表现文件和只使用本体声音均应触发默认火箭发射声。
        assertTrue(RVP_WeaponFireSoundPolicy.useGroundRocketSound(true, true, LARGE_CALIBER,
                true, null));
        assertTrue(RVP_WeaponFireSoundPolicy.useGroundRocketSound(true, true, LARGE_CALIBER,
                true, List.of()));
        assertTrue(RVP_WeaponFireSoundPolicy.useGroundRocketSound(true, true, LARGE_CALIBER,
                true, List.of(NATIVE_FIRE, NATIVE_RELOAD)));
    }

    @Test
    void largeRocketSoundRequiresAtLeast220Millimeters() {
        // 调用口径门槛判断：220 mm 是包含边界，219.9 mm、缺失和 NaN 均不能使用大口径声。
        assertTrue(RVP_WeaponFireSoundPolicy.isLargeRocketCaliber(220.0F));
        assertTrue(RVP_WeaponFireSoundPolicy.isLargeRocketCaliber(300.0F));
        assertFalse(RVP_WeaponFireSoundPolicy.isLargeRocketCaliber(219.9F));
        assertFalse(RVP_WeaponFireSoundPolicy.isLargeRocketCaliber(null));
        assertFalse(RVP_WeaponFireSoundPolicy.isLargeRocketCaliber(Float.NaN));

        assertFalse(RVP_WeaponFireSoundPolicy.useGroundRocketSound(true, true, 219.9F,
                true, List.of()));
        assertTrue(RVP_WeaponFireSoundPolicy.useGroundRocketSound(true, true, 220.0F,
                true, List.of()));
    }

    @Test
    void rootCaliberTakesPrecedenceAndEffectsCaliberIsTheFallback() {
        // 调用口径回退策略：根级有值时优先，根级为空才使用 effects_data.caliber，两者为空则无口径。
        assertEquals(300.0F, RVP_WeaponFireSoundPolicy.resolveRocketCaliber(300.0F, 220.0F));
        assertEquals(220.0F, RVP_WeaponFireSoundPolicy.resolveRocketCaliber(null, 220.0F));
        assertNull(RVP_WeaponFireSoundPolicy.resolveRocketCaliber(null, null));
    }

    @Test
    void anyNonNativeSoundProtectsTheEntireWeaponSoundConfiguration() {
        // 调用声音策略：即使非本体声音不是 fire，也应保留武器原有的整套声音。
        assertFalse(RVP_WeaponFireSoundPolicy.useGroundRocketSound(true, true, LARGE_CALIBER, true,
                List.of(NATIVE_FIRE, CUSTOM_SOUND)));
        assertFalse(RVP_WeaponFireSoundPolicy.useGroundRocketSound(true, true, LARGE_CALIBER, true,
                List.of(ResourceLocation.fromNamespaceAndPath("minecraft", "entity.generic.explode"))));
    }

    @Test
    void unsupportedWeaponsVehiclesAndMissingSampleKeepTheirExistingSound() {
        // 调用声音策略：空中或水下载具、非火箭和缺失音频均回退原有播放链。
        assertFalse(RVP_WeaponFireSoundPolicy.useGroundRocketSound(false, true, LARGE_CALIBER,
                true, List.of(NATIVE_FIRE)));
        assertFalse(RVP_WeaponFireSoundPolicy.useGroundRocketSound(true, false, LARGE_CALIBER,
                true, List.of(NATIVE_FIRE)));
        assertFalse(RVP_WeaponFireSoundPolicy.useGroundRocketSound(true, true, LARGE_CALIBER,
                false, List.of(NATIVE_FIRE)));
        assertFalse(RVP_WeaponFireSoundPolicy.useGroundRocketSound(true, true, null,
                true, List.of(NATIVE_FIRE)));
    }

    @Test
    void defaultRocketSoundResolvesTheExistingOggWithoutSoundJson() {
        // 调用声音管理器构造定位声音，模拟声音引擎解析并检查实际资源路径。
        var sound = RVP_ClientWeaponFireSound.createGroundRocketPositionSound(new Vec3(12.0D, 64.0D, -4.0D));
        var event = sound.resolve(null);
        assertNotNull(event);
        assertEquals(1, event.getWeight());
        assertEquals(ResourceLocation.fromNamespaceAndPath("ywzj_rvp",
                "sounds/default/rocket_launch/large/large_rocket_launch.ogg"), sound.getSound().getPath());
        assertEquals(SoundSource.PLAYERS, sound.getSource());
        assertEquals(4.0F, sound.getVolume());
        assertEquals(12.0D, sound.getX());
        assertEquals(64.0D, sound.getY());
        assertEquals(-4.0D, sound.getZ());
        assertTrue(Files.isRegularFile(Path.of("src/main/resources/assets/ywzj_rvp",
                sound.getSound().getPath().getPath())));
    }
}
