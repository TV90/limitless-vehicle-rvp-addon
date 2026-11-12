package org.ywzj.rvp.entity.projectile;

import com.google.gson.annotations.SerializedName;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证弹药侧冷发射豁免：弹种默认、JSON 覆盖与弹体接线。 */
class RVP_ColdLaunchExemptionTest {

    @BeforeAll
    static void bootstrap() {
        RVP_ProjectileSimulationBootstrap.initialize();
    }

    @Test
    void rocketsAndBombsDefaultToExemptWhileMissilesKeepStationColdLaunch() {
        assertTrue(weapon(RVP_EnumWeaponKind.ROCKET).isColdLaunchExempt());
        assertTrue(weapon(RVP_EnumWeaponKind.BOMB).isColdLaunchExempt());
        assertFalse(weapon(RVP_EnumWeaponKind.MISSILE).isColdLaunchExempt());
        assertFalse(weapon(RVP_EnumWeaponKind.MACHINEGUN).isColdLaunchExempt());
        assertFalse(weapon(RVP_EnumWeaponKind.TORPEDO).isColdLaunchExempt());
    }

    @Test
    void explicitFieldOverridesKindDefault() {
        RVP_WeaponData rocket = weapon(RVP_EnumWeaponKind.ROCKET);
        setField(rocket, "coldLaunchExempt", false);
        assertFalse(rocket.isColdLaunchExempt());

        RVP_WeaponData missile = weapon(RVP_EnumWeaponKind.MISSILE);
        setField(missile, "coldLaunchExempt", true);
        assertTrue(missile.isColdLaunchExempt());
    }

    @Test
    void jsonKeyIsBoundToWeaponDataField() throws ReflectiveOperationException {
        Field field = RVP_WeaponData.class.getDeclaredField("coldLaunchExempt");
        SerializedName annotation = field.getAnnotation(SerializedName.class);
        assertEquals("cold_launch_exempt", annotation.value());
    }

    @Test
    void exemptProjectileIgnoresStationColdLaunchConfiguration() {
        WeaponUnit unit = RVP_ProjectileSimulationBootstrap.environment(WeaponUnit.class);
        RVP_ProjectileSimulationBootstrap.write(WeaponUnit.class, unit, "coldLaunchTimeTick", 10);
        RVP_ProjectileSimulationBootstrap.write(WeaponUnit.class, unit, "coldLaunchVelocity", new Vec3(0, 2, 0));

        RVP_BulletEntity exempt = RVP_ProjectileSimulationBootstrap.environment(RVP_BulletEntity.class);
        exempt.initColdLaunch(unit, true);
        assertEquals(0, exempt.getColdLaunchTimeTick());

        RVP_BulletEntity normal = RVP_ProjectileSimulationBootstrap.environment(RVP_BulletEntity.class);
        normal.initColdLaunch(unit, false);
        assertEquals(10, normal.getColdLaunchTimeTick());
        assertEquals(new Vec3(0, 2, 0), normal.getColdLaunchVelocity());
    }

    private static RVP_WeaponData weapon(RVP_EnumWeaponKind kind) {
        RVP_WeaponData data = new RVP_WeaponData();
        data.setWeaponKind(kind);
        return data;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("字段已变化: " + name, e);
        }
    }
}
