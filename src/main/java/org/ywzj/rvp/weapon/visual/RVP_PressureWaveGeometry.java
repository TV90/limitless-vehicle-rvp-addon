package org.ywzj.rvp.weapon.visual;

import org.joml.Matrix4fc;
import org.joml.Vector4f;

/** 空气层的保守屏幕范围计算；只用数学类型，专用服务器也可安全加载。 */
public final class RVP_PressureWaveGeometry {
    private RVP_PressureWaveGeometry() { }

    /**
     * 球体屏幕包围框（标准化坐标 -1～1）；可比实际球体略大，不得漏掉透视轮廓。
     * @param left 左侧
     * @param bottom 下侧
     * @param right 右侧
     * @param top 上侧
     */
    public record Bounds(float left, float bottom, float right, float top) { }

    /** 用包住球体的八个立方体角点求范围；穿过近裁剪面时保守覆盖全屏。 */
    public static Bounds screenBounds(Matrix4fc projection, float x, float y, float z, float radius) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                || !Float.isFinite(radius) || radius <= 0 || z - radius >= 0) {
            return null;
        }
        if (z + radius >= -0.05F) { return new Bounds(-1, -1, 1, 1); }
        float left = Float.POSITIVE_INFINITY;
        float bottom = Float.POSITIVE_INFINITY;
        float right = Float.NEGATIVE_INFINITY;
        float top = Float.NEGATIVE_INFINITY;
        Vector4f corner = new Vector4f();
        for (int index = 0; index < 8; index++) {
            corner.set(x + ((index & 1) == 0 ? -radius : radius),
                    y + ((index & 2) == 0 ? -radius : radius),
                    z + ((index & 4) == 0 ? -radius : radius), 1).mul(projection);
            if (corner.w <= 0.00001F) { return new Bounds(-1, -1, 1, 1); }
            float screenX = corner.x / corner.w;
            float screenY = corner.y / corner.w;
            left = Math.min(left, screenX);
            bottom = Math.min(bottom, screenY);
            right = Math.max(right, screenX);
            top = Math.max(top, screenY);
        }
        left = Math.max(-1, left);
        bottom = Math.max(-1, bottom);
        right = Math.min(1, right);
        top = Math.min(1, top);
        return left < right && bottom < top ? new Bounds(left, bottom, right, top) : null;
    }
}
