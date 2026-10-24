package org.ywzj.rvp.client.particle;

import net.minecraft.client.particle.ParticleRenderType;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证殉燃静态贴图集的目录、索引与共享渲染批次；不启动客户端世界或粒子引擎。 */
class RVP_WreckCookoffParticleTexturesTest {

    @Test
    void textureSetContainsAllImportedVariants() {
        // 16 张静态贴图均为独立变体；增删资源时应同步调整文件名表与本断言。
        assertEquals(16, RVP_WreckCookoffParticleTextures.count());
    }

    @Test
    void indexWrapsWithoutSelectingAnAnimationFrame() {
        // 调用索引规范化：负数与超范围输入只选择固定贴图变体，不按年龄计算帧。
        assertEquals(0, RVP_WreckCookoffParticleTextures.normalizeIndex(0));
        assertEquals(15, RVP_WreckCookoffParticleTextures.normalizeIndex(-1));
        assertEquals(0, RVP_WreckCookoffParticleTextures.normalizeIndex(16));
        assertEquals(2, RVP_WreckCookoffParticleTextures.normalizeIndex(18));
    }

    @Test
    void textureLocationPointsAtTheWreckCookoffParticleDirectory() {
        // 调用贴图定位入口：拖尾烟与炮口烟必须引用 ywzj_rvp 下同一目录的烟雾贴图。
        assertEquals("ywzj_rvp", RVP_WreckCookoffParticleTextures.location(0).getNamespace());
        assertEquals("textures/wreck_cookoff/particle/flame_somke_01.png",
                RVP_WreckCookoffParticleTextures.location(0).getPath());
        // 超范围索引回卷后仍指向同一张贴图。
        assertEquals(RVP_WreckCookoffParticleTextures.location(0),
                RVP_WreckCookoffParticleTextures.location(16));
    }

    @Test
    void renderTypeIsSharedPerTextureInsteadOfRecreated() {
        // 同一张贴图必须返回同一批次实例，否则每多一个复用粒子类就会多出一整套重复批次。
        assertSame(RVP_WreckCookoffParticleTextures.renderType(0),
                RVP_WreckCookoffParticleTextures.renderType(16));
        Set<ParticleRenderType> distinct =
                Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0; i < RVP_WreckCookoffParticleTextures.count(); i++) {
            distinct.add(RVP_WreckCookoffParticleTextures.renderType(i));
        }
        assertEquals(RVP_WreckCookoffParticleTextures.count(), distinct.size());
        assertTrue(distinct.size() > 0);
    }
}
