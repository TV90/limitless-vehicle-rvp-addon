package org.ywzj.rvp.client.state;

import net.minecraft.world.phys.Vec3;

public class RVP_AimAssistState {
    public Vec3 currDir = Vec3.ZERO;
    public double currDistance;
    public int lastTick;
    public boolean initialized;
}
