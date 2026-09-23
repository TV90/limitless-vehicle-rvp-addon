package org.ywzj.rvp.client.state;

import net.minecraft.world.phys.Vec3;

public class RVP_AimAssistState {
    /** 当前平滑后的世界方向单位向量。 */
    public Vec3 currDir = Vec3.ZERO;

    /** 当前平滑后的瞄准距离，单位为格。 */
    public double currDistance;

    /** 最后一次更新该状态的载具Tick。 */
    public int lastTick;

    /** 是否已经获得可用于插值的初始方向。 */
    public boolean initialized;
}
