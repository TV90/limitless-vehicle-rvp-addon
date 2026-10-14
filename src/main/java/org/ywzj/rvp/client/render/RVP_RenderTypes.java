package org.ywzj.rvp.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.Util;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Function;

public class RVP_RenderTypes extends RenderType {

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

    private static final Function<ResourceLocation, RenderType> CUBE_COCKPIT_TRANSLUCENT_CULLED = Util.memoize((location) -> {
        RenderStateShard.TextureStateShard shard = new RenderStateShard.TextureStateShard(location, false, false);
        RenderType.CompositeState state = RenderType.CompositeState.builder()
                .setTextureState(shard)
                .setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                .setLayeringState(VIEW_OFFSET_Z_LAYERING)
                .setCullState(CULL)
                .setOverlayState(OVERLAY)
                .setLightmapState(LIGHTMAP)
                .setWriteMaskState(COLOR_WRITE)
                .createCompositeState(true);
        return create(
                "ywzj_rvp:cube_cockpit_translucent_culled",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS,
                256,
                true,
                true,
                state
        );
    });

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

    private static final Function<ResourceLocation, RenderType> POLY_MESH_COCKPIT_TRANSLUCENT_CULLED = Util.memoize((location) -> {
        RenderStateShard.TextureStateShard shard = new RenderStateShard.TextureStateShard(location, false, false);
        RenderType.CompositeState state = RenderType.CompositeState.builder()
                .setTextureState(shard)
                .setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                .setLayeringState(VIEW_OFFSET_Z_LAYERING)
                .setCullState(CULL)
                .setOverlayState(OVERLAY)
                .setLightmapState(LIGHTMAP)
                .setWriteMaskState(COLOR_WRITE)
                .createCompositeState(true);
        return create(
                "ywzj_rvp:poly_mesh_cockpit_translucent_culled",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.TRIANGLES,
                256,
                true,
                true,
                state
        );
    });

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

    public static RenderType cubeCockpitTransparent(ResourceLocation texture) {
        return CUBE_COCKPIT_TRANSLUCENT_CULLED.apply(texture);
    }

    public static RenderType polyMeshCockpitTransparent(ResourceLocation texture) {
        return POLY_MESH_COCKPIT_TRANSLUCENT_CULLED.apply(texture);
    }

    public static RenderType texturedTranslucentNoDepthWrite(ResourceLocation texture) {
        return TEXTURED_TRANSLUCENT_NO_DEPTH_WRITE.apply(texture);
    }

    public static RenderType texturedAdditiveNoDepthWrite(ResourceLocation texture) {
        return TEXTURED_ADDITIVE_NO_DEPTH_WRITE.apply(texture);
    }

    public static RenderType cubeScreenOpaque(ResourceLocation texture) {
        return CUBE_SCREEN_OPAQUE.apply(texture);
    }

    public static RenderType polyMeshScreenOpaque(ResourceLocation texture) {
        return POLY_MESH_SCREEN_OPAQUE.apply(texture);
    }
}
