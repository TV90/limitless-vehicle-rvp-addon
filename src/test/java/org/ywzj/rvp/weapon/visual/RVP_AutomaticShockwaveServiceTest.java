package org.ywzj.rvp.weapon.visual;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.weapon.visual.api.RVP_ShockwaveProfile;
import org.ywzj.rvp.weapon.visual.api.RVP_VisualEffectEvent;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/** 自动冲击波的门槛、附加效果隔离及重复发布判定。 */
class RVP_AutomaticShockwaveServiceTest {
    @Test
    void includesEightButRejectsSmallerAndInvalidRadii() {
        // 调用实际启用判断，覆盖紧邻门槛的两侧，避免把 >= 错写成 >。
        assertFalse(RVP_AutomaticShockwaveService.isEnabled(Math.nextDown(8.0F)));
        assertTrue(RVP_AutomaticShockwaveService.isEnabled(8.0F));
        assertTrue(RVP_AutomaticShockwaveService.isEnabled(Math.nextUp(8.0F)));
        assertTrue(RVP_AutomaticShockwaveService.isEnabled(64.0F));
        for (float radius : new float[] {0, -8, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertFalse(RVP_AutomaticShockwaveService.isEnabled(radius));
        }
    }

    @Test
    void vehicleDestructionPathAcceptsEveryNonNegativeFiniteRadius() {
        // 调用载具击毁专用判断，确认小于 8 格和 0 格不会被普通爆炸门槛拦截。
        assertTrue(RVP_AutomaticShockwaveService.isVehicleDestructionRadiusValid(0.0F));
        assertTrue(RVP_AutomaticShockwaveService.isVehicleDestructionRadiusValid(1.0F));
        assertTrue(RVP_AutomaticShockwaveService.isVehicleDestructionRadiusValid(Math.nextDown(8.0F)));
        assertTrue(RVP_AutomaticShockwaveService.isVehicleDestructionRadiusValid(8.0F));
        for (float radius : new float[] {-0.01F, -8.0F, Float.NaN,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertFalse(RVP_AutomaticShockwaveService.isVehicleDestructionRadiusValid(radius));
        }
    }

    @Test
    void supplementsEveryTerrainClassWithoutCloudsSoundFlashOrShake() {
        for (RVP_ShockwaveProfile.HitClass hitClass : RVP_ShockwaveProfile.HitClass.values()) {
            RVP_ShockwaveProfile profile = new RVP_ShockwaveProfile(hitClass, 64, 0.7F,
                    Collections.nCopies(8, 20.0F));
            // 调用正式事件构造器，验证只追加冲击前沿，且不丢失服务端地形分类。
            RVP_VisualEffectEvent event = RVP_AutomaticShockwaveService.createEvent(
                    dimension(), new Vec3(10, 64, 20), 8, profile, 42, 100);
            assertEquals(8, event.baseExplosionRadius());
            assertSame(profile, event.shockwaveProfile());
            assertEquals(0, event.density());
            assertFalse(event.sound());
            assertFalse(event.flash());
            assertFalse(event.shake());
            assertEquals("{}", event.canonicalPresetDataJson());
            assertTrue(RVP_AutomaticShockwaveService.providesShockwave(event));
        }
    }

    @Test
    void vehicleDestructionEventEnablesOnlyTheVehicleSoundProfile() {
        RVP_ShockwaveProfile profile = new RVP_ShockwaveProfile(RVP_ShockwaveProfile.HitClass.GROUND,
                64, 0.7F, Collections.nCopies(8, 20.0F));
        RVP_VisualEffectEvent event = RVP_AutomaticShockwaveService.createEvent(
                dimension(), new Vec3(10, 64, 20), 4, profile, 42, 100, true);
        assertTrue(event.sound());
        assertFalse(event.flash());
        assertFalse(event.shake());
        assertTrue(RVP_DefaultExplosionEventData.decodeVehicleSoundProfile(
                event.canonicalPresetDataJson()));
    }

    @Test
    void disabledOrUnrelatedConfiguredEffectsCannotSuppressAutomaticFronts() {
        RVP_ShockwaveProfile profile = new RVP_ShockwaveProfile(RVP_ShockwaveProfile.HitClass.GROUND,
                64, 0.7F, Collections.nCopies(8, 20.0F));
        // 调用事件判定，零时长、零尺寸、缺少轮廓和其它主特效都不能被当作已有完整冲击波。
        assertFalse(RVP_AutomaticShockwaveService.providesShockwave(event("wilson_mach_shockwave", 0, 220, profile)));
        assertFalse(RVP_AutomaticShockwaveService.providesShockwave(event("wilson_mach_shockwave", 1, 0, profile)));
        assertFalse(RVP_AutomaticShockwaveService.providesShockwave(event("wilson_mach_shockwave", 1, 220, null)));
        assertFalse(RVP_AutomaticShockwaveService.providesShockwave(event("thermobaric", 1, 220, profile)));
        assertTrue(RVP_AutomaticShockwaveService.providesShockwave(event("wilson_mach_shockwave", 1, -1, profile)));
    }

    /** 构造不同主特效事件，检查自动补充与已有配置间的边界。 */
    private static RVP_VisualEffectEvent event(String type, float scale, int duration, RVP_ShockwaveProfile profile) {
        return new RVP_VisualEffectEvent(ResourceLocation.fromNamespaceAndPath("rvp", type),
                ResourceLocation.fromNamespaceAndPath("rvp", "default"), "{}", dimension(), Vec3.ZERO,
                8, scale, 1, duration, 8192, 42, 100, true, true, true, false, profile);
    }

    /** 纯数据测试直接创建维度键，避免读取 Level 常量时启动整套 Minecraft 注册表。 */
    @SuppressWarnings("unchecked")
    private static ResourceKey<Level> dimension() {
        try {
            var constructor = ResourceKey.class.getDeclaredConstructor(ResourceLocation.class, ResourceLocation.class);
            constructor.setAccessible(true);
            return (ResourceKey<Level>) constructor.newInstance(
                    ResourceLocation.fromNamespaceAndPath("minecraft", "dimension"),
                    ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("无法创建测试维度键", exception);
        }
    }
}
