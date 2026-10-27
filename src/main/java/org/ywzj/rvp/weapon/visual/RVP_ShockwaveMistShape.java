package org.ywzj.rvp.weapon.visual;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** 分散云团的稳定随机分布及贴地裁切，不依赖客户端，供预览与实弹共同使用。 */
public final class RVP_ShockwaveMistShape {
    private RVP_ShockwaveMistShape() { }

    /**
     * 每个云团固定的随机参数，不逐帧重抽，避免闪烁。
     * @param x 方向X
     * @param y 方向Y；地爆仅取上半球
     * @param z 方向Z
     * @param depth 在云层内的深度，0～1
     * @param size 尺寸随机倍率
     * @param opacity 浓度随机倍率
     * @param spin 贴图面内旋转角（弧度）
     */
    public record Puff(double x, double y, double z, float depth, float size, float opacity, float spin) {
        /** 取得云团中心所在半径比例，让同一方向的云呈厚层散布而非紧贴球面。 */
        public float radialFactor(float thickness) {
            return 1 - Math.max(0, Math.min(1, thickness)) * (0.15F + depth * 0.85F);
        }
    }

    /** 创建有数量上限的云团分布；地爆不生成地下半球，也不把它折叠成贴地圆环。 */
    public static List<Puff> create(long seed, int count, boolean hemisphere) {
        Random random = new Random(seed);
        List<Puff> result = new ArrayList<>();
        int boundedCount = Math.max(0, Math.min(512, count));
        for (int index = 0; index < boundedCount; index++) {
            double y = hemisphere ? random.nextDouble() : random.nextDouble() * 2 - 1;
            double angle = random.nextDouble() * Math.PI * 2;
            double horizontal = Math.sqrt(Math.max(0, 1 - y * y));
            result.add(new Puff(Math.cos(angle) * horizontal, y, Math.sin(angle) * horizontal,
                    random.nextFloat(), 0.65F + random.nextFloat() * 0.7F,
                    0.45F + random.nextFloat() * 0.55F, (float) (random.nextDouble() * Math.PI * 2)));
        }
        return List.copyOf(result);
    }

    /** @param x 世界X @param y 世界Y @param z 世界Z @param u 纹理横坐标 @param v 纹理纵坐标 */
    public record Vertex(double x, double y, double z, float u, float v) { }

    /** 将云片多边形裁到地表之上，边界插值纹理坐标，禁止通过抬高顶点制造规则贴地圆环。 */
    public static List<Vertex> clipAbove(List<Vertex> polygon, double groundY) {
        if (polygon.isEmpty() || !Double.isFinite(groundY)) { return List.of(); }
        List<Vertex> clipped = new ArrayList<>(6);
        Vertex previous = polygon.get(polygon.size() - 1);
        for (Vertex current : polygon) {
            boolean before = previous.y() >= groundY;
            boolean after = current.y() >= groundY;
            if (before != after) {
                double t = (groundY - previous.y()) / (current.y() - previous.y());
                clipped.add(new Vertex(previous.x() + (current.x() - previous.x()) * t, groundY,
                        previous.z() + (current.z() - previous.z()) * t,
                        (float) (previous.u() + (current.u() - previous.u()) * t),
                        (float) (previous.v() + (current.v() - previous.v()) * t)));
            }
            if (after) { clipped.add(current); }
            previous = current;
        }
        return List.copyOf(clipped);
    }
}
