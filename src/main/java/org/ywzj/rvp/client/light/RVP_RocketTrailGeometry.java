package org.ywzj.rvp.client.light;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.ywzj.rvp.weapon.visual.RVP_PressureWaveGeometry.Bounds;
import org.ywzj.rvp.weapon.visual.RVP_RocketTrailParameters;
import java.util.ArrayList;
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
     * 输入已从远到近排序；烟内和烟外分别分配面积预算，再恢复原透明合成顺序。
     * 相机所在烟团不能占光所有份额，否则站进几团冲刷烟便会使远处体积烟突然消失。
     */
    static List<Projected> budget(List<RVP_RocketTrailSegment> segments, Vec3 camera,
            Function<RVP_RocketTrailSegment, Bounds> project) {
        List<Projected> nearestFirst = new ArrayList<>();
        double exteriorArea = 0;
        for (int index = segments.size() - 1; index >= 0; index--) {
            RVP_RocketTrailSegment segment = segments.get(index);
            Bounds bounds = project.apply(segment);
            if (bounds == null) { continue; }
            double area = (bounds.right() - bounds.left()) * (bounds.top() - bounds.bottom()) * 0.25;
            if (!Double.isFinite(area) || area <= 0) { continue; }
            nearestFirst.add(new Projected(segment, bounds, area));
            // 调用真实烟段包含判定，跨近裁剪面的整屏投影并不一定意味着眼睛在烟里面。
            if (!segment.contains(camera)) { exteriorArea += area; }
        }
        boolean[] selected = new boolean[nearestFirst.size()];
        double remaining = RVP_RocketTrailParameters.MAX_SCREEN_COVERAGE;
        double interiorRemaining = remaining - Math.min(RVP_RocketTrailParameters.EXTERIOR_SCREEN_RESERVE, exteriorArea);
        for (int index = 0; index < nearestFirst.size(); index++) {
            Projected input = nearestFirst.get(index);
            // 调用烟内判定和独立额度，只挑可以装下的近处烟，不侵占预留给其它体积烟的面积。
            if (input.segment().contains(camera) && input.area() <= interiorRemaining + 0.000001) {
                selected[index] = true; interiorRemaining -= input.area(); remaining -= input.area();
            }
        }
        for (int index = 0; index < nearestFirst.size(); index++) {
            Projected input = nearestFirst.get(index);
            // 调用同一烟内判定，烟外尾迹使用预留和剩余额度，不因眼前烟团变成整屏而被全部跳过。
            if (!input.segment().contains(camera) && input.area() <= remaining + 0.000001) {
                selected[index] = true; remaining -= input.area();
            }
        }
        for (int index = 0; index < nearestFirst.size(); index++) {
            Projected input = nearestFirst.get(index);
            // 烟外实际用不完的额度还给烟内，只有自己一团烟时不无故降低表现。
            if (!selected[index] && input.segment().contains(camera) && input.area() <= remaining + 0.000001) {
                selected[index] = true; remaining -= input.area();
            }
        }
        List<Projected> result = new ArrayList<>();
        for (int index = nearestFirst.size() - 1; index >= 0; index--) {
            if (selected[index]) { result.add(nearestFirst.get(index)); }
        }
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
