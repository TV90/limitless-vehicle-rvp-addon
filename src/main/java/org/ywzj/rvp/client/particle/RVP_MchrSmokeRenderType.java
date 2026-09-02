package org.ywzj.rvp.client.particle;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.ywzj.rvp.RVP_MOD;

/**
 * MCHR 爆炸烟渲染契约（供 {@link RVP_MchrSmokeParticle} 使用）：
 * 直接绑定 MCHR 原版 {@code textures/particles/smoke.png}（灰白底样 8×8 帧，本包复制于
 * {@code textures/boom/smoke.png}）——灰度底样 × 粒子 RGB tint（0.3~0.7 灰黄）才是 MCHR 的
 * 实际观感；1.20.1 图集粒子会丢掉这种"暗色乘算"效果，因此用独立 RenderType + 完整 UV。
 */
@OnlyIn(Dist.CLIENT)
public final class RVP_MchrSmokeRenderType {

    /** MCHR 烟雾贴图（复制自 MCHR textures/particles/smoke.png，512×64 = 8 帧横排 64×64）。 */
    public static final ResourceLocation TEXTURE =
            RVP_MOD.modLocation("textures/boom/smoke.png");

    /** 烟帧数（贴图横排 8 帧，64×64 每帧）。 */
    public static final int FRAME_COUNT = 8;

    /** 独立半透明渲染类型：绑定 MCHR 烟贴图、标准 alpha 混合（GL_SRC_ALPHA / GL_ONE_MINUS_SRC_ALPHA）。 */
    public static final ParticleRenderType RENDER_TYPE = new ParticleRenderType() {
        @Override
        public void begin(BufferBuilder builder, TextureManager textureManager) {
            RenderSystem.depthMask(true);
            RenderSystem.setShaderTexture(0, TEXTURE);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
        }

        @Override
        public void end(Tesselator tesselator) {
            tesselator.end();
        }

        @Override
        public String toString() {
            return "RVP_MCHR_SMOKE";
        }
    };

    private RVP_MchrSmokeRenderType() {
    }
}
