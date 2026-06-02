package org.ywzj.rvp.client.state;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

public class RvpClientGPSState {

    private static ResourceLocation dimension;
    private static Vec3 pos;

    public static void set(ResourceLocation dim, Vec3 p) {
        dimension = dim;
        pos = p;
    }

    public static void clear() {
        dimension = null;
        pos = null;
    }

    public static boolean isActive() {
        return dimension != null && pos != null;
    }

    public static ResourceLocation getDimension() {
        return dimension;
    }

    public static Vec3 getPos() {
        return pos;
    }
}
