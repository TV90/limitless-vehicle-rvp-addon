package org.ywzj.rvp.mixin.accessor;

import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.ywzj.vehicle.custom.part.data.PartUnitData;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeGroup;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeOBB;

import java.util.List;

@Mixin(value = PartUnitData.class, remap = false)
public interface PartUnitDataAccessor {
    @Accessor("pivotOffset")
    void setPivotOffset(Vec3 value);

    @Accessor("structureGroup")
    void setStructureGroup(VehicleCubeGroup value);

    @Accessor("partCubeOBBs")
    void setPartCubeOBBs(List<VehicleCubeOBB> value);
}
