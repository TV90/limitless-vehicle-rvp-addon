package org.ywzj.rvp.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationBootstrap;
import org.ywzj.rvp.projectile.simulation.RVP_ProjectileSimulationWeapon;
import net.minecraft.resources.ResourceLocation;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.serialize.GsonUtil;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RVP_WorkshopDataTest {
    @BeforeAll static void initialize() {
        // 解析真实武器数据前复用项目的独立注册表环境。
        RVP_ProjectileSimulationBootstrap.initialize();
    }

    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }

    private static RVP_WorkshopDamage.Projectile projectile(String source) {
        var raw = json(source);
        return new RVP_WorkshopDamage.Projectile(raw, GsonUtil.GSON.fromJson(raw, RVP_WeaponData.class));
    }

    private static RVP_WorkshopDamage.Shot shot(String source, Map<String, RVP_WorkshopDamage.Projectile> children) {
        return RVP_WorkshopDamage.read(ResourceLocation.tryParse("rvp:test_parent"), projectile(source),
                id -> children.get(id.toString())).stream().findFirst().map(RVP_WorkshopDamage.Scenario::shot)
                .orElse(new RVP_WorkshopDamage.Shot(List.of(), List.of()));
    }

    @Test void missingValuesAreDistinctFromZeroAndAllArmorLimitsAreInclusive() {
        var weapon = RVP_WorkshopData.weapon(json("{\"max_capacity\":0,\"damage\":0}"));
        assertEquals(0.0, weapon.capacity());
        assertTrue(shot("{\"damage\":0}", Map.of()).hasPower());
        assertFalse(shot("{}", Map.of()).hasPower());
        assertNull(weapon.rpm());
        assertNull(weapon.reloadSeconds());
        assertEquals("", RVP_WorkshopData.armorGrade(null));
        assertEquals(List.of("very_low", "low", "medium", "high", "very_high", ""),
                List.of(8d, 12d, 15d, 20d, 30d, 31d).stream().map(RVP_WorkshopData::armorGrade).toList());
    }

    @Test void damageTableUsesAutomaticAirOnlyMissileClassification() throws IOException {
        Path weapons = Path.of("limitless_vehicle", "rvp", "data", "rvp", "weapons");
        var expected = Map.ofEntries(
                Map.entry("f22a_aim260a", false),
                Map.entry("lavad_fim92", false),
                Map.entry("bukm3_9m317ma", false),
                Map.entry("cssa5_hq13_saclos_ir", false),
                Map.entry("ea18g_agm65f", true),
                Map.entry("052d_yj19", true),
                Map.entry("052d_yj20", true),
                Map.entry("fa18f_brimstone", true),
                Map.entry("ea18g_agm88", true),
                Map.entry("m1agds_mim146", true),
                Map.entry("ps1_95ya6m", true),
                Map.entry("ps1_tkb1055", true),
                Map.entry("f22a_m61a1", true));
        for (var entry : expected.entrySet()) {
            ResourceLocation weapon = ResourceLocation.tryParse("rvp:" + entry.getKey());
            // 调用包文件的实际解析器，包含 RVP 运行时武器种类和各段制导配置。
            var pack = RVP_ProjectileSimulationWeapon.read(weapons, weapon.toString());
            var projectile = new RVP_WorkshopDamage.Projectile(pack.source(), pack.data());
            var scenarios = RVP_WorkshopDamage.read(weapon, projectile, ignored -> null);
            assertTrue(scenarios.stream().anyMatch(scenario -> scenario.shot().hasPower()), weapon.toString());
            assertEquals(entry.getValue(),
                    scenarios.stream().anyMatch(scenario -> scenario.shot().hasTableDamage()), weapon.toString());
        }
    }

    @Test void vehicleOrderAndMissingFlightDataComeFromPack() {
        var ground = RVP_WorkshopData.vehicle(json("""
                {"type":"example:tracked_vehicle","attributes":{"max_speed_backward":0,"max_speed_forward":1,"max_turn":2},
                 "parts":[{"type":"example:weapon","weapons":[],"rot_info":{"y_rot_speed":0}},
                          {"type":"example:weapon","weapons":["example:tool"],"rot_info":{"y_rot_speed":2},"seat_offset":[0,0,0],"zoom_max":4}]}
                """));
        assertEquals(List.of("forward", "reverse", "turn", "turret"), List.copyOf(ground.mobility().keySet()));
        assertEquals(2.0, ground.mobility().get("turret"));
        var plane = RVP_WorkshopData.vehicle(json("""
                {"type":"example:fixed_wing_vehicle","attributes":{"ceiling":400,"y_turn_rate":2,"climb_rate":3}}
                """));
        // 本体不读取虚构的 climb_rate；缺质量和气动力时不能产生性能数值。
        assertEquals(List.of("ceiling", "turn"), List.copyOf(plane.mobility().keySet()));
        var rotor = RVP_WorkshopData.vehicle(json("""
                {"type":"example:rotary_wing_vehicle","attributes":{"max_air_speed":2,"y_rot_speed_max":4}}
                """));
        assertEquals(List.of("airspeed", "ceiling", "turn"), List.copyOf(rotor.mobility().keySet()));
        assertEquals(256.0, rotor.mobility().get("ceiling"));
    }

    @Test void flightEnvelopeAgreesWithIndependentWorldAxisTickAndIncludesBoost() {
        var v = json("""
                {"type":"example:fixed_wing_vehicle","physics_info":{"mass":14550},"attributes":{
                 "thrust":121202,"thrust_k":1.35294,"air_drag_k_min":29.1,"air_drag_k_max":116.4,"lift_to_drag_k":3.2}}
                """);
        var boosted = RVP_WorkshopData.vehicle(v).mobility();
        // 独立地在世界坐标执行一次本体物理步，求平飞和上升速度不变的解，再对高度、姿态寻优。
        assertEquals(267.82, boosted.get("airspeed") * 72, 0.08);
        assertEquals(32.69, boosted.get("climb") * 20, 0.08);
        v.getAsJsonObject("attributes").addProperty("thrust_k", 1);
        var dry = RVP_WorkshopData.vehicle(v).mobility();
        assertTrue(dry.get("airspeed") < boosted.get("airspeed") && dry.get("climb") < boosted.get("climb"));
        var rotor = RVP_WorkshopData.vehicle(json("""
                {"type":"example:rotary_wing_vehicle","physics_info":{"mass":5100,"friction":7712},
                 "attributes":{"max_air_speed":1.5,"main_rotor_force":74970}}
                """)).mobility();
        assertEquals(108, rotor.get("airspeed") * 72);
        assertEquals(11.4, rotor.get("climb") * 20, 0.15);
    }

    @Test void terminalGuidanceSharesTheExistingFieldAndDoesNotDuplicateAStage() {
        var raw = json("""
                {"guidance_data":{"guidance_type":"GPS","terminal_guidance":{"guidance_type":"ARH"}}}
                """);
        assertEquals("ARH", RVP_WorkshopData.weapon(raw).terminalGuidance());
        raw.getAsJsonObject("guidance_data").addProperty("guidance_type", "ARH");
        assertEquals("", RVP_WorkshopData.weapon(raw).terminalGuidance());
    }

    @Test void explosionAddsToPowerAndEveryApplicableDamageCell() {
        String source = """
                {"shoot_interval":75,"reload":{"time":25},"effects_data":{"caliber":20},"fire_data":{"fire_mode":"BURST"},
                 "damage":99,"collision_data":{"direct_damage":40},"detonate_data":{"explosion_data":{"explode":true,"damage":75,"radius":4}}}
                """;
        var data = RVP_WorkshopData.weapon(json(source));
        assertFalse(data.guided());
        assertEquals("BURST", data.fireMode());
        assertEquals(800.0, data.rpm());
        assertEquals(1.25, data.reloadSeconds());
        String vehicle = RVP_WorkshopDamage.DEFAULT_VEHICLE;
        var normal = shot(source, Map.of());
        assertEquals(115.0, normal.power(vehicle));
        assertEquals(4.0, normal.radius());
        for (int d : new int[]{10,100,500,1000,1500,2000}) for (int a : new int[]{0,30,60,80})
            assertEquals(115.0, normal.impact(vehicle, d, a));
        var disabled = shot("""
                {"damage":12,"detonate_data":{"explosion_data":{"explode":false,"damage":80,"radius":3}}}
                """, Map.of());
        assertEquals(12.0, disabled.power(vehicle));
        assertNull(disabled.radius());
        assertEquals(12.0, disabled.impact(vehicle, 500, 60));
    }

    @Test void hitReleasedPayloadUsesItsOwnFactorsAndSkipsDistanceDecay() {
        String source = """
                {"collision_data":{"direct_damage":100,
                  "direct_damage_factor":{"player":0.5,"vehicles":{"test:wheeled":0.5}},
                  "damage_decay":[{"domain":"distance","type":"linear","start_distance":0,"end_distance":100,"end_factor":0.5},
                                  {"domain":"angle","type":"linear","start_distance":0,"end_distance":30,"end_factor":0.5}]},
                 "detonate_data":{"explosion_data":{"explode":true,"damage":50,"radius":6,
                  "explosion_damage_factor":{"player":0.2,"vehicles":{"test:wheeled":2}}}},
                 "submunition_data":{"releases":[
                  {"triggers":["on_entity_hit","on_aps_intercept"],"release_events":1,"parent_action":"continue",
                   "payloads":[{"kind":"rvp_weapon","weapon_id":"test:payload","count":10,"damage_multiplier":0.5}]},
                  {"triggers":["on_block_hit"],"release_events":1,"parent_action":"discard_after_release",
                   "payloads":[{"kind":"rvp_weapon","weapon_id":"test:payload","count":10}]}]}}
                """;
        var children = Map.of("test:payload", projectile("""
                {"collision_data":{"direct_damage":20,
                  "direct_damage_factor":{"player":0.9,"vehicles":{"test:wheeled":2}},
                  "damage_decay":[{"domain":"distance","type":"linear","start_distance":0,"end_distance":100,
                                  "start_factor":0.6,"end_factor":0.1},
                                  {"domain":"angle","type":"linear","start_distance":0,"end_distance":30,"end_factor":0.8}]},
                 "detonate_data":{"explosion_data":{"explode":true,"damage":5,"radius":4,
                  "explosion_damage_factor":{"vehicles":{"test:wheeled":3}}}}}
                """));
        String vehicle = RVP_WorkshopDamage.DEFAULT_VEHICLE;
        var normal = shot(source, children);
        assertEquals(2, normal.stages().size());
        assertEquals(java.math.BigInteger.TEN, normal.stages().get(1).count());
        assertEquals(300.0, normal.power(vehicle));
        assertEquals(500.0, normal.power("test:wheeled"));
        assertEquals(240.0, normal.power(RVP_WorkshopDamage.PLAYER), 0.0001);
        assertEquals(225.0, normal.impact(vehicle, 100, 0), 0.0001);
        assertEquals(180.0, normal.impact(vehicle, 100, 30), 0.0001);
        assertEquals(425.0, normal.impact("test:wheeled", 100, 0), 0.0001);
        assertTrue(normal.vehicleTypes().contains("test:wheeled"));
        var tandem = shot(source.replace("continue", "discard_after_release"), children);
        assertEquals(250.0, tandem.power(vehicle));
        assertEquals(4.0, tandem.radius());
        var withFuse = json(source.replace("on_entity_hit", "on_fuse"));
        withFuse.add("fuse_data", json("{\"delay_tick\":5}"));
        var fuse = RVP_WorkshopDamage.read(ResourceLocation.tryParse("rvp:test_parent"), projectile(withFuse.toString()),
                id -> children.get(id.toString())).stream()
                .filter(scenario -> scenario.context() == RVP_WorkshopDamage.Context.DELAY).findFirst().orElseThrow().shot();
        assertEquals(65.0, fuse.impact(vehicle, 100, 0), 0.0001);
    }

    @Test void canisterScalesEveryCellAndRicochetBlocksHitReleasedPayloads() {
        String root = """
                {"fire_data":{"canister_count":8,"canister_burst_count":2,"burst_count":3},
                 "collision_data":{"direct_damage":5,"bounce":1,"bounce_incidence_angle":60,"bounce_on_vehicle":true,
                  "damage_decay":[{"domain":"distance","type":"linear","start_distance":0,"end_distance":100,"end_factor":0.5},
                                  {"domain":"angle","type":"linear","start_distance":0,"end_distance":30,"end_factor":0.5}]}}
                """;
        String vehicle = RVP_WorkshopDamage.DEFAULT_VEHICLE;
        var canister = shot(root, Map.of());
        assertEquals(java.math.BigInteger.valueOf(16), canister.stages().get(0).count());
        assertEquals(80.0, canister.power(vehicle));
        assertEquals(40.0, canister.impact(vehicle, 100, 0));
        assertEquals(20.0, canister.impact(vehicle, 100, 30));
        assertNull(canister.impact(vehicle, 100, 60));
        assertEquals(40.0, canister.impact(RVP_WorkshopDamage.PLAYER, 100, 80));
        var hit = projectile("""
                {"collision_data":{"direct_damage":5,"bounce":1,"bounce_incidence_angle":80,"bounce_on_vehicle":true},
                 "detonate_data":{"explosion_data":{"explode":true,"damage":20,"radius":3}},
                 "submunition_data":{"releases":[{"triggers":["on_entity_hit"],"release_events":1,
                  "payloads":[{"kind":"rvp_weapon","weapon_id":"test:followup","count":1}]}]}}
                """);
        var stacked = RVP_WorkshopDamage.read(ResourceLocation.tryParse("rvp:test_parent"), hit,
                id -> projectile("{\"collision_data\":{\"direct_damage\":7}}")).get(0).shot();
        assertEquals(32.0, stacked.impact(vehicle, 10, 0));
        assertNull(stacked.impact(vehicle, 10, 80));
        assertEquals(32.0, stacked.impact(RVP_WorkshopDamage.LIVING, 10, 80));
    }
}
