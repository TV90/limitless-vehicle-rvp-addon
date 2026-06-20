package org.ywzj.rvp.client.lead;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

public record RVP_LeadSolution(
        Entity target,
        Vec3 targetWorldPos,
        Vec3 leadWorldPos,
        double timeToImpact,
        double missDistance
) {
}
