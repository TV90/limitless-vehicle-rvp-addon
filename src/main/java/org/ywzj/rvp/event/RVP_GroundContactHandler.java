package org.ywzj.rvp.event;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Vector3f;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager;
import org.ywzj.vehicle.api.event.VehicleCollectCollisionEvent;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeOBB;

@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_GroundContactHandler {

    private static final float CONTACT_EPSILON = 0.1f;

    private RVP_GroundContactHandler() {}

    @SubscribeEvent
    public static void onVehicleCollectCollision(VehicleCollectCollisionEvent event) {
        AbstractVehicle vehicle = event.getVehicle();
        var cfg = RVP_VehicleExtendedConfigManager.INSTANCE.get(vehicle);
        if (cfg.groundContactPartIds().isEmpty()) {
            return;
        }

        VehicleCubeOBB mainCube = vehicle.getMainCubeOBB();
        Vector3f[] axes = mainCube.obb().getAxes();
        for (String partId : cfg.groundContactPartIds()) {
            PartUnit<?> partUnit = vehicle.getPartUnit(partId).orElse(null);
            if (partUnit == null) {
                continue;
            }
            for (VehicleCubeOBB cube : partUnit.getPartCubeOBBs()) {
                appendCubeContacts(event, mainCube, axes, cube);
            }
        }
    }

    private static void appendCubeContacts(VehicleCollectCollisionEvent event, VehicleCubeOBB mainCube, Vector3f[] axes, VehicleCubeOBB partCube) {
        Vec3 centerOffset = partCube.offset().subtract(mainCube.offset());
        float baseX = (float) centerOffset.x;
        float baseZ = (float) centerOffset.z;
        float y = (float) (centerOffset.y - partCube.obb().extents().y - CONTACT_EPSILON);
        float ex = partCube.obb().extents().x;
        float ez = partCube.obb().extents().z;

        addBottomContact(event, mainCube, axes, baseX, y, baseZ);
        addBottomContact(event, mainCube, axes, baseX - ex, y, baseZ - ez);
        addBottomContact(event, mainCube, axes, baseX - ex, y, baseZ + ez);
        addBottomContact(event, mainCube, axes, baseX + ex, y, baseZ - ez);
        addBottomContact(event, mainCube, axes, baseX + ex, y, baseZ + ez);
    }

    private static void addBottomContact(VehicleCollectCollisionEvent event, VehicleCubeOBB mainCube, Vector3f[] axes,
                                         float x, float y, float z) {
        VehicleCubeOBB.CubePoint point = new VehicleCubeOBB.CubePoint(
                mainCube,
                new Vector3f(x, y, z),
                VehicleCubeOBB.CubeFace.BOTTOM
        );
        Vec3 worldPos = new Vec3(point.worldPos(axes));
        BlockPos blockPos = BlockPos.containing(worldPos);
        BlockState blockState = event.getVehicle().level().getBlockState(blockPos);
        if (!blockState.isSolid()) {
            return;
        }
        point.cubePointContext.setBlockPos(Vec3.atBottomCenterOf(blockPos));
        point.cubePointContext.setBlockState(blockState);
        event.getTouchPoints().add(point);
    }
}
