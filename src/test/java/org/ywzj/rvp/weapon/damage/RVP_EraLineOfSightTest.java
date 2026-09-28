package org.ywzj.rvp.weapon.damage;

import com.mojang.math.Axis;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.ywzj.vehicle.vehicle.structure.OBB;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ERA 爆炸破坏视距判定（2026-09-29 用户定版）纯逻辑单测：爆心→ERA OBB 中心连线被本车
 * 其它骨骼 OBB 阻挡的候选剔除；爆心在遮挡物内部不遮挡；入射双 ε 防贴板误挡。
 */
class RVP_EraLineOfSightTest {

    /** 轴对齐 OBB（无旋转）。 */
    private static OBB aabb(float cx, float cy, float cz, float hx, float hy, float hz) {
        return new OBB(new Vector3f(cx, cy, cz), new Vector3f(hx, hy, hz), new Quaternionf());
    }

    /** 绕 Z 轴转 90° 的 OBB（长轴沿 Y），模拟倾斜布局。 */
    private static OBB rotated(float cx, float cy, float cz, float hx, float hy, float hz) {
        return new OBB(new Vector3f(cx, cy, cz), new Vector3f(hx, hy, hz),
                Axis.ZP.rotationDegrees(90f));
    }

    /** X 轴正向为车首：爆心在车前 -10，正面装甲板竖在 x=-1（薄板 0.2 厚），ERA 在 x=+2。 */
    private static final Vec3 BLAST_FRONT = new Vec3(-10, 0, 0);

    @Test
    void frontalPlateBlocksRearEra() {
        Map<String, List<OBB>> obbs = new HashMap<>();
        obbs.put("front_plate", List.of(aabb(-1f, 0f, 0f, 0.1f, 5f, 5f)));
        obbs.put("era_top", List.of(aabb(-0.8f, 6f, 0f, 0.3f, 0.3f, 0.3f)));
        obbs.put("era_rear", List.of(aabb(2f, 0f, 0f, 0.3f, 0.3f, 0.3f)));
        // 正面爆心：车顶上方 ERA 连线越过板顶（可见），车体后方的 era_rear 被板挡（剔除）
        List<String> visible = RVP_VehicleHitboxFactorManager.filterBonesByLineOfSight(
                BLAST_FRONT, obbs, List.of("era_top", "era_rear"));
        assertTrue(visible.contains("era_top"), "连线越过板顶的 ERA 应可见");
        assertFalse(visible.contains("era_rear"), "被正面装甲挡住的后侧 ERA 应剔除");
    }

    @Test
    void sideEraVisibleWithoutBlocker() {
        Map<String, List<OBB>> obbs = new HashMap<>();
        obbs.put("front_plate", List.of(aabb(-1f, 0f, 0f, 0.1f, 5f, 5f)));
        obbs.put("era_side", List.of(aabb(-1f, 0f, 6f, 0.3f, 0.3f, 0.3f)));
        // 侧面 ERA：爆心→其中心连线绕开正面装甲板 → 可见
        List<String> visible = RVP_VehicleHitboxFactorManager.filterBonesByLineOfSight(
                BLAST_FRONT, obbs, List.of("era_side"));
        assertTrue(visible.contains("era_side"), "无遮挡连线的侧向 ERA 应可见");
    }

    @Test
    void blastInsideBlockerDoesNotBlock() {
        Map<String, List<OBB>> obbs = new HashMap<>();
        // 爆心就在装甲板 OBB 内部（贴板/入车爆炸）：该板对任何连线都不构成遮挡
        obbs.put("front_plate", List.of(aabb(-1f, 0f, 0f, 0.6f, 5f, 5f)));
        obbs.put("era_rear", List.of(aabb(2f, 0f, 0f, 0.3f, 0.3f, 0.3f)));
        Vec3 blastInside = new Vec3(-1, 0, 0);
        List<String> visible = RVP_VehicleHitboxFactorManager.filterBonesByLineOfSight(
                blastInside, obbs, List.of("era_rear"));
        assertTrue(visible.contains("era_rear"), "爆心在遮挡物内部时不应遮挡");
    }

    @Test
    void grazingPlateNearMountingFaceDoesNotBlock() {
        Map<String, List<OBB>> obbs = new HashMap<>();
        // 安装面 grazing：爆心在板前 0.1 格（板外），ERA 中心紧贴板背面——clip 命中点距爆心
        // 仅 0.1 < 双 ε（0.2 格）→ 不算遮挡（防贴板 grazing 误挡安装面上的 ERA）
        obbs.put("front_plate", List.of(aabb(-1f, 0f, 0f, 0.1f, 5f, 5f)));
        obbs.put("era_on_plate", List.of(aabb(-0.85f, 2f, 0f, 0.3f, 0.3f, 0.3f)));
        Vec3 blastOnPlate = new Vec3(-1.2, 2f, 0);
        List<String> visible = RVP_VehicleHitboxFactorManager.filterBonesByLineOfSight(
                blastOnPlate, obbs, List.of("era_on_plate"));
        assertTrue(visible.contains("era_on_plate"), "贴板 grazing 误挡应被 ε 容差排除");
    }

    @Test
    void rotatedBlockerStillBlocks() {
        Map<String, List<OBB>> obbs = new HashMap<>();
        // 倾斜遮挡物（旋转 OBB）同样参与遮挡：斜板挡住斜后方的 ERA
        obbs.put("angled_plate", List.of(rotated(0f, 0f, 0f, 0.1f, 5f, 5f)));
        obbs.put("era_behind", List.of(aabb(6f, 0f, 0f, 0.3f, 0.3f, 0.3f)));
        List<String> visible = RVP_VehicleHitboxFactorManager.filterBonesByLineOfSight(
                new Vec3(-10, 0, 0), obbs, List.of("era_behind"));
        assertFalse(visible.contains("era_behind"), "旋转 OBB 遮挡应生效");
    }
}
