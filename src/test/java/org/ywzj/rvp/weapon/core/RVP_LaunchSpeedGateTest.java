package org.ywzj.rvp.weapon.core;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.weapon.data.RVP_Range;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证载具发射速度门：区间写法、单边/并集边界与 JSON 字段绑定。 */
class RVP_LaunchSpeedGateTest {

    @Test
    void unconfiguredRangeAlwaysAllows() {
        assertTrue(RVP_ProjectileWeapon.isLaunchSpeedAllowed(null, 0.0));
    }

    @Test
    void singleSidedRangesRespectClosedBoundaries() {
        // "[[inf,0.7]]"：低速允许，超过 0.7 格/刻拒射。
        assertTrue(RVP_ProjectileWeapon.isLaunchSpeedAllowed(lowSpeedOnly(), 0.0));
        assertTrue(RVP_ProjectileWeapon.isLaunchSpeedAllowed(lowSpeedOnly(), 0.7));
        assertFalse(RVP_ProjectileWeapon.isLaunchSpeedAllowed(lowSpeedOnly(), 0.71));

        // "[[0.4167,inf]]"：固定翼导弹现用配置（30 km/h），边界处允许、低于边界拒射。
        assertFalse(RVP_ProjectileWeapon.isLaunchSpeedAllowed(thirtyKphAndUp(), 0.4166));
        assertTrue(RVP_ProjectileWeapon.isLaunchSpeedAllowed(thirtyKphAndUp(), 0.4167));
        assertTrue(RVP_ProjectileWeapon.isLaunchSpeedAllowed(thirtyKphAndUp(), 12.5));
    }

    @Test
    void unionRangeAllowsBothEndsAndRejectsMiddle() {
        // "[[inf,0.7],[4.2,inf]]"：低速与高速均可，中间 0.7~4.2 区间拒射。
        RVP_Range<Float> bothEnds = RVP_Range.of(
                RVP_Range.interval(null, 0.7f),
                RVP_Range.interval(4.2f, null));
        assertTrue(RVP_ProjectileWeapon.isLaunchSpeedAllowed(bothEnds, 0.3));
        assertTrue(RVP_ProjectileWeapon.isLaunchSpeedAllowed(bothEnds, 0.7));
        assertFalse(RVP_ProjectileWeapon.isLaunchSpeedAllowed(bothEnds, 2.0));
        assertFalse(RVP_ProjectileWeapon.isLaunchSpeedAllowed(bothEnds, 4.1));
        assertTrue(RVP_ProjectileWeapon.isLaunchSpeedAllowed(bothEnds, 4.2));
        assertTrue(RVP_ProjectileWeapon.isLaunchSpeedAllowed(bothEnds, 6.0));
    }

    @Test
    void jsonStringRangeSyntaxParsesWithInfinity() {
        Gson gson = new Gson();
        RVP_Range<Float> parsed = gson.fromJson(
                "\"[[0.4167,inf]]\"", new TypeToken<RVP_Range<Float>>() {}.getType());
        assertFalse(parsed.contains(0.4166f));
        assertTrue(parsed.contains(0.4167f));
        assertTrue(parsed.contains(4.5f));
    }

    @Test
    void jsonKeyIsBoundToWeaponDataField() throws ReflectiveOperationException {
        Field field = RVP_WeaponData.class.getDeclaredField("launchSpeedRange");
        SerializedName annotation = field.getAnnotation(SerializedName.class);
        assertEquals("launch_speed_range", annotation.value());
        assertNull(new RVP_WeaponData().getLaunchSpeedRange());
    }

    private static RVP_Range<Float> lowSpeedOnly() {
        return RVP_Range.of(RVP_Range.interval(null, 0.7f));
    }

    private static RVP_Range<Float> thirtyKphAndUp() {
        return RVP_Range.of(RVP_Range.interval(0.4167f, null));
    }
}
