package org.ywzj.rvp.util;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockModel;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.junit.jupiter.api.*;
import org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager;
import org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationBootstrap;
import org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.custom.StructureModelManager;
import org.ywzj.vehicle.custom.part.data.PartUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeGroup;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationBootstrap.*;

/** 用本体真实结构解析与 group 层级验证选骨，测试环境只替换世界/实体构造边界。 */
class RVP_NamedVehicleObbsTest {
    private CommonAssetsManager previousAssets;
    private Object previousConfigs;
    private FixedWingVehicle vehicle;
    private VehicleCubeGroup turret;
    private final ResourceLocation vehicleId = new ResourceLocation("rvp", "test_weakpoint");
    private final ResourceLocation modelId = new ResourceLocation("rvp", "vehicle/test_weakpoint");

    @BeforeAll static void bootstrap() { RVP_ProjectileSimulationBootstrap.initialize(); }

    @BeforeEach void createModelAndTurret() {
        previousAssets = CommonAssetsManager.INSTANCE;
        previousConfigs = read(RVP_VehicleExtendedConfigManager.class, RVP_VehicleExtendedConfigManager.INSTANCE, "configs");
        CommonAssetsManager.INSTANCE = new CommonAssetsManager();
        ((StructureModelManager) CommonAssetsManager.structureModelManager()).apply(Map.of(modelId, JsonParser.parseString("""
                {"format_version":"1.12.0","minecraft:geometry":[{"description":{"identifier":"geometry.test",
                  "texture_width":16,"texture_height":16,"visible_bounds_offset":[0,0,0]},"bones":[
                  {"name":"main_structure","pivot":[0,0,0],"cubes":[{"origin":[-40,-20,-50],"size":[80,80,100],"uv":[0,0]}]},
                  {"name":"vehicle_body","pivot":[0,0,0],"cubes":[{"origin":[-32,0,-48],"size":[64,16,96],"uv":[0,0]}]},
                  {"name":"turret","pivot":[0,16,0],"cubes":[{"origin":[-16,16,-16],"size":[32,16,32],"uv":[0,0]}]},
                  {"name":"top","parent":"turret","pivot":[0,32,0],"cubes":[{"origin":[-16,30,-16],"size":[32,2,32],"uv":[0,0]}]},
                  {"name":"scope","parent":"turret","pivot":[0,32,0],"cubes":[{"origin":[8,32,8],"size":[8,8,8],"uv":[0,0]}]}
                ]}]}
                """)), null, null);
        var config = JsonParser.parseString("""
                {"structure_model":"rvp:vehicle/test_weakpoint","physics_info":{"physics_only_bones":["main_structure"]}}
                """).getAsJsonObject();
        RVP_VehicleExtendedConfigManager.INSTANCE.applyFromJsonMap(Map.of(vehicleId, config));
        BedrockModel model = CommonAssetsManager.structureModelManager().getStructureModel(modelId).orElseThrow();
        turret = group(model.getBoneMap().get("turret"), null);
        vehicle = environment(FixedWingVehicle.class);
        vehicle.setUUID(UUID.randomUUID());
        write(Entity.class, vehicle, "position", Vec3.ZERO);
        write(AbstractVehicle.class, vehicle, "vehicleId", vehicleId);
        vehicle.centerOffset = Vec3.ZERO;
        PartUnit<?> part = environment(PartUnit.class);
        PartUnitData data = new TestPartData();
        write(PartUnitData.class, data, "structureBone", "turret");
        write(PartUnit.class, part, "data", data);
        write(PartUnit.class, part, "structureGroup", turret);
        write(AbstractVehicle.class, vehicle, "partUnits", List.of(part));
    }

    @AfterEach void restoreGlobalAssets() {
        CommonAssetsManager.INSTANCE = previousAssets;
        write(RVP_VehicleExtendedConfigManager.class, RVP_VehicleExtendedConfigManager.INSTANCE, "configs", previousConfigs);
    }

    @Test void sameBoneKeyFollowsTurretRotationAndVehicleTranslation() {
        var key = new RVP_NamedVehicleObbs.Key("scope", 0);
        Vec3 first = RVP_NamedVehicleObbs.resolve(vehicle, key).center();
        turret.rotation = new Quaternionf().rotateY((float) Math.PI / 2);
        Vec3 rotated = RVP_NamedVehicleObbs.resolve(vehicle, key).center();
        assertTrue(first.distanceTo(rotated) > .5, "不能使用静态结构骨中心冒充转动炮塔锁点");
        write(Entity.class, vehicle, "position", new Vec3(10, 3, -20));
        Vec3 moved = RVP_NamedVehicleObbs.resolve(vehicle, key).center();
        assertEquals(10, moved.x - rotated.x, 1.0E-5);
        assertEquals(3, moved.y - rotated.y, 1.0E-5);
        assertEquals(-20, moved.z - rotated.z, 1.0E-5);
        assertNull(RVP_NamedVehicleObbs.resolve(vehicle, new RVP_NamedVehicleObbs.Key("scope", 2)));
    }

    @Test void unconfiguredHullAndIndependentTopFactorUseTheActualParsedModel() {
        var boxes = RVP_NamedVehicleObbs.collect(vehicle);
        assertFalse(boxes.isEmpty());
        assertTrue(boxes.stream().noneMatch(box -> box.key().bone().equals("main_structure")));
        var config = JsonParser.parseString("""
                {"structure_model":"rvp:vehicle/test_weakpoint","hitbox_explosion_damage_factor":{"top":1.5}}
                """).getAsJsonObject();
        var manager = new RVP_VehicleHitboxFactorManager();
        manager.applyFromJsonMap(Map.of(vehicleId, config));
        var top = RVP_NamedVehicleObbs.resolve(vehicle, new RVP_NamedVehicleObbs.Key("top", 0));
        assertEquals(1.5F, manager.resolveHitboxExplosionDamageFactor(vehicle, top.center().add(0, .1, 0)));
        var body = RVP_NamedVehicleObbs.resolve(vehicle, new RVP_NamedVehicleObbs.Key("vehicle_body", 0));
        assertEquals(1F, manager.resolveHitboxExplosionDamageFactor(vehicle, body.center()));
    }

    private static VehicleCubeGroup group(BedrockBone bone, VehicleCubeGroup parent) {
        VehicleCubeGroup result = new VehicleCubeGroup(parent, new Quaternionf(bone.rotation), new Vec3(bone.x / 16, bone.y / 16, bone.z / 16));
        for (BedrockBone child : bone.getChildren()) group(child, result);
        return result;
    }

    private static final class TestPartData extends PartUnitData {
        TestPartData() { super("turret"); }
    }
}
