package org.ywzj.rvp.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.Util;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Function;

public class RVP_RenderTypes extends RenderType {

    /** 座舱材质按实例身份登记，供独立透明阶段识别；不把屏幕或尾焰误当成玻璃。 */
    private static final java.util.Map<RenderType, RenderType> COCKPIT_TYPES = new java.util.IdentityHashMap<>();

    public RVP_RenderTypes(String pName, VertexFormat pFormat, VertexFormat.Mode pMode, int pBufferSize, boolean pAffectsCrumbling, boolean pSortOnUpload, Runnable pSetupState, Runnable pClearState) {
        super(pName, pFormat, pMode, pBufferSize, pAffectsCrumbling, pSortOnUpload, pSetupState, pClearState);
    }

    private static final Function<ResourceLocation, RenderType> POLY_MESH_CUTOUT_CULLED = Util.memoize((location) -> {
        RenderStateShard.TextureStateShard shard = new RenderStateShard.TextureStateShard(location, false, false);
        RenderType.CompositeState state = RenderType.CompositeState.builder()
                .setTextureState(shard)
                .setShaderState(RENDERTYPE_ENTITY_CUTOUT_SHADER)
                .setTransparencyState(NO_TRANSPARENCY)
                .setCullState(CULL)
                .setOverlayState(OVERLAY)
                .setLightmapState(LIGHTMAP)
                .createCompositeState(true);
        return create(
                "ywzj_rvp:poly_mesh_cutout_culled",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.TRIANGLES,
                256,
                true,
                true,
                state
        );
    });

    private static final Function<ResourceLocation, RenderType> POLY_MESH_CUTOUT_NO_CULL = Util.memoize((location) -> {
        RenderStateShard.TextureStateShard shard = new RenderStateShard.TextureStateShard(location, false, false);
        RenderType.CompositeState state = RenderType.CompositeState.builder()
                .setTextureState(shard)
                .setShaderState(RENDERTYPE_ENTITY_CUTOUT_SHADER)
                .setTransparencyState(NO_TRANSPARENCY)
                .setCullState(NO_CULL)
                .setOverlayState(OVERLAY)
                .setLightmapState(LIGHTMAP)
                .createCompositeState(true);
        return create(
                "ywzj_rvp:poly_mesh_cutout_no_cull",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.TRIANGLES,
                256,
                true,
                true,
                state
        );
    });

    private static final Function<ResourceLocation, RenderType> POLY_MESH_TRANSLUCENT_CULLED = Util.memoize((location) -> {
        RenderStateShard.TextureStateShard shard = new RenderStateShard.TextureStateShard(location, false, false);
        RenderType.CompositeState state = RenderType.CompositeState.builder()
                .setTextureState(shard)
                .setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                .setCullState(CULL)
                .setOverlayState(OVERLAY)
                .setLightmapState(LIGHTMAP)
                .createCompositeState(true);
        return create(
                "ywzj_rvp:poly_mesh_translucent_culled",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.TRIANGLES,
                256,
                true,
                true,
                state
        );
    });

    /** 即时回退与延后合成共享纹理；只有实体提交后的合成材质写深度。 */
    private static final Function<ResourceLocation, RenderType> CUBE_COCKPIT_TRANSLUCENT = Util.memoize(
            texture -> registerCockpit(texture, VertexFormat.Mode.QUADS));

    private static final Function<ResourceLocation, RenderType> CUBE_CUTOUT_CULLED = Util.memoize((location) -> {
        RenderStateShard.TextureStateShard shard = new RenderStateShard.TextureStateShard(location, false, false);
        RenderType.CompositeState state = RenderType.CompositeState.builder()
                .setTextureState(shard)
                .setShaderState(RENDERTYPE_ENTITY_CUTOUT_SHADER)
                .setTransparencyState(NO_TRANSPARENCY)
                .setCullState(CULL)
                .setOverlayState(OVERLAY)
                .setLightmapState(LIGHTMAP)
                .createCompositeState(true);
        return create(
                "ywzj_rvp:cube_cutout_culled",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS,
                256,
                true,
                true,
                state
        );
    });

    private static final Function<ResourceLocation, RenderType> CUBE_TRANSLUCENT_CULLED = Util.memoize((location) -> {
        RenderStateShard.TextureStateShard shard = new RenderStateShard.TextureStateShard(location, false, false);
        RenderType.CompositeState state = RenderType.CompositeState.builder()
                .setTextureState(shard)
                .setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                .setCullState(CULL)
                .setOverlayState(OVERLAY)
                .setLightmapState(LIGHTMAP)
                .createCompositeState(true);
        return create(
                "ywzj_rvp:cube_translucent_culled",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS,
                256,
                true,
                true,
                state
        );
    });

    /** 薄面片和封闭舱盖均按本体透明材质双面绘制，不用模型绕序猜测可见面。 */
    private static final Function<ResourceLocation, RenderType> POLY_MESH_COCKPIT_TRANSLUCENT = Util.memoize(
            texture -> registerCockpit(texture, VertexFormat.Mode.TRIANGLES));

    /** 半透明四边形只写颜色，保留真实前景遮挡；用于粒子和不能充当遮挡物的发动机尾焰。 */
    private static final Function<ResourceLocation, RenderType> TEXTURED_TRANSLUCENT_NO_DEPTH_WRITE = Util.memoize((location) -> {
        RenderType.CompositeState state = RenderType.CompositeState.builder()
                .setTextureState(new RenderStateShard.TextureStateShard(location, false, false))
                .setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                .setDepthTestState(LEQUAL_DEPTH_TEST)
                .setCullState(NO_CULL)
                .setOverlayState(OVERLAY)
                .setLightmapState(LIGHTMAP)
                .setWriteMaskState(COLOR_WRITE)
                .createCompositeState(false);
        return create("ywzj_rvp:textured_translucent_no_depth_write_" + location.toString().replace(':', '_'),
                DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 1_048_576,
                false, true, state);
    });

    /** 半透明三角网格尾焰的同等通道；避免模型改用 poly_mesh 后再次向场景深度写入自身。 */
    private static final Function<ResourceLocation, RenderType> POLY_MESH_TRANSLUCENT_NO_DEPTH_WRITE = Util.memoize((location) -> {
        RenderType.CompositeState state = RenderType.CompositeState.builder()
                .setTextureState(new RenderStateShard.TextureStateShard(location, false, false))
                .setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                .setDepthTestState(LEQUAL_DEPTH_TEST)
                .setCullState(NO_CULL)
                .setOverlayState(OVERLAY)
                .setLightmapState(LIGHTMAP)
                .setWriteMaskState(COLOR_WRITE)
                .createCompositeState(false);
        return create("ywzj_rvp:poly_mesh_translucent_no_depth_write_" + location.toString().replace(':', '_'),
                DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.TRIANGLES, 256,
                false, true, state);
    });

    /** 三角网格的叠加自发光：与纹理相乘后直接加到已绘制像素上，用于玻片"亮起来"的观感。 */
    private static final Function<ResourceLocation, RenderType> POLY_MESH_ADDITIVE_NO_DEPTH_WRITE = Util.memoize((location) -> {
        RenderType.CompositeState state = RenderType.CompositeState.builder()
                .setTextureState(new RenderStateShard.TextureStateShard(location, false, false))
                .setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                .setTransparencyState(ADDITIVE_TRANSPARENCY)
                .setDepthTestState(LEQUAL_DEPTH_TEST)
                .setCullState(NO_CULL)
                .setOverlayState(OVERLAY)
                .setLightmapState(LIGHTMAP)
                .setWriteMaskState(COLOR_WRITE)
                .createCompositeState(false);
        return create("ywzj_rvp:poly_mesh_additive_no_depth_write_" + location.toString().replace(':', '_'),
                DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.TRIANGLES, 262_144,
                false, true, state);
    });

    private static final Function<ResourceLocation, RenderType> TEXTURED_ADDITIVE_NO_DEPTH_WRITE = Util.memoize((location) -> {
        RenderType.CompositeState state = RenderType.CompositeState.builder()
                .setTextureState(new RenderStateShard.TextureStateShard(location, false, false))
                .setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                .setTransparencyState(ADDITIVE_TRANSPARENCY)
                .setDepthTestState(LEQUAL_DEPTH_TEST)
                .setCullState(NO_CULL)
                .setOverlayState(OVERLAY)
                .setLightmapState(LIGHTMAP)
                .setWriteMaskState(COLOR_WRITE)
                .createCompositeState(false);
        return create("ywzj_rvp:textured_additive_no_depth_write_" + location.toString().replace(':', '_'),
                DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 262_144,
                false, true, state);
    });

    // 座舱屏骨专用"不透明+自发光"（2026-10-14 屏骨渲染定档）：半透明通道按片元深度排序，
    // 屏骨在座舱玻璃（同为半透明）正后方会互相吞面/闪烁，且透明片元写深度留空洞——
    // 屏骨改走 cutout 不透明通道：不参与半透明排序、深度写入正确；illum_ 骨在 SBM renderBone
    // 侧强制 MAX_LIGHT_TEXTURE 全亮；非乘载填 alpha=0 时片元直接 discard（连深度都不写），"整块不渲染"语义不变。
    private static final Function<ResourceLocation, RenderType> CUBE_SCREEN_OPAQUE = Util.memoize((location) -> {
        RenderStateShard.TextureStateShard shard = new RenderStateShard.TextureStateShard(location, false, false);
        RenderType.CompositeState state = RenderType.CompositeState.builder()
                .setTextureState(shard)
                .setShaderState(RENDERTYPE_ENTITY_CUTOUT_SHADER)
                .setTransparencyState(NO_TRANSPARENCY)
                .setCullState(NO_CULL)
                .setOverlayState(OVERLAY)
                .setLightmapState(LIGHTMAP)
                .createCompositeState(true);
        return create(
                "ywzj_rvp:cube_screen_opaque",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS,
                256,
                true,
                false,
                state
        );
    });

    private static final Function<ResourceLocation, RenderType> POLY_MESH_SCREEN_OPAQUE = Util.memoize((location) -> {
        RenderStateShard.TextureStateShard shard = new RenderStateShard.TextureStateShard(location, false, false);
        RenderType.CompositeState state = RenderType.CompositeState.builder()
                .setTextureState(shard)
                .setShaderState(RENDERTYPE_ENTITY_CUTOUT_SHADER)
                .setTransparencyState(NO_TRANSPARENCY)
                .setCullState(NO_CULL)
                .setOverlayState(OVERLAY)
                .setLightmapState(LIGHTMAP)
                .createCompositeState(true);
        return create(
                "ywzj_rvp:poly_mesh_screen_opaque",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.TRIANGLES,
                256,
                true,
                false,
                state
        );
    });

    public static RenderType polyMeshCutout(ResourceLocation texture) {
        return POLY_MESH_CUTOUT_CULLED.apply(texture);
    }

    public static RenderType cubeCutout(ResourceLocation texture) {
        return CUBE_CUTOUT_CULLED.apply(texture);
    }

    public static RenderType polyMeshCutoutNoCull(ResourceLocation texture) {
        return POLY_MESH_CUTOUT_NO_CULL.apply(texture);
    }

    public static RenderType polyMeshTransparent(ResourceLocation texture) {
        return POLY_MESH_TRANSLUCENT_CULLED.apply(texture);
    }

    public static RenderType cubeTransparent(ResourceLocation texture) {
        return CUBE_TRANSLUCENT_CULLED.apply(texture);
    }

    /** 同时建立回退/合成材质：回退不提前挡乘员，合成在全部实体之后恢复可靠深度。 */
    private static RenderType registerCockpit(ResourceLocation texture, VertexFormat.Mode mode) {
        RenderType direct = createCockpitType(texture, mode, false);
        RenderType composite = createCockpitType(texture, mode, true);
        COCKPIT_TYPES.put(direct, composite);
        return direct;
    }

    /** 与本体透明材质一样双面；深度写入只交给独立延后阶段使用。 */
    private static RenderType createCockpitType(ResourceLocation texture, VertexFormat.Mode mode, boolean writeDepth) {
        RenderType.CompositeState state = RenderType.CompositeState.builder()
                .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                .setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                .setDepthTestState(LEQUAL_DEPTH_TEST)
                .setCullState(NO_CULL)
                .setOverlayState(OVERLAY)
                .setLightmapState(LIGHTMAP)
                .setWriteMaskState(writeDepth ? COLOR_DEPTH_WRITE : COLOR_WRITE)
                .createCompositeState(true);
        return create("ywzj_rvp:cockpit_" + mode.name().toLowerCase(java.util.Locale.ROOT)
                        + (writeDepth ? "_composite" : "_direct"),
                DefaultVertexFormat.NEW_ENTITY, mode, 256, true, mode == VertexFormat.Mode.QUADS, state);
    }

    public static boolean isCockpitTransparent(RenderType type) {
        return COCKPIT_TYPES.containsKey(type);
    }

    /** 调用者必须已提交实体缓冲，不能把这个写深度材质用于实体中途的玻璃。 */
    public static RenderType cockpitComposite(RenderType type) {
        return COCKPIT_TYPES.getOrDefault(type, type);
    }

    public static RenderType cubeCockpitTransparent(ResourceLocation texture) {
        return CUBE_COCKPIT_TRANSLUCENT.apply(texture);
    }

    public static RenderType polyMeshCockpitTransparent(ResourceLocation texture) {
        return POLY_MESH_COCKPIT_TRANSLUCENT.apply(texture);
    }

    /** 获取四边形半透明通道；只写颜色，仍受已绘制的地形和实体深度约束。 */
    public static RenderType texturedTranslucentNoDepthWrite(ResourceLocation texture) {
        return TEXTURED_TRANSLUCENT_NO_DEPTH_WRITE.apply(texture);
    }

    /** 获取三角网格半透明通道，保持与四边形尾焰相同的遮挡和不写深度语义。 */
    public static RenderType polyMeshTranslucentNoDepthWrite(ResourceLocation texture) {
        return POLY_MESH_TRANSLUCENT_NO_DEPTH_WRITE.apply(texture);
    }

    public static RenderType texturedAdditiveNoDepthWrite(ResourceLocation texture) {
        return TEXTURED_ADDITIVE_NO_DEPTH_WRITE.apply(texture);
    }

    public static RenderType polyMeshAdditiveNoDepthWrite(ResourceLocation texture) {
        return POLY_MESH_ADDITIVE_NO_DEPTH_WRITE.apply(texture);
    }

    public static RenderType cubeScreenOpaque(ResourceLocation texture) {
        return CUBE_SCREEN_OPAQUE.apply(texture);
    }

    public static RenderType polyMeshScreenOpaque(ResourceLocation texture) {
        return POLY_MESH_SCREEN_OPAQUE.apply(texture);
    }
}
