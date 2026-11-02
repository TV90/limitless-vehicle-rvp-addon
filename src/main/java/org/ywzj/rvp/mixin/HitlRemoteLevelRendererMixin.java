package org.ywzj.rvp.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.client.hitl.RVP_ClientHitlRemoteView;
import org.ywzj.rvp.client.hitl.RVP_HitlTerrainAccess;

/** 原版 setupRender 按玩家身体摆地形网格；HITL 会话在 HEAD 为真实镜头准备网格。 */
@Mixin(LevelRenderer.class)
public abstract class HitlRemoteLevelRendererMixin implements RVP_HitlTerrainAccess {
    /** 原版地形网格和配置视距。 */
    @Shadow private ViewArea viewArea;
    @Shadow private int lastViewDistance;
    /** 原版身体区块缓存，用于避免 setupRender 随后把远程网格搬回母车。 */
    @Shadow private int lastCameraChunkX;
    @Shadow private int lastCameraChunkY;
    @Shadow private int lastCameraChunkZ;
    /** 当前地形镜头位置及重建标志。 */
    @Shadow private double lastCameraX;
    @Shadow private double lastCameraY;
    @Shadow private double lastCameraZ;
    @Shadow private boolean needsFullRenderChunkUpdate;

    @Inject(method = "setupRender", at = @At("HEAD"))
    private void rvp$prepareRemoteTerrain(Camera camera, Frustum frustum, boolean captured, boolean spectator, CallbackInfo ci) {
        RVP_ClientHitlRemoteView.prepareTerrain((LevelRenderer) (Object) this, camera);
    }

    @Override public ViewArea rvp$viewArea() { return viewArea; }
    @Override public int rvp$viewDistance() { return lastViewDistance; }

    @Override
    public void rvp$positionTerrain(Vec3 lens, Vec3 body, boolean reposition) {
        lastCameraChunkX = SectionPos.posToSectionCoord(body.x);
        lastCameraChunkY = SectionPos.posToSectionCoord(body.y);
        lastCameraChunkZ = SectionPos.posToSectionCoord(body.z);
        if (reposition) {
            lastCameraX = lens.x;
            lastCameraY = lens.y;
            lastCameraZ = lens.z;
            viewArea.repositionCamera(lens.x, lens.z);
            needsFullRenderChunkUpdate = true;
        }
    }
}
