package org.ywzj.rvp.weapon.gps;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class GPSTargetManager {

    private static final Map<UUID, GPSTarget> TARGETS = new ConcurrentHashMap<>();

    public static void clear(ServerPlayer player) {
        TARGETS.remove(player.getUUID());
    }

    public static void set(ServerPlayer player, ResourceLocation dimension, Vec3 pos) {
        TARGETS.put(player.getUUID(), new GPSTarget(dimension, pos));
    }

    public static GPSTarget get(Entity entity) {
        return TARGETS.get(entity.getUUID());
    }
}
