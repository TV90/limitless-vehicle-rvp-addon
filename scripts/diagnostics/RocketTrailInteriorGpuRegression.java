package org.ywzj.rvp.client.light;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.ywzj.rvp.weapon.visual.RVP_RocketTrailParameters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 真实 Java 绘制选择 + 正式 GLSL：眼睛在多团烟里时，烟外的另一团烟仍能影响最终像素。 */
public final class RocketTrailInteriorGpuRegression {
    /** 与独立像素回读一致的测试分辨率。 */
    private static final int SIZE = 64;
    /** 原场景颜色，保留非一 Alpha 检查多团烟叠加是否破坏背景通道。 */
    private static final float[] BACKGROUND = {0.1F, 0.12F, 0.16F, 0.37F};

    private RocketTrailInteriorGpuRegression() { }

    public static void main(String[] args) throws Exception {
        // 调用既有隐藏显卡入口，共用真实程序编译、基础遮挡检查和显存清理，不另外模拟 Shader。
        RocketTrailVolumeGpuRegression.afterVolumeChecks = () -> {
            // 调用常规烟内外场景，再调用扩大预算后的密集整屏场景，使用同一个正式程序。
            testInteriorScene();
            testExpandedFullscreenScene();
        };
        try { RocketTrailVolumeGpuRegression.main(args); }
        finally { RocketTrailVolumeGpuRegression.afterVolumeChecks = null; }
    }

    /** 旧算法会先选三团整屏近烟并丢掉远烟；本回归直接运行生产选择器并比较最终显卡像素。 */
    private static void testInteriorScene() {
        Vec3 camera = Vec3.ZERO;
        Matrix4f view = new Matrix4f();
        Matrix4f projection = new Matrix4f().perspective((float) Math.PI / 2, 1, 0.1F, 10000);
        var distant = new RVP_RocketTrailSegment(99, new Vec3(-2, 0, -8), new Vec3(2, 0, -8),
                0.8F, 0.8F, 1.2F, 1.2F, 20, 0.3F, 0.85F, 0.7F);
        List<RVP_RocketTrailSegment> scene = new ArrayList<>();
        scene.add(distant);
        for (int index = 0; index < 4; index++) {
            Vec3 center = new Vec3(index * 0.05, 0, 0);
            scene.add(new RVP_RocketTrailSegment(index, center, center,
                    1.5F, 1.5F, 0.18F, 0.18F, 80, index * 0.1F, 0.35F, 0.7F));
        }
        scene.sort(Comparator.comparingDouble((RVP_RocketTrailSegment segment) ->
                segment.center().distanceToSqr(camera)).reversed());
        // 调用正式投影与预算，不用测试内“永远保留远烟”的替代算法。
        var selected = RVP_RocketTrailGeometry.budget(scene, camera,
                segment -> RVP_RocketTrailGeometry.bounds(segment, view, projection, camera));
        require(selected.stream().anyMatch(input -> input.segment() == distant), "production selection lost exterior smoke");
        require(selected.stream().anyMatch(input -> input.segment().contains(camera)), "production selection lost interior smoke");
        require(selected.stream().mapToDouble(RVP_RocketTrailGeometry.Projected::area).sum()
                <= RVP_RocketTrailParameters.MAX_SCREEN_COVERAGE + 0.000001, "screen budget exceeded");
        int output = RocketTrailVolumeGpuRegression.target(SIZE, SIZE);
        int sky = RocketTrailVolumeGpuRegression.depth(SIZE, 0, false);
        // 调用同一组被生产算法选中的近烟，只在第二次加入远烟，排除近烟数量不同造成的误判。
        float[] withoutExterior = render(selected.stream().filter(input -> input.segment() != distant).toList(), output, sky);
        float[] withExterior = render(selected, output, sky);
        require(withExterior[0] > withoutExterior[0] + 0.02F,
                "exterior volume disappeared from the final pixel while the camera was inside smoke");
        require(Math.abs(withExterior[3] - BACKGROUND[3]) < 0.002F, "background alpha was changed");
        require(!GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK), "smoke became a solid depth occluder");
        System.out.println("PASS production camera-inside selection and GLSL composition: outside-smoke red contribution="
                + (withExterior[0] - withoutExterior[0]) + "; selected volumes=" + selected.size());
    }

    /** 调用生产投影/选择和正式程序，验证扩大的 32 屏份额可实际画出 24 团烟外与 8 团烟内体积。 */
    private static void testExpandedFullscreenScene() {
        Matrix4f view = new Matrix4f();
        Matrix4f projection = new Matrix4f().perspective((float) Math.PI / 2, 1, 0.1F, 10000);
        List<RVP_RocketTrailSegment> scene = new ArrayList<>();
        int exteriorLimit = (int) RVP_RocketTrailParameters.EXTERIOR_SCREEN_RESERVE;
        int totalLimit = (int) RVP_RocketTrailParameters.MAX_SCREEN_COVERAGE;
        for (int index = 0; index < exteriorLimit + 8; index++) {
            Vec3 center = new Vec3(0, 0, -7 - index * 0.1);
            scene.add(new RVP_RocketTrailSegment(index + 100, center, center, 6, 6,
                    0.02F, 0.02F, 20, 0.3F, 0.65F, 0.7F));
        }
        for (int index = 0; index < totalLimit + 8; index++) {
            scene.add(new RVP_RocketTrailSegment(index, Vec3.ZERO, Vec3.ZERO, 1.5F, 1.5F,
                    0.01F, 0.01F, 20, 0.3F, 0.65F, 0.7F));
        }
        scene.sort(Comparator.comparingDouble((RVP_RocketTrailSegment segment) ->
                segment.center().distanceToSqr(Vec3.ZERO)).reversed());
        // 调用正式预算，不能仅检查常量已变而未验证实际参与绘制的体积数量。
        var selected = RVP_RocketTrailGeometry.budget(scene, Vec3.ZERO,
                segment -> RVP_RocketTrailGeometry.bounds(segment, view, projection, Vec3.ZERO));
        require(selected.size() == totalLimit, "expanded total fullscreen budget was not used");
        require(selected.stream().filter(input -> !input.segment().contains(Vec3.ZERO)).count() == exteriorLimit,
                "expanded exterior fullscreen reservation was not used");
        int output = RocketTrailVolumeGpuRegression.target(SIZE, SIZE);
        int sky = RocketTrailVolumeGpuRegression.depth(SIZE, 0, false);
        // 调用同一正式体积程序连续绘制实际选中的烟，检查颜色、Alpha 和有限数值。
        float[] combined = render(selected, output, sky);
        require(combined[0] > BACKGROUND[0] + 0.02F, "expanded volume scene produced no visible smoke");
        require(Math.abs(combined[3] - BACKGROUND[3]) < 0.002F, "expanded volume scene changed background alpha");
        System.out.println("PASS expanded production fullscreen volumes: total=" + selected.size()
                + ", exterior=" + exteriorLimit + ", interior=" + (totalLimit - exteriorLimit));
    }

    /** 上传正式烟段快照，按生产返回顺序执行正式体积程序；不写测试用烟雾图片。 */
    private static float[] render(List<RVP_RocketTrailGeometry.Projected> inputs, int output, int sky) {
        RocketTrailVolumeGpuRegression.base(SIZE, SIZE, sky);
        RocketTrailVolumeGpuRegression.clear(output, SIZE, SIZE, BACKGROUND);
        RocketTrailVolumeGpuRegression.premultipliedBlend();
        for (var input : inputs) {
            RVP_RocketTrailSegment segment = input.segment();
            // 调用与生产入口相同的面积降采样参数；整屏烟用八步，不能只验证二十四步的理想输入。
            RocketTrailVolumeGpuRegression.uniform1i("RaySteps", Math.max(8,
                    RVP_RocketTrailParameters.RAY_STEPS - (int) (input.area() * 16)));
            RocketTrailVolumeGpuRegression.uniform4("TrailStart", (float) segment.start().x, (float) segment.start().y,
                    (float) segment.start().z, segment.startRadius());
            RocketTrailVolumeGpuRegression.uniform4("TrailEnd", (float) segment.end().x, (float) segment.end().y,
                    (float) segment.end().z, segment.endRadius());
            RocketTrailVolumeGpuRegression.uniform2("TrailDensity", segment.startDensity(), segment.endDensity());
            RocketTrailVolumeGpuRegression.uniform4("TrailColor", segment.grey(), segment.age(), segment.seed(), segment.noiseScale());
            RocketTrailVolumeGpuRegression.uniform3("TrailNoiseOrigin", (float) ((segment.start().x * segment.noiseScale()) % 4096),
                    (float) ((segment.start().y * segment.noiseScale()) % 4096),
                    (float) ((segment.start().z * segment.noiseScale()) % 4096));
            // 调用正式 GLSL；仍读取相同不透明深度，不把前一团烟作为下一团烟的墙。
            RocketTrailVolumeGpuRegression.draw();
        }
        return RocketTrailVolumeGpuRegression.pixel(output, 32, 32);
    }

    /** 失败必须终止整个显卡回归，不能跳过后仍报告成功。 */
    private static void require(boolean value, String message) { if (!value) { throw new AssertionError(message); } }
}
