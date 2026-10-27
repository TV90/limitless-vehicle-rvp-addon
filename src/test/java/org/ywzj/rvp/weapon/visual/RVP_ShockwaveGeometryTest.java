package org.ywzj.rvp.weapon.visual;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证实际绘制共用的球面和亮带算法，避免只对齐扩张数值却仍画成白球或随机云。 */
class RVP_ShockwaveGeometryTest {
    @Test
    void defaultMeshHasBothHemispheresClosedSeamAndUnitNormals() {
        // 调用真实网格生成器，核对默认 36×18 面片及南北极、赤道和闭合接缝。
        Vec3[][] sphere = RVP_ShockwaveReference.buildUnitSphere(36);
        assertEquals(19, sphere.length);
        for (Vec3[] latitude : sphere) {
            assertEquals(37, latitude.length);
            assertEquals(0, latitude[0].distanceTo(latitude[36]), 1e-12);
            for (Vec3 point : latitude) { assertEquals(1, point.lengthSqr(), 1e-12); }
        }
        assertEquals(-1, sphere[0][0].y, 1e-12);
        assertEquals(0, sphere[9][0].y, 1e-12);
        assertEquals(1, sphere[18][0].y, 1e-12);
        assertEquals(5, RVP_ShockwaveReference.buildUnitSphere(0).length);
        assertEquals(33, RVP_ShockwaveReference.buildUnitSphere(Integer.MAX_VALUE).length);
    }

    @Test
    void frontCenterIsTransparentAndTangentHasReferencePeak() {
        Vec3 camera = new Vec3(0, 0, 100);
        // 正面与背面中心都不发光；相机到半径 10 球面的切点满足 normal.z=10/100。
        assertEquals(0, alpha(new Vec3(0, 0, 1), 10, camera, 0.75F, 0.2F, 1));
        assertEquals(0, alpha(new Vec3(0, 0, -1), 10, camera, 0.75F, 0.2F, 1));
        assertEquals(0.15F, alpha(new Vec3(Math.sqrt(0.99), 0, 0.1), 10, camera, 0.75F, 0.2F, 1), 1e-7);
    }

    @Test
    void innerEdgeHasLinearFeatherAndThicknessUsesReferenceClamp() {
        Vec3 camera = new Vec3(0, 0, 100);
        // 带宽 0.2 的内缘在 rim=0.8；15% 过渡宽度为 0.03，取中点应得到一半亮度。
        assertEquals(0, alpha(normalForFacing(0.21), 10, camera, 1, 0.2F, 0.2));
        assertEquals(0.1F, alpha(normalForFacing(0.185), 10, camera, 1, 0.2F, 0.2), 1e-6);
        assertEquals(0.2F, alpha(normalForFacing(0.16), 10, camera, 1, 0.2F, 0.2), 1e-6);
        Vec3 point = normalForFacing(0.5);
        assertEquals(alpha(point, 10, camera, 1, 0.2F, 0.6), alpha(point, 10, camera, 1, 0.2F, 1));
        assertEquals(0, alpha(point, 10, camera, 1, 0.2F, 0.2));
    }

    @Test
    void facingFilterRejectsBackPiecesAndKeepsReferenceSideMargin() {
        Vec3 camera = new Vec3(0, 0, 100);
        // 调用实际面片筛选，核对背面剔除阈值 -0.25，避免背面重复加亮或错误切掉轮廓。
        assertTrue(RVP_ShockwaveReference.isVisibleFace(new Vec3(0, 0, 1), new Vec3(0, 0, 1), camera, 100));
        assertFalse(RVP_ShockwaveReference.isVisibleFace(new Vec3(0, 0, -1), new Vec3(0, 0, -1), camera, 100));
        Vec3 boundary = new Vec3(Math.sqrt(1 - 0.25 * 0.25), 0, -0.25);
        Vec3 outside = new Vec3(Math.sqrt(1 - 0.251 * 0.251), 0, -0.251);
        assertTrue(RVP_ShockwaveReference.isVisibleFace(boundary, boundary, camera, 100));
        assertFalse(RVP_ShockwaveReference.isVisibleFace(outside, outside, camera, 100));
    }

    @Test
    void perspectiveScalesConsistentlyAndCameraInsideDoesNotFillTheScreen() {
        Vec3 point = normalForFacing(0.185);
        Vec3 camera = new Vec3(0, 0, 100);
        // 整体等比缩放只改变尺寸，不能改变轮廓亮度；位于球心时所有视线都正对球面。
        assertEquals(alpha(point, 10, camera, 1, 0.2F, 0.2), alpha(point, 60, camera.scale(6), 1, 0.2F, 0.2), 1e-6);
        for (Vec3[] latitude : RVP_ShockwaveReference.buildUnitSphere(36)) {
            for (Vec3 normal : latitude) { assertEquals(0, alpha(normal, 10, Vec3.ZERO, 1, 0.2F, 1)); }
        }
        assertEquals(0, alpha(new Vec3(0, 0, 1), 10, new Vec3(0, 0, 10), 1, 0.2F, 1));
        assertEquals(0, alpha(point, 10, camera, 0, 0.2F, 1));
        assertEquals(0, alpha(point, 10, camera, 1, 0, 1));
    }

    /** 从相机视线与表面法线的夹角反求球面点；固定半径 10、相机距爆心 100。 */
    private static Vec3 normalForFacing(double cosine) {
        double z = 0.1 * (1 - cosine * cosine) + cosine * Math.sqrt(1 - 0.01 * (1 - cosine * cosine));
        return new Vec3(Math.sqrt(1 - z * z), 0, z);
    }

    /** 调用正式绘制使用的亮带函数，测试不另写一份渲染公式。 */
    private static float alpha(Vec3 normal, double radius, Vec3 camera, float fade, float brightness, double width) {
        return RVP_ShockwaveReference.bandAlpha(normal, radius, camera, camera.lengthSqr(), fade, brightness, width);
    }
}
