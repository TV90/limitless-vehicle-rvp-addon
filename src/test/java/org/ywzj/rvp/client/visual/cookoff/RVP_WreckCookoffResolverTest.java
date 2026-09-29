package org.ywzj.rvp.client.visual.cookoff;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证舱盖火柱只选每个舱盖的中心采样点。 */
class RVP_WreckCookoffResolverTest {

    @Test
    void onlyCenterHatchSampleRepresentsOneColumn() {
        RVP_WreckCookoffResolver.Anchor left = anchor(RVP_WreckCookoffResolver.Kind.HATCH, 0);
        RVP_WreckCookoffResolver.Anchor center = anchor(RVP_WreckCookoffResolver.Kind.HATCH, 1);
        RVP_WreckCookoffResolver.Anchor right = anchor(RVP_WreckCookoffResolver.Kind.HATCH, 2);
        RVP_WreckCookoffResolver.Anchor seam = anchor(RVP_WreckCookoffResolver.Kind.SEAM, 1);

        assertFalse(RVP_WreckCookoffResolver.isHatchColumnAnchor(left));
        assertTrue(RVP_WreckCookoffResolver.isHatchColumnAnchor(center));
        assertFalse(RVP_WreckCookoffResolver.isHatchColumnAnchor(right));
        assertFalse(RVP_WreckCookoffResolver.isHatchColumnAnchor(seam));
    }

    /** 构造只用于采样点分类断言的最小舱盖锚点。 */
    private static RVP_WreckCookoffResolver.Anchor anchor(RVP_WreckCookoffResolver.Kind kind,
                                                           int sampleIndex) {
        return new RVP_WreckCookoffResolver.Anchor(kind, null, Vec3.ZERO, Vec3.ZERO,
                null, sampleIndex, 0.5, null);
    }
}
