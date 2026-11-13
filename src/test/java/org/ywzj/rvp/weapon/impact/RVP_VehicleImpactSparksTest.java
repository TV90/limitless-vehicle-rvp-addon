package org.ywzj.rvp.weapon.impact;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.weapon.data.RVP_EffectsData;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 覆盖开关默认值、显式关闭、实际载具包配置、口径缩放和朝外喷射。 */
class RVP_VehicleImpactSparksTest {
    /** 只解析实际消费的视觉分组，不在纯单元测试中启动物品配方等 Minecraft 注册表。 */
    private final Gson gson = new Gson();

    @Test
    void defaultsOnAndHonorsExplicitDisable() {
        RVP_EffectsData enabled = gson.fromJson(
                "{\"vehicle_impact_sparks\":true}", RVP_EffectsData.class);
        // 调用真实触发规则；不依赖名称、爆炸半径或穿透次数。
        assertTrue(RVP_VehicleImpactSparks.shouldEmit(enabled));
        assertFalse(RVP_VehicleImpactSparks.shouldEmit(null));
        // 未配置开关的弹种默认生效（2026-10-24 改为默认开启），显式 false 仍可单独关闭。
        assertTrue(RVP_VehicleImpactSparks.shouldEmit(gson.fromJson("{}", RVP_EffectsData.class)));
        assertFalse(RVP_VehicleImpactSparks.shouldEmit(gson.fromJson(
                "{\"vehicle_impact_sparks\":false}", RVP_EffectsData.class)));
    }

    @Test
    void cannonSparksGrowWithPhysicalCaliberAndStayBounded() {
        float previousSize = 0f;
        int previousCount = 0;
        double previousSpeed = 0;
        for (float caliber : new float[]{7.62f, 25f, 30f, 105f, 120f, 125f, 200f}) {
            // 调用生产尺寸、数量与速度规则，验证口径增大不会反而缩小。
            float size = RVP_VehicleImpactSparks.sparkSize(caliber);
            int count = RVP_VehicleImpactSparks.sparkCount(caliber);
            double speed = RVP_VehicleImpactSparks.sparkSpeed(caliber);
            assertTrue(size > previousSize);
            assertTrue(count >= previousCount && count <= 45);
            assertTrue(speed > previousSpeed);
            previousSize = size;
            previousCount = count;
            previousSpeed = speed;
        }
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, -1f, 0f}) {
            assertEquals(7.62f, RVP_VehicleImpactSparks.sanitizeCaliber(invalid));
        }
        assertEquals(RVP_VehicleImpactSparks.sparkSize(200), RVP_VehicleImpactSparks.sparkSize(Float.MAX_VALUE));
    }

    @Test
    void sparkDirectionsStayOutsideEveryArmorFace() {
        for (Vec3 normal : List.of(new Vec3(1, 0, 0), new Vec3(-1, 0, 0),
                new Vec3(0, 1, 0), new Vec3(0, -1, 0), new Vec3(0, 0, 1),
                new Vec3(0, 0, -1), new Vec3(1, 2, -3), Vec3.ZERO)) {
            // 调用生产法线回退，为六个朝向、倾斜板面及缺失法线验证多个入射角。
            Vec3 surface = RVP_VehicleImpactSparks.outwardNormal(normal);
            Vec3 helper = Math.abs(surface.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
            Vec3 tangent = surface.cross(helper).normalize();
            for (double degrees : new double[]{0, 30, 60, 85, 89.9}) {
                double angle = Math.toRadians(degrees);
                Vec3 incoming = tangent.scale(Math.sin(angle)).subtract(surface.scale(Math.cos(angle)));
                for (int index = 0; index < 100; index++) {
                    // 调用真实飞溅采样，任何入射角都不应产生向装甲内部飞行的初速度。
                    Vec3 direction = RVP_VehicleImpactSparks.sparkDirection(normal, incoming,
                            index / 100.0, index * 0.618 % 1);
                    assertEquals(1.0, direction.length(), 1.0E-9);
                    assertTrue(direction.dot(surface) >= -1.0E-12);
                }
            }
        }
    }

    @Test
    void grazingImpactsFollowIncomingTravelAlongPlate() {
        Vec3 normal = new Vec3(0, 1, 0);
        Vec3 grazing = new Vec3(1, -0.02, 0).normalize();
        Vec3 headOnSum = Vec3.ZERO;
        Vec3 grazingSum = Vec3.ZERO;
        for (int index = 0; index < 1000; index++) {
            double u = (index + 0.5) / 1000.0;
            double v = index * 0.61803398875 % 1.0;
            // 调用相同随机样本比较入射角的效果，防止退回与来向无关的半球散射。
            headOnSum = headOnSum.add(RVP_VehicleImpactSparks.sparkDirection(normal, new Vec3(0, -1, 0), u, v));
            grazingSum = grazingSum.add(RVP_VehicleImpactSparks.sparkDirection(normal, grazing, u, v));
        }
        assertTrue(headOnSum.y / 1000.0 > 0.7);
        assertTrue(Math.abs(headOnSum.x / 1000.0) < 0.01);
        assertTrue(grazingSum.x / 1000.0 > 0.95);
        assertTrue(grazingSum.y / 1000.0 < 0.15);
    }

    @Test
    void centralDirectionReflectsOffSlopedPlateAndPreservesTravelSide() {
        Vec3 normal = new Vec3(0, 1, 1).normalize();
        // 45° 斜板将向下入射反射为 +Z；验证装甲板自身倾斜确实参与方向计算。
        Vec3 sloped = RVP_VehicleImpactSparks.sparkDirection(normal, new Vec3(0, -1, 0), 0, 0);
        assertEquals(0, sloped.x, 1.0E-9);
        assertEquals(0, sloped.y, 1.0E-9);
        assertEquals(1, sloped.z, 1.0E-9);
        for (double side : new double[]{-1, 1}) {
            // 相反方向的来弹必须沿板面向相反一侧飞溅，且单位化前后速度大小不影响角度。
            Vec3 incoming = new Vec3(side, -0.1, 0);
            Vec3 direction = RVP_VehicleImpactSparks.sparkDirection(new Vec3(0, 1, 0), incoming, 0.3, 0.7);
            assertTrue(direction.x * side > 0.9);
            assertEquals(0, direction.distanceTo(RVP_VehicleImpactSparks.sparkDirection(
                    new Vec3(0, 1, 0), incoming.scale(10), 0.3, 0.7)), 1.0E-12);
        }
    }

    @Test
    void missingIncomingDirectionFallsBackToHeadOnImpact() {
        Vec3 normal = new Vec3(0, 1, 0);
        // 调用生产零速度回退，必须得到有限且向外的单位方向。
        Vec3 direction = RVP_VehicleImpactSparks.sparkDirection(normal, Vec3.ZERO, 0.5, 0.2);
        assertEquals(1, direction.length(), 1.0E-9);
        assertTrue(direction.y > 0.5);
    }

    @Test
    void packKeepsArmorPiercingRoundsEnabledAndHeDefaultsOn() throws Exception {
        
        for (String weapon : List.of("abramsx_xm829a5", "bmpt_2a42", "gsh_30_1", "lav25_apfsds",
                "m1a2sep_m829a2", "mi28_2a42", "t84bm_3bm42", "t90m_3bm59", "vt4_dtc10",
                "zbl08a_apfsds", "ztz100_dtc251")) {
            // 提取实际 JSON 的 effects_data，用生产数据模型验证开关与口径。
            RVP_EffectsData data = gson.fromJson(JsonParser.parseString(Files.readString(org.ywzj.rvp.testutil.RVP_TestPackPaths.data("weapons", weapon + ".json")))
                    .getAsJsonObject().get("effects_data"), RVP_EffectsData.class);
            // 调用生产规则核实真实穿甲弹配置已启用，并且口径与曳光宽度分开。
            assertTrue(RVP_VehicleImpactSparks.shouldEmit(data), weapon);
            assertTrue(data.getCaliber() >= 25f, weapon);
        }
        for (String weapon : List.of("gsh_30_1_he", "lav25_hei", "zbl08a_hei", "t84bm_3of26",
                "t90m_3of26", "vt4_dtb10", "abramsx_xm1127", "ztz100_dtb251", "m1a2sep_m830a1")) {
            // 提取实际 HE 配置的视觉分组；开关改为默认开启后 HE 同样生效。
            RVP_EffectsData data = gson.fromJson(JsonParser.parseString(Files.readString(org.ywzj.rvp.testutil.RVP_TestPackPaths.data("weapons", weapon + ".json")))
                    .getAsJsonObject().get("effects_data"), RVP_EffectsData.class);
            // 调用生产规则核实 HE/HEAT 走默认开启；需要关闭时在 JSON 显式写 false，由上一用例覆盖。
            assertTrue(RVP_VehicleImpactSparks.shouldEmit(data), weapon);
        }
    }
}
