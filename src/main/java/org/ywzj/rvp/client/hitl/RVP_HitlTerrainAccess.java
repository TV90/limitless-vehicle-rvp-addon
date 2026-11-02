package org.ywzj.rvp.client.hitl;

import net.minecraft.client.renderer.ViewArea;
import net.minecraft.world.phys.Vec3;

/** 只调整原版地形视区位置，不改模型材质、剔除方向或透明排序。 */
public interface RVP_HitlTerrainAccess {
    ViewArea rvp$viewArea();
    int rvp$viewDistance();
    void rvp$positionTerrain(Vec3 lens, Vec3 body, boolean reposition);
}
