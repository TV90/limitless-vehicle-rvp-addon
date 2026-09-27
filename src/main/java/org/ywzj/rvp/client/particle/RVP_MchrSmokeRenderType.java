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

    /** MCHR 烟雾贴图（复制自 MCHR textures/particles/smoke.png，实际 64×8 = 8 帧横排 8×8）。 */
    public static final ResourceLocation TEXTURE =
            RVP_MOD.modLocation("textures/boom/smoke.png");

    /** 彩蛋贴图：事件配置 {@code explosion_sound = rvp:114514} 时烟雾改绑此贴图（2026-09-22）。 */
    public static final ResourceLocation EGG_TEXTURE =
            RVP_MOD.modLocation("textures/boom/114514.png");

    /** 烟帧数（贴图横排 8 帧，8×8 每帧）。 */
    public static final int FRAME_COUNT = 8;

    /** 独立半透明渲染类型：绑定 MCHR 烟贴图、标准 alpha 混合（GL_SRC_ALPHA / GL_ONE_MINUS_SRC_ALPHA）。 */
    public static final ParticleRenderType RENDER_TYPE = forTexture(TEXTURE, true);

    /** 彩蛋渲染类型：绑定 114514.png（8 帧横排、分辨率不限——UV 归一化，512×64 或 64×8 均可）。 */
    public static final ParticleRenderType EGG_RENDER_TYPE = forTexture(EGG_TEXTURE, true);

    /**
     * 损坏冒烟专用渲染类型（2026-09-28）：与 {@link #RENDER_TYPE} 同贴图，但<b>深度只测不写</b>
     * （{@code depthMask(false)}）——对齐火箭尾焰（{@code RVP_RocketFlameParticle}）、核爆云、
     * 冲击波、MchrFlare 的半透明约定：半透明烟写深度会把后续绘制的粒子/载具切片挡死，多层
     * 叠加后透过烟雾看载具出现"实体被剔穿"（2026-09-15 实机同款问题）。MCHR 普通爆炸烟的
     * {@link #RENDER_TYPE}/{@link #EGG_RENDER_TYPE} 保持原状不受影响。
     */
    public static final ParticleRenderType DAMAGED_SMOKE_RENDER_TYPE = forTexture(TEXTURE, false);

    /** 按贴图生成独立半透明渲染类型实例（默认写深度，爆炸烟历史行为保持）。 */
    private static ParticleRenderType forTexture(ResourceLocation texture) {
        return forTexture(texture, true);
    }

    /** 按贴图生成独立半透明渲染类型实例（标准 alpha 混合 GL_SRC_ALPHA / GL_ONE_MINUS_SRC_ALPHA）。 */
    private static ParticleRenderType forTexture(ResourceLocation texture, boolean depthWrite) {
        return new ParticleRenderType() {
            @Override
            public void begin(BufferBuilder builder, TextureManager textureManager) {
                RenderSystem.depthMask(depthWrite);
                RenderSystem.setShaderTexture(0, texture);
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
    }

    private RVP_MchrSmokeRenderType() {
    }
}
