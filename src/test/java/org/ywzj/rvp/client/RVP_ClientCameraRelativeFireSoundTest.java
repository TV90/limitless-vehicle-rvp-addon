package org.ywzj.rvp.client;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证长冷发射延时、移动炮口和一次性消费，防止导弹替换声与延迟原声叠加。 */
class RVP_ClientCameraRelativeFireSoundTest {

    /** 本体实际播放的导弹发射声 ID。 */
    private static final ResourceLocation NATIVE_FIRE =
            ResourceLocation.fromNamespaceAndPath("ywzj_vehicle", "missile_launch");

    @BeforeEach
    void clearExpiredEntries() {
        // 调用生产过期清理，避免静态一次性队列在测试之间残留。
        RVP_ClientCameraRelativeFireSound.consumePending(NATIVE_FIRE, Vec3.ZERO, Long.MAX_VALUE);
    }

    @Test
    void delayedNativeFireWaitsBeyondTwoSecondsAndFollowsTheMovedMuzzle() {
        AtomicReference<Vec3> muzzle = new AtomicReference<>(new Vec3(0, 64, 0));
        // 调用生产登记入口，模拟持续 5 秒的冷发射，本体尚未播放时不能吞掉其他声音。
        RVP_ClientCameraRelativeFireSound.registerPending(NATIVE_FIRE, muzzle::get, 5000L, 1000L);
        assertFalse(RVP_ClientCameraRelativeFireSound.consumePending(NATIVE_FIRE, muzzle.get(), 1100L));
        assertFalse(RVP_ClientCameraRelativeFireSound.consumePending(NATIVE_FIRE, muzzle.get(), 3500L));

        muzzle.set(new Vec3(20, 64, 0));
        assertFalse(RVP_ClientCameraRelativeFireSound.consumePending(NATIVE_FIRE, new Vec3(0, 64, 0), 6000L));
        assertTrue(RVP_ClientCameraRelativeFireSound.consumePending(NATIVE_FIRE, muzzle.get(), 6000L));
        assertFalse(RVP_ClientCameraRelativeFireSound.consumePending(NATIVE_FIRE, muzzle.get(), 6001L));
    }

    @Test
    void normalFireStillMatchesImmediatelyAndUnmatchedDelayedKeysExpire() {
        Vec3 position = new Vec3(1, 2, 3);
        // 调用共用生产入口，普通发射的声音 ID、位置容差和两秒过期行为保持不变。
        RVP_ClientCameraRelativeFireSound.registerPending(NATIVE_FIRE, () -> position, 0L, 1000L);
        assertFalse(RVP_ClientCameraRelativeFireSound.consumePending(NATIVE_FIRE, new Vec3(2, 2, 3), 1000L));
        assertTrue(RVP_ClientCameraRelativeFireSound.consumePending(NATIVE_FIRE, position, 1000L));

        RVP_ClientCameraRelativeFireSound.registerPending(NATIVE_FIRE, () -> position, 5000L, 1000L);
        assertFalse(RVP_ClientCameraRelativeFireSound.consumePending(NATIVE_FIRE, position, 8000L));
    }
}
