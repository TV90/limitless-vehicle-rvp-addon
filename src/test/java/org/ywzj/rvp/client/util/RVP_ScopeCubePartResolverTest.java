package org.ywzj.rvp.client.util;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeGroup;
import java.util.IdentityHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 真实结构组关联：独立炮管和座圈不混成整个炮塔，匿名子骨继承最近具名父骨。 */
class RVP_ScopeCubePartResolverTest {
    @Test void bodyModulesWithoutPartUnitsStillResolveFromChildCubeGroups() {
        BedrockBone body = new BedrockBone();
        BedrockBone engine = new BedrockBone();
        BedrockBone anonymous = new BedrockBone();
        body.getChildren().add(engine); engine.getChildren().add(anonymous);
        var bodyGroup = new VehicleCubeGroup(null, new Quaternionf(), Vec3.ZERO);
        var engineGroup = new VehicleCubeGroup(bodyGroup, new Quaternionf(), Vec3.ZERO);
        var childGroup = new VehicleCubeGroup(engineGroup, new Quaternionf(), Vec3.ZERO);
        var cube = new org.ywzj.vehicle.vehicle.structure.VehicleCubeOBB(
                new org.ywzj.vehicle.vehicle.structure.OBB(new org.joml.Vector3f(),
                        new org.joml.Vector3f(1), new Quaternionf()));
        cube.group = childGroup;
        Map<VehicleCubeGroup, String> result = new IdentityHashMap<>();
        RVP_ScopeCubePartResolver.registerBodyGroups(java.util.List.of(cube, cube), body,
                Map.of(body, "vehicle_body", engine, "Engine"), result);
        assertEquals(3, result.size());
        assertEquals("vehicle_body", result.get(bodyGroup));
        assertEquals("Engine", result.get(engineGroup));
        assertEquals("Engine", result.get(childGroup));
    }

    @Test void namedChildrenStayIndependentAndAnonymousGeometryInheritsItsBone() {
        BedrockBone turret = new BedrockBone();
        BedrockBone barrel = new BedrockBone();
        BedrockBone ring = new BedrockBone();
        BedrockBone anonymous = new BedrockBone();
        turret.getChildren().add(barrel); turret.getChildren().add(ring); ring.getChildren().add(anonymous);
        var root = new VehicleCubeGroup(null, new Quaternionf(), Vec3.ZERO);
        var barrelGroup = new VehicleCubeGroup(root, new Quaternionf(), Vec3.ZERO);
        var ringGroup = new VehicleCubeGroup(root, new Quaternionf(), Vec3.ZERO);
        var anonymousGroup = new VehicleCubeGroup(ringGroup, new Quaternionf(), Vec3.ZERO);
        Map<org.ywzj.vehicle.vehicle.structure.VehicleCubeGroup, String> result = new IdentityHashMap<>();
        RVP_ScopeCubePartResolver.registerGroups(root, turret, Map.of(turret,"turret",barrel,"turret_barrel",ring,"turretring"), "turret", result);
        assertEquals("turret_barrel",result.get(barrelGroup));
        assertEquals("turretring",result.get(ringGroup));
        assertEquals("turretring",result.get(anonymousGroup));
        assertEquals("turret",result.get(root));
    }
}
