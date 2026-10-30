package org.ywzj.rvp.weapon.sound;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 小口径阈值、爆炸过滤和高速飞行段划空判定。 */
class RVP_SmallCaliberSoundPolicyTest {

    @Test
    void caliberMustBePositiveAndStrictlyLessThan45Millimeters() {
        // 调用小口径规则，覆盖 45 毫米边界和无效数据。
        assertTrue(RVP_SmallCaliberSoundPolicy.isSmallCaliber(7.62F));
        assertTrue(RVP_SmallCaliberSoundPolicy.isSmallCaliber(44.99F));
        assertFalse(RVP_SmallCaliberSoundPolicy.isSmallCaliber(45.0F));
        assertFalse(RVP_SmallCaliberSoundPolicy.isSmallCaliber(120.0F));
        assertFalse(RVP_SmallCaliberSoundPolicy.isSmallCaliber(0.0F));
        assertFalse(RVP_SmallCaliberSoundPolicy.isSmallCaliber(Float.NaN));
    }

    @Test
    void impactSoundIsSuppressedOnlyForEnabledExplosionAtLeastOneBlock() {
        // 调用方块命中规则，验证 explode 和 radius 必须同时满足屏蔽条件。
        assertTrue(RVP_SmallCaliberSoundPolicy.allowsBlockImpact(30.0F, false, 5.0F));
        assertTrue(RVP_SmallCaliberSoundPolicy.allowsBlockImpact(30.0F, true, 0.99F));
        assertFalse(RVP_SmallCaliberSoundPolicy.allowsBlockImpact(30.0F, true, 1.0F));
        assertFalse(RVP_SmallCaliberSoundPolicy.allowsBlockImpact(30.0F, true, 3.0F));
        assertFalse(RVP_SmallCaliberSoundPolicy.allowsBlockImpact(45.0F, false, 0.0F));
    }

    @Test
    void fastBulletUsesTheWholeSegmentAndNotJustItsEndpoints() {
        // 调用飞行段投影：两端都远离玩家，中间经过玩家附近时仍应返回最近点。
        Vec3 position = RVP_SmallCaliberSoundPolicy.incomingPosition(
                new Vec3(-30.0D, 0.0D, 0.0D), new Vec3(30.0D, 0.0D, 0.0D),
                new Vec3(0.0D, 3.0D, 0.0D), 8.0D);
        assertEquals(new Vec3(0.0D, 0.0D, 0.0D), position);
        // 截止于墙前的实际飞行段，不能使墙后较远的玩家听到划空声。
        assertNull(RVP_SmallCaliberSoundPolicy.incomingPosition(
                new Vec3(-30.0D, 0.0D, 0.0D), new Vec3(-10.0D, 0.0D, 0.0D),
                new Vec3(0.0D, 0.0D, 0.0D), 8.0D));
    }

    @Test
    void incomingRequiresAValidApproachingSegmentWithinRange() {
        Vec3 origin = Vec3.ZERO;
        // 调用飞行段投影：已远离、零长度和非有限坐标均不触发。
        assertNull(RVP_SmallCaliberSoundPolicy.incomingPosition(origin, new Vec3(10, 0, 0),
                new Vec3(-2, 0, 0), 8));
        assertNull(RVP_SmallCaliberSoundPolicy.incomingPosition(origin, origin, origin, 8));
        assertNull(RVP_SmallCaliberSoundPolicy.incomingPosition(origin, new Vec3(Double.NaN, 0, 0), origin, 8));
        assertNull(RVP_SmallCaliberSoundPolicy.incomingPosition(origin, new Vec3(10, 0, 0),
                new Vec3(5, 9, 0), 8));
        // 已进入听者范围但尚未飞到最近点时，也应及时播放来袭声。
        assertEquals(new Vec3(4, 0, 0), RVP_SmallCaliberSoundPolicy.incomingPosition(
                origin, new Vec3(4, 0, 0), new Vec3(10, 0, 0), 8));
    }

    @Test
    void penetrationCannotTurnAnOvershotSegmentIntoBackwardFlight() {
        // 调用方向保护：越墙推进超过段末时，向后裁剪的线段不能产生虚假的划空声。
        assertTrue(RVP_SmallCaliberSoundPolicy.isForwardSegment(
                new Vec3(2, 0, 0), new Vec3(8, 0, 0), new Vec3(10, 0, 0)));
        assertFalse(RVP_SmallCaliberSoundPolicy.isForwardSegment(
                new Vec3(12, 0, 0), new Vec3(8, 0, 0), new Vec3(10, 0, 0)));
        assertFalse(RVP_SmallCaliberSoundPolicy.isForwardSegment(Vec3.ZERO, Vec3.ZERO, Vec3.ZERO));
    }
}
