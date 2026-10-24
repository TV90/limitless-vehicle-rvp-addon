package org.ywzj.rvp.client.particle;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.ywzj.rvp.RVP_MOD;

/**
 * 殉燃静态贴图集（{@code textures/wreck_cookoff/particle/}）的统一加载入口。
 *
 * <p>这套 128×128 的白色 Alpha 轮廓贴图最初只由车顶殉燃火柱
 * （{@link RVP_WreckFlameParticle}）加载；炮口灰烟、热诱弹拖尾烟也要复用同一批资源，
 * 故把「目录 + 文件名表 + 索引规范化 + 每张贴图的渲染批次」抽到本类：任何粒子只需申请
 * 一个索引即可复用，不再各自复制资源清单，也不会因为多一个粒子类而多出一批重复批次。</p>
 *
 * <p><b>渲染批次按贴图静态缓存</b>：同一张图永远返回同一个 {@link ParticleRenderType} 实例，
 * 不同粒子类（火柱 / 炮口烟 / 热诱弹拖尾烟）因此共用同一批次，{@code ParticleEngine} 的
 * 批次数量不随复用方增加。批次语义沿用火柱原实现：深度只测不写（半透明烟写深度会剔穿
 * 后画的载具切片与其它烟团）、普通 Alpha 混合、整张图直接映射面片。</p>
 *
 * <p>本类不含任何逐帧状态，类初始化只计算资源路径与渲染类型对象，不触碰 GL。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class RVP_WreckCookoffParticleTextures {

    /** 贴图目录（相对 {@code assets/ywzj_rvp}）。 */
    private static final String DIRECTORY = "textures/wreck_cookoff/particle/";

    /**
     * 全部静态贴图文件名；数组下标即贴图索引。
     *
     * <p>文件名里的 {@code somke} 是资产既有拼写，改名会让已导入的贴图失效，故保持原样。</p>
     */
    private static final String[] FILE_NAMES = {
            "flame_somke_01.png",
            "flame_somke_02.png",
            "flame_somke_03.png",
            "flame_somke_04.png",
            "flame_somke_05.png",
            "flame_somke_06.png",
            "flame_somke_07.png",
            "flame_somke_08.png",
            "flame_somke_09.png",
            "flame_somke_010.png",
            "flame_somke_011.png",
            "flame_somke_012.png",
            "flame_somke_013.png",
            "flame_somke_014.png",
            "flame_somke_015.png",
            "flame_somke_016.png",
    };

    /** 每张贴图对应的独立渲染批次，下标与 {@link #FILE_NAMES} 一致。 */
    private static final ParticleRenderType[] RENDER_TYPES = createRenderTypes();

    private RVP_WreckCookoffParticleTextures() {
    }

    /** 返回静态贴图数量，供各粒子类与单元测试共享边界。 */
    public static int count() {
        return FILE_NAMES.length;
    }

    /** 把任意输入索引规范化到现有静态贴图范围（负数与超范围输入循环回卷）。 */
    public static int normalizeIndex(int textureIndex) {
        return Math.floorMod(textureIndex, FILE_NAMES.length);
    }

    /** 返回指定索引对应的贴图路径；索引自动规范化。 */
    public static ResourceLocation location(int textureIndex) {
        return RVP_MOD.modLocation(DIRECTORY + FILE_NAMES[normalizeIndex(textureIndex)]);
    }

    /** 返回指定索引对应的渲染批次；索引自动规范化，且同一张图始终返回同一实例。 */
    public static ParticleRenderType renderType(int textureIndex) {
        return RENDER_TYPES[normalizeIndex(textureIndex)];
    }

    /** 用客户端随机源取一个贴图索引，供"每枚粒子固定一张图"的效果直接调用。 */
    public static int randomIndex(RandomSource random) {
        return random.nextInt(FILE_NAMES.length);
    }

    /** 创建全部静态贴图的渲染类型（只在类初始化时执行一次）。 */
    private static ParticleRenderType[] createRenderTypes() {
        ParticleRenderType[] types = new ParticleRenderType[FILE_NAMES.length];
        for (int i = 0; i < FILE_NAMES.length; i++) {
            types[i] = createRenderType(location(i));
        }
        return types;
    }

    /** 创建单张静态贴图的半透明渲染批次：深度只测不写，整张图映射面片。 */
    private static ParticleRenderType createRenderType(ResourceLocation texture) {
        return new ParticleRenderType() {
            @Override
            public void begin(BufferBuilder builder, TextureManager textureManager) {
                RenderSystem.depthMask(false);
                RenderSystem.setShaderTexture(0, texture);
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
            }

            @Override
            public void end(Tesselator tesselator) {
                tesselator.end();
                RenderSystem.depthMask(true);
            }

            @Override
            public String toString() {
                return "RVP_WRECK_COOKOFF_TEXTURE_" + texture;
            }
        };
    }
}
