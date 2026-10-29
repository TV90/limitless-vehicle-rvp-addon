package org.ywzj.rvp.client;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 本体载具通用爆音抑制键的匹配范围、过期和一次性消费测试。 */
class RVP_ClientVehicleExplosionSoundTest {

    /** 用于测试爆心坐标严格匹配和一次性消费的固定位置。 */
    private static final Vec3 CENTER = new Vec3(10.0D, 64.0D, -3.0D);

    @AfterEach
    void clearPendingKeys() {
        RVP_ClientVehicleExplosionSound.clearPending();
    }

    @Test
    void consumesOnlyMatchingHostileNativeExplosionOnce() {
        RVP_ClientVehicleExplosionSound.registerPending(null, CENTER, 100L);

        assertFalse(RVP_ClientVehicleExplosionSound.consumePending(null,
                RVP_ClientVehicleExplosionSound.NATIVE_GENERIC_EXPLOSION,
                SoundSource.PLAYERS, CENTER, 100L));
        assertFalse(RVP_ClientVehicleExplosionSound.consumePending(null,
                ResourceLocation.fromNamespaceAndPath("minecraft", "entity.generic.explode"),
                SoundSource.HOSTILE, CENTER.add(0.02D, 0.0D, 0.0D), 100L));
        assertTrue(RVP_ClientVehicleExplosionSound.consumePending(null,
                RVP_ClientVehicleExplosionSound.NATIVE_GENERIC_EXPLOSION,
                SoundSource.HOSTILE, CENTER, 100L));
        assertFalse(RVP_ClientVehicleExplosionSound.consumePending(null,
                RVP_ClientVehicleExplosionSound.NATIVE_GENERIC_EXPLOSION,
                SoundSource.HOSTILE, CENTER, 100L));
    }

    @Test
    void expiredPendingKeyDoesNotSuppressLaterExplosion() {
        RVP_ClientVehicleExplosionSound.registerPending(null, CENTER, 100L);
        assertFalse(RVP_ClientVehicleExplosionSound.consumePending(null,
                RVP_ClientVehicleExplosionSound.NATIVE_GENERIC_EXPLOSION,
                SoundSource.HOSTILE, CENTER, 2101L));
    }
}
