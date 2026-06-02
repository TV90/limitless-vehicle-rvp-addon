package org.ywzj.rvp.weapon.gps;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

public class GPSTarget {

    public final ResourceLocation dimension;
    public final Vec3 pos;

    public GPSTarget(ResourceLocation dimension, Vec3 pos) {
        this.dimension = dimension;
        this.pos = pos;
    }
}
