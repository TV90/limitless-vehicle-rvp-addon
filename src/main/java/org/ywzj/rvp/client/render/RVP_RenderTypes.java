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
}
