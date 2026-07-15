package org.ywzj.rvp.radar;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.network.S2CHbmMissileSnapshot;

public interface RVP_HbmRadarContact {
    @Nullable
    Vec3 ywzj_rvp$getRadarTargetPos();

    @Nullable
    S2CHbmMissileSnapshot.Affiliation ywzj_rvp$getRadarAffiliation();
}
