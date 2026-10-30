package org.ywzj.rvp.client.light;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.ywzj.rvp.weapon.visual.RVP_PressureWaveGeometry.Bounds;
import org.ywzj.rvp.weapon.visual.RVP_RocketTrailParameters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/** 烟段投影和屏幕覆盖预算的纯几何逻辑，隐藏窗口与普通单测均可验证。 */
final class RVP_RocketTrailGeometry {
    private RVP_RocketTrailGeometry() { }

    /**
     * 两端球体的立方体角点共同求范围，最后才裁到屏幕。
     * 不能先剔除两个端点：一段横穿屏幕的长烟可能两端都在屏幕外。
     */
    static Bounds bounds(RVP_RocketTrailSegment segment, Matrix4f view, Matrix4f projection, Vec3 camera) {
        Vector3f first = position(segment.start(), view, camera);
        Vector3f last = position(segment.end(), view, camera);
        if (first.z - segment.startRadius() >= 0 && last.z - segment.endRadius() >= 0) { return null; }
        if (first.z + segment.startRadius() >= -0.05F || last.z + segment.endRadius() >= -0.05F) {
            return new Bounds(-1, -1, 1, 1);
        }
        float left = Float.POSITIVE_INFINITY;
        float bottom = Float.POSITIVE_INFINITY;
        float right = Float.NEGATIVE_INFINITY;
        float top = Float.NEGATIVE_INFINITY;
        Vector4f corner = new Vector4f();
        for (int end = 0; end < 2; end++) {
            Vector3f center = end == 0 ? first : last;
            float radius = end == 0 ? segment.startRadius() : segment.endRadius();
            for (int index = 0; index < 8; index++) {
                corner.set(center.x + ((index & 1) == 0 ? -radius : radius),
                        center.y + ((index & 2) == 0 ? -radius : radius),
                        center.z + ((index & 4) == 0 ? -radius : radius), 1).mul(projection);
                if (corner.w <= 0.00001F) { return new Bounds(-1, -1, 1, 1); }
                left = Math.min(left, corner.x / corner.w); bottom = Math.min(bottom, corner.y / corner.w);
                right = Math.max(right, corner.x / corner.w); top = Math.max(top, corner.y / corner.w);
            }
        }
        left = Math.max(-1, left); bottom = Math.max(-1, bottom);
        right = Math.min(1, right); top = Math.min(1, top);
        return left < right && bottom < top ? new Bounds(left, bottom, right, top) : null;
    }

    /**
     * 输入已从远到近排序；先保留近处烟，再限制全部矩形覆盖总量，最后恢复透明合成顺序。
     * 这同时限制绘制条数之外的像素开销，避免身处烟里时 512 段烟都覆盖整屏。
     */
    static List<Projected> budget(List<RVP_RocketTrailSegment> segments, Function<RVP_RocketTrailSegment, Bounds> project) {
        List<Projected> result = new ArrayList<>();
        double remaining = RVP_RocketTrailParameters.MAX_SCREEN_COVERAGE;
        for (int index = segments.size() - 1; index >= 0; index--) {
            RVP_RocketTrailSegment segment = segments.get(index);
            Bounds bounds = project.apply(segment);
            if (bounds == null) { continue; }
            double area = (bounds.right() - bounds.left()) * (bounds.top() - bounds.bottom()) * 0.25;
            if (area <= 0 || area > remaining + 0.000001) { continue; }
            remaining -= area;
            result.add(new Projected(segment, bounds, area));
        }
        Collections.reverse(result);
        return result;
    }

    /** 两端坐标都先减相机再转 float，避免世界远坐标损失精度。 */
    private static Vector3f position(Vec3 point, Matrix4f view, Vec3 camera) {
        return view.transformPosition(new Vector3f((float) (point.x - camera.x),
                (float) (point.y - camera.y), (float) (point.z - camera.z)));
    }

    /** @param segment 世界烟段 @param bounds 归一化屏幕矩形 @param area 占整屏面积比例，0～1 */
    record Projected(RVP_RocketTrailSegment segment, Bounds bounds, double area) { }
}
