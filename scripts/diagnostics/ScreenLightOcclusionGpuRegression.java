package org.ywzj.rvp.client.light;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.joml.Matrix4f;
import org.lwjgl.opengl.*;
import org.ywzj.rvp.light.RVP_LightParameters;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** 游戏原生 Shader 加载器、实际 Java 分层构建和正式遮挡核心的组合回归，全部资源只读。 */
final class ScreenLightOcclusionGpuRegression {
    /** 本回归创建的纹理；仅释放显存对象，不删除磁盘文件。 */
    private static final List<Integer> TEXTURES = new ArrayList<>();

    private ScreenLightOcclusionGpuRegression() { }

    /** 由已初始化 Minecraft 渲染线程的隐藏显卡入口调用。 */
    static void run(ResourceProvider resources) throws Exception {
        try (ShaderInstance shader = new ShaderInstance(resources,
                ResourceLocation.fromNamespaceAndPath("ywzj_rvp", "screen_light_occlusion"), DefaultVertexFormat.POSITION);
             ShaderInstance smoke = new ShaderInstance(resources,
                ResourceLocation.fromNamespaceAndPath("ywzj_rvp", "smoke_light_occlusion"), DefaultVertexFormat.POSITION_TEX_COLOR)) {
            require(shader.getUniform("OcclusionReady") != null && smoke.getUniform("OcclusionReady") != null,
                    "Minecraft did not load the namespaced shared include");
            // 调用真实上传作用域，外部 PBO/错行/反字节序不能污染新几何纹理。
            checkGeometryUnpackState(shader);
            int cases = 0;
            // 调用三类正式预设；每个都覆盖左右、薄条、奇数窗口、近景和正/反 DH。
            for (RVP_LightParameters preset : new RVP_LightParameters[] {
                    RVP_LightParameters.explosion(4), RVP_LightParameters.muzzle(125, false), RVP_LightParameters.engine()}) {
                for (int width : new int[] {257, 512}) {
                    for (boolean mirror : new boolean[] {false, true}) {
                        for (int thickness : new int[] {1, 2, 4}) {
                            for (int dh : new int[] {0, 1, 2, 3}) {
                                // 调用直接几何参考比较真实生产层级，避免只验收阴影条数。
                                checkScene(shader, preset, width, 31, thickness, mirror, dh);
                                cases++;
                            }
                        }
                    }
                }
            }
            System.out.println("PASS " + cases + " production hierarchy scenes: explosion/muzzle/engine, both sides, 1/2/4px strips, odd windows, near/DH forward/reverse, no budget exhaustion");
            // 调用多灯、显式中止状态及第四纹理单元恢复专项，覆盖非几何数值的合同。
            checkMultipleAndStates(shader);
            // 调用真实立体箱体＋地面复现，不能只靠一张正对相机的深度条验收实机阴影。
            for (RVP_LightParameters preset : new RVP_LightParameters[] {
                    RVP_LightParameters.explosion(4), RVP_LightParameters.muzzle(125, false), RVP_LightParameters.engine()}) {
                checkSolidBoxScene(shader, preset, false);
                checkSolidBoxScene(shader, preset, true);
            }
            benchmark(shader, 1);
            benchmark(shader, 8);
            benchmarkGeometry(shader, 1);
            benchmarkGeometry(shader, 8);
        } finally {
            RVP_LightOcclusionPass.release();
            RVP_LightGeometryPass.release();
            for (int texture : TEXTURES) { GL11.glDeleteTextures(texture); }
            TEXTURES.clear();
        }
    }

    /** 对比箱体完整几何与单层相机深度；仅诊断输出，不把失败图案当作已经修好的结果。 */
    private static void checkSolidBoxScene(ShaderInstance shader, RVP_LightParameters preset, boolean mirror) throws Exception {
        int width = 512, height = 512;
        float lightX = mirror ? 2.2F : -2.2F;
        Matrix4f projection = new Matrix4f().perspective((float) Math.PI / 2, 1, .1F, 100);
        float[] plane = new float[width * height], scene = new float[width * height];
        boolean[] floor = new boolean[width * height], expected = new boolean[width * height];
        for (int y = 0; y < height; y++) { for (int x = 0; x < width; x++) {
            int at = y * width + x;
            double dx = (x + .5) / width * 2 - 1, dy = (y + .5) / height * 2 - 1;
            double ground = dy < 0 ? -2 / dy : Double.POSITIVE_INFINITY;
            double box = boxIntersection(0, 0, 0, dx, dy, -1, 100);
            plane[at] = ground < 90 ? (float)((-projection.m22() + projection.m32() / ground) * .5 + .5) : 1;
            double nearest = Math.min(ground, box);
            scene[at] = nearest < 90 ? (float)((-projection.m22() + projection.m32() / nearest) * .5 + .5) : 1;
            floor[at] = ground < box && ground < 90;
            if (floor[at]) {
                double px = ground * dx, pz = -ground;
                // 调用完整三维箱体求交，地面起点同样向上偏移 0.08 格，避免混入自遮挡误差。
                expected[at] = Double.isFinite(boxIntersection(px, -1.92, pz, lightX - px, 2.92, -3 - pz, 1));
            }
        } }
        int planeDepth = texture(width, height, true, plane), sceneDepth = texture(width, height, true, scene);
        int color = texture(width, height, false, new float[width * height * 4]);
        int world = GL30.glGenFramebuffers();
        RVP_ScreenFrameResources frame = new RVP_ScreenFrameResources();
        try (RVP_ScreenFrameResources.State ignored = new RVP_ScreenFrameResources.State()) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, world);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, color, 0);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0); GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            float[][] images = new float[4][];
            for (int pass = 0; pass < 4; pass++) {
                require(frame.captureDepth(world, pass == 0 ? planeDepth : sceneDepth, width, height), "solid-box capture");
                require(frame.captureColor(world, width, height), "solid-box color");
                configure(shader, frame, projection, null, false);
                lights(shader, preset, lightX, 1);
                shader.safeGetUniform("LightPositionRadius0").set(lightX, 1F, -3F, preset.radius());
                RVP_LightOcclusionPass.prepare(frame, shader);
                if (pass == 0) { shader.safeGetUniform("GeometryMode").set(2); }
                if (pass >= 2) {
                    // 调用正式三维路径：同一个完整箱体分别编码为方块碰撞并集或实体盒。
                    installBoxGeometry(shader, pass == 3);
                }
                images[pass] = draw(shader, frame, world, 1);
            }
            java.awt.image.BufferedImage preview = new java.awt.image.BufferedImage(width * 4, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
            for (int pass = 1; pass < 4; pass++) {
                int falseShadow = 0, missingShadow = 0;
                boolean[] mask = new boolean[width * height];
                for (int y = 0; y < height; y++) { for (int x = 0; x < width; x++) {
                    int at = y * width + x;
                    boolean eligible = floor[at] && images[0][at * 4] > .01F;
                    // 排除紧贴轮廓时法线本身不确定的一圈像素，两套几何参考采用相同范围。
                    if (x > 0 && x + 1 < width && y > 0 && y + 1 < height) {
                        eligible &= floor[at - 1] && floor[at + 1] && floor[at - width] && floor[at + width];
                    }
                    boolean actual = eligible && images[pass][at * 4] < images[0][at * 4] * .1F;
                    mask[at] = actual;
                    if (eligible && actual && !expected[at]) { falseShadow++; }
                    if (eligible && !actual && expected[at]) { missingShadow++; }
                    preview.setRGB((pass - 1) * width + x, height - 1 - y, !eligible ? 0x303030 : actual ? 0x050505 : 0xffb050);
                    preview.setRGB(width * 3 + x, height - 1 - y, !eligible ? 0x303030 : expected[at] ? 0x050505 : 0xffb050);
                } }
                int components = components(mask, width, height);
                System.out.println("SOLID-BOX radius=" + preset.radius() + " mirror=" + mirror + " mode=" + pass + " false=" + falseShadow + " missing=" + missingShadow + " shadowComponents=" + components);
                if (pass >= 2) {
                    require(falseShadow + missingShadow <= 32 && components == 1, "3D block/entity geometry does not produce one continuous correct shadow");
                }
            }
            if (!mirror && preset.radius() == 20F) {
                for (boolean entity : new boolean[] {false, true}) {
                    installBoxGeometry(shader, entity);
                    float[] slotReference = images[entity ? 3 : 2];
                    if (entity) {
                        // 交换局部Y/Z半长再旋转90°，世界盒保持不变，检验真实载具旋转数据的解释。
                        float[] rotated = new float[528];
                        rotated[1] = -1.05F; rotated[2] = -6.4F; rotated[3] = .6F; rotated[4] = .6F; rotated[5] = .95F;
                        rotated[6] = (float) Math.sqrt(.5); rotated[9] = (float) Math.sqrt(.5); rotated[10] = 255;
                        shader.safeGetUniform("GeometryBodies").set(rotated);
                        lights(shader, preset, lightX, 1);
                        shader.safeGetUniform("LightPositionRadius0").set(lightX, 1F, -3F, preset.radius());
                        slotReference = draw(shader, frame, world, 1);
                        int boundaryDifferences = 0;
                        for (int pixel = 0; pixel < slotReference.length; pixel += 4) {
                            if (Math.abs(slotReference[pixel] - images[3][pixel]) >= .002F) { boundaryDifferences++; }
                        }
                        // 浮点四元数在恰好相切的边界允许少量像素差；八槽位仍须逐像素一致，不能放宽错槽判定。
                        require(boundaryDifferences <= 32, "rotated entity changed the physical shadow: " + boundaryDifferences);
                        System.out.println("ROTATED-BOX tangency boundary pixels=" + boundaryDifferences);
                    }
                    for (int active = 0; active < 8; active++) {
                        lights(shader, preset, lightX, active + 1);
                        for (int slot = 0; slot < 8; slot++) {
                            shader.safeGetUniform("LightPositionRadius" + slot).set(lightX, 1F, -3F, preset.radius());
                            shader.safeGetUniform("LightColorIntensity" + slot).set(preset.red(), preset.green(), preset.blue(), slot == active ? preset.intensity() : 0F);
                        }
                        float[] slotImage = draw(shader, frame, world, 1);
                        float[] referenceImage = slotReference;
                        for (int index = 0; index < slotImage.length; index++) {
                            require(Math.abs(slotImage[index] - referenceImage[index]) < .002F,
                                    "geometry atlas/body mask wrong: entity=" + entity + " slot=" + active + " scalar=" + index
                                            + " actual=" + slotImage[index] + " reference=" + referenceImage[index]);
                        }
                    }
                }
                System.out.println("PASS all eight geometry atlas tiles, entity light masks and rotated body boxes match the same physical scene");
            }
            java.nio.file.Path output = java.nio.file.Path.of("build/light-occlusion-solid-box");
            java.nio.file.Files.createDirectories(output);
            java.nio.file.Path png = output.resolve("solid-box-" + System.nanoTime() + ".png");
            javax.imageio.ImageIO.write(preview, "png", png.toFile());
            System.out.println("SOLID-BOX preview: old camera depth / true blocks / true entity / geometry reference = " + png.toAbsolutePath());
        } finally { frame.release(); GL30.glDeleteFramebuffers(world); }
    }

    /** 外部上传状态下执行生产分配/作用域，并回读整数纹理值，防止切片数据错行导致新的重复图案。 */
    private static void checkGeometryUnpackState(ShaderInstance shader) throws Exception {
        int[] parameters = {GL11.GL_UNPACK_ALIGNMENT, GL11.GL_UNPACK_ROW_LENGTH, GL11.GL_UNPACK_SKIP_PIXELS,
                GL11.GL_UNPACK_SKIP_ROWS, GL12.GL_UNPACK_IMAGE_HEIGHT, GL12.GL_UNPACK_SKIP_IMAGES,
                GL11.GL_UNPACK_SWAP_BYTES, GL11.GL_UNPACK_LSB_FIRST};
        int[] previous = new int[parameters.length];
        for (int index = 0; index < parameters.length; index++) { previous[index] = GL11.glGetInteger(parameters[index]); }
        int previousBuffer = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        int pbo = GL15.glGenBuffers(), target = GL30.glGenFramebuffers();
        try (RVP_ScreenFrameResources.State ignored = new RVP_ScreenFrameResources.State()) {
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, pbo);
            GL15.glBufferData(GL21.GL_PIXEL_UNPACK_BUFFER, 32L, GL15.GL_STREAM_DRAW);
            int[] alien = {8, 13, 3, 2, 7, 1, 1, 1};
            for (int index = 0; index < parameters.length; index++) { GL11.glPixelStorei(parameters[index], alien[index]); }
            RVP_LightGeometryPass.upload(shader);
            shader.apply();
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "geometry allocation interpreted PBO offset as Java data");
            var constructor = Class.forName("org.ywzj.rvp.client.light.RVP_LightGeometryPass$UnpackState").getDeclaredConstructor();
            constructor.setAccessible(true);
            try (AutoCloseable upload = (AutoCloseable) constructor.newInstance()) {
                GL13.glActiveTexture(GL13.GL_TEXTURE4);
                int texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 1, 1, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_SHORT, new short[] {311});
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target);
                GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
                GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
                short[] actual = new short[1];
                GL11.glReadPixels(0, 0, 1, 1, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_SHORT, actual);
                require(actual[0] == 311, "geometry upload byte order/row stride corrupted shape index");
            }
            require(GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING) == pbo, "geometry upload lost external PBO");
            for (int index = 0; index < parameters.length; index++) {
                require(GL11.glGetInteger(parameters[index]) == alien[index], "geometry upload lost external pixel parameter");
            }
            // 与正式 draw 相同地成对结束 apply，避免诊断自己留下 ShaderInstance 的当前程序缓存。
            shader.clear();
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "geometry upload/readback GL error");
            System.out.println("PASS real geometry upload under external PBO, row strides, skip regions and byte swap; integer value and all caller state restored");
        } finally {
            for (int index = 0; index < parameters.length; index++) { GL11.glPixelStorei(parameters[index], previous[index]); }
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, previousBuffer); GL15.glDeleteBuffers(pbo); GL30.glDeleteFramebuffers(target);
        }
    }

    /** 实际着色程序使用的三维输入；完整箱体被切为八个方块局部盒，独立几何参考仍用单个整体盒。 */
    private static void installBoxGeometry(ShaderInstance shader, boolean entity) {
        int size = 64, tile = 512, paletteWidth = 4096;
        short[] volume = new short[tile * tile];
        float[] palette = new float[paletteWidth * 8];
        palette[7] = 1;
        palette[(paletteWidth + 1) * 4] = 1; palette[(paletteWidth + 1) * 4 + 1] = 1; palette[(paletteWidth + 1) * 4 + 2] = 1;
        for (int z = 0; z < size; z++) { for (int x = 0; x < size; x++) {
            // 调用生产布局编码，保证真正上传的数据与着色器切片寻址配套。
            volume[RVP_LightGeometryPass.atlasIndex(x, 29, z)] = 1;
        } }
        int next = 2;
        if (!entity) {
            for (int y = -2; y < 0; y++) { for (int z = -7; z < -5; z++) { for (int x = -1; x < 1; x++) {
                int at = next * 4, end = (paletteWidth + next) * 4;
                palette[at] = (float) Math.max(0, -.6 - x); palette[at + 1] = (float) Math.max(0, -2 - y); palette[at + 2] = (float) Math.max(0, -7 - z); palette[at + 3] = 1;
                palette[end] = (float) Math.min(1, .6 - x); palette[end + 1] = (float) Math.min(1, -.1 - y); palette[end + 2] = (float) Math.min(1, -5.8 - z);
                volume[RVP_LightGeometryPass.atlasIndex(x + 32, y + 32, z + 32)] = (short) next++;
            } } }
        }
        int cells = GL11.glGenTextures(); TEXTURES.add(cells);
        GL13.glActiveTexture(GL13.GL_TEXTURE4); GL11.glBindTexture(GL11.GL_TEXTURE_2D, cells);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        short[] packed = new short[tile * 4 * tile * 2];
        for (int slot = 0; slot < 8; slot++) { for (int y = 0; y < tile; y++) {
            System.arraycopy(volume, y * tile, packed, (y + (slot >> 2) * tile) * tile * 4 + (slot & 3) * tile, tile);
        } }
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R16UI, tile * 4, tile * 2, 0, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_SHORT, packed);
        int shapes = GL11.glGenTextures(); TEXTURES.add(shapes);
        GL13.glActiveTexture(GL13.GL_TEXTURE5); GL11.glBindTexture(GL11.GL_TEXTURE_2D, shapes);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, paletteWidth, 2, 0, GL11.GL_RGBA, GL11.GL_FLOAT, palette);
        float[] origins = new float[32];
        for (int index = 0; index < 8; index++) { origins[index * 4] = -32; origins[index * 4 + 1] = -32; origins[index * 4 + 2] = -32; origins[index * 4 + 3] = index + 1; }
        float[] bodies = new float[528];
        bodies[1] = -1.05F; bodies[2] = -6.4F; bodies[3] = .6F; bodies[4] = .95F; bodies[5] = .6F; bodies[9] = 1; bodies[10] = 255;
        shader.setSampler("GeometryCells", cells); shader.setSampler("GeometryPalette", shapes);
        shader.safeGetUniform("GeometryMode").set(1); shader.safeGetUniform("GeometryOrigins").set(origins);
        shader.safeGetUniform("GeometryBodies").set(bodies); shader.safeGetUniform("GeometryBodyCount").set(entity ? 1 : 0);
        shader.safeGetUniform("GeometryViewToWorld").set(new Matrix4f());
    }

    /** 统计四连通阴影区，直接防止一个完整遮挡体被切成数个副本；单像素噪点也会被计入。 */
    private static int components(boolean[] mask, int width, int height) {
        boolean[] visited = new boolean[mask.length]; int[] queue = new int[mask.length]; int count = 0;
        for (int at = 0; at < mask.length; at++) {
            if (!mask[at] || visited[at]) { continue; }
            count++; int read = 0, write = 1; queue[0] = at; visited[at] = true;
            while (read < write) {
                int p = queue[read++], x = p % width, y = p / width;
                int[] neighbors = {x > 0 ? p - 1 : -1, x + 1 < width ? p + 1 : -1, y > 0 ? p - width : -1, y + 1 < height ? p + width : -1};
                for (int next : neighbors) { if (next >= 0 && mask[next] && !visited[next]) { visited[next] = true; queue[write++] = next; } }
            }
        }
        return count;
    }

    /** 与完整立体箱体求线段交点；箱体贴在 y=-2 的地面上，前后/顶面均参与几何参考。 */
    private static double boxIntersection(double x, double y, double z, double dx, double dy, double dz, double maximum) {
        double[] origin = {x, y, z}, direction = {dx, dy, dz};
        double[] minimum = {-.6, -2, -7}, upper = {.6, -.1, -5.8};
        double enter = 0, exit = maximum;
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(direction[axis]) < 1e-10) {
                if (origin[axis] < minimum[axis] || origin[axis] > upper[axis]) { return Double.POSITIVE_INFINITY; }
            } else {
                double a = (minimum[axis] - origin[axis]) / direction[axis], b = (upper[axis] - origin[axis]) / direction[axis];
                enter = Math.max(enter, Math.min(a, b)); exit = Math.min(exit, Math.max(a, b));
                if (enter > exit) { return Double.POSITIVE_INFINITY; }
            }
        }
        return enter <= maximum ? enter : Double.POSITIVE_INFINITY;
    }

    /** 每个场景用真实深度捕获/分层构建，按几何求交核验受光平面的每个横向像素。 */
    private static void checkScene(ShaderInstance shader, RVP_LightParameters preset, int width, int height,
                                   int stripWidth, boolean mirror, int dh) {
        int left = width / 2 - 6;
        if (mirror) { left = width - left - stripWidth; }
        float lightX = mirror ? -1.5F : 1.5F;
        int color = texture(width, height, false, new float[width * height * 4]);
        float[] depths = new float[width * height];
        Matrix4f near = new Matrix4f().perspective((float) Math.PI / 2, 1, .1F, 100);
        Matrix4f distant = new Matrix4f().perspective((float) Math.PI / 2, 1, .5F, 512);
        boolean distantScene = dh == 1 || dh == 2;
        if (dh == 3) { near.m22(-near.m22()).m32(-near.m32()); }
        if (dh == 2) { distant.m22(-distant.m22()).m32(-distant.m32()); }
        for (int y = 0; y < height; y++) { for (int x = 0; x < width; x++) {
            float distance = x >= left && x < left + stripWidth ? 4 : 10;
            Matrix4f projection = distantScene ? distant : near;
            depths[y * width + x] = (-projection.m22() + projection.m32() / distance) * .5F + .5F;
        } }
        int geometry = texture(width, height, true, depths);
        float[] empty = new float[width * height]; Arrays.fill(empty, 1);
        int sky = texture(width, height, true, empty);
        int world = GL30.glGenFramebuffers();
        RVP_ScreenFrameResources frame = new RVP_ScreenFrameResources();
        try (RVP_ScreenFrameResources.State ignored = new RVP_ScreenFrameResources.State()) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, world);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, color, 0);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0); GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            // 调用生产近景/DH 副本，不能只把诊断纹理直接当作生产已经捕获。
            require(frame.captureDepth(world, distantScene ? sky : geometry, width, height), "near capture");
            require(frame.captureColor(world, width, height), "color capture");
            if (distantScene) { require(frame.captureDistantDepth(geometry), "DH capture"); }
            configure(shader, frame, near, distantScene ? distant : null, dh == 2);
            shader.safeGetUniform("NearEmptyDepth").set(dh == 3 ? 0F : 1F);
            lights(shader, preset, lightX, 1);
            RVP_LightOcclusionPass.beginFrame();
            RVP_LightOcclusionPass.prepare(frame, shader);
            require(RVP_LightOcclusionPass.isReady(), "hierarchy silently fell back");
            // 调用实际表面程序，单灯阴影与直接线段/平面求交比较。
            float[] result = draw(shader, frame, world, 1);
            double t = (10 - .08 - 4) / (10 - .08 - 2);
            double minimum = (2.0 * left / width - 1) * 4;
            double maximum = (2.0 * (left + stripWidth) / width - 1) * 4;
            int mismatches = 0;
            int actualShadows = 0;
            StringBuilder expectedColumns = new StringBuilder();
            StringBuilder actualColumns = new StringBuilder();
            for (int x = 0; x < width; x++) {
                if (x >= left - 2 && x < left + stripWidth + 2) { continue; }
                double surfaceX = ((x + .5) / width * 2 - 1) * 10;
                if (Math.hypot(surfaceX - lightX, 8) >= preset.radius() * .99) { continue; }
                double intersection = surfaceX * (1 - t) + lightX * t;
                boolean expected = intersection >= minimum && intersection < maximum;
                boolean actual = result[((height / 2) * width + x) * 4] < .00001F;
                if (expected) { expectedColumns.append(x).append(','); }
                if (actual) { actualColumns.append(x).append(','); }
                if (actual) { actualShadows++; }
                if (expected != actual) { mismatches++; }
            }
            if (mismatches > 2) {
                System.out.println("EXPECTED columns=" + expectedColumns + " ACTUAL columns=" + actualColumns);
                shader.safeGetUniform("OcclusionReady").set(0);
                shader.safeGetUniform("OcclusionMaxSteps").set(1024);
                float[] direct = draw(shader, frame, world, 1);
                StringBuilder directColumns = new StringBuilder();
                for (int x = 0; x < width; x++) {
                    if (direct[((height / 2) * width + x) * 4] < .00001F && (x < left - 2 || x >= left + stripWidth + 2)) {
                        directColumns.append(x).append(',');
                    }
                }
                System.out.println("DIRECT traversal columns=" + directColumns);
            }
            require(actualShadows > 0 && mismatches <= 2, "geometry mismatch=" + mismatches + " shadows=" + actualShadows
                    + " width=" + width + " strip=" + stripWidth + " mirror=" + mirror + " DH=" + dh);
            float[] stats = draw(shader, frame, world, 4);
            for (int i = 0; i < stats.length; i += 4) {
                require(Float.isFinite(stats[i]) && stats[i + 2] == 0, "standard geometry exhausted query budget");
            }
        } finally {
            frame.release(); GL30.glDeleteFramebuffers(world);
        }
    }

    /** 检查多灯线性贡献、预算耗尽/离屏的区别及真实作用域的第四采样器恢复。 */
    private static void checkMultipleAndStates(ShaderInstance shader) {
        int width = 257, height = 33;
        int output = texture(width, height, false, new float[width * height * 4]);
        Matrix4f projection = new Matrix4f().perspective((float) Math.PI / 2, 1, .1F, 100);
        float[] depthValues = new float[width * height];
        Arrays.fill(depthValues, (-projection.m22() + projection.m32() / 10) * .5F + .5F);
        for (int y = 0; y < height; y++) { for (int x = width / 2 - 2; x <= width / 2 + 2; x++) {
            depthValues[y * width + x] = (-projection.m22() + projection.m32() / 4) * .5F + .5F;
        } }
        int depth = texture(width, height, true, depthValues);
        int world = GL30.glGenFramebuffers();
        int externalSampler = GL33.glGenSamplers();
        RVP_ScreenFrameResources frame = new RVP_ScreenFrameResources();
        GL13.glActiveTexture(GL13.GL_TEXTURE3); GL11.glBindTexture(GL11.GL_TEXTURE_2D, depth);
        GL33.glSamplerParameteri(externalSampler, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
        GL33.glBindSampler(3, externalSampler);
        GL33.glBindSampler(4, externalSampler); GL33.glBindSampler(5, externalSampler);
        try (RVP_ScreenFrameResources.State ignored = new RVP_ScreenFrameResources.State()) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, world);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, output, 0);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0); GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            require(frame.captureDepth(world, depth, width, height), "multi near capture");
            require(frame.captureColor(world, width, height), "multi color capture");
            configure(shader, frame, projection, null, false);
            RVP_LightParameters preset = RVP_LightParameters.engine();
            lights(shader, preset, 1.5F, 1);
            RVP_LightOcclusionPass.prepare(frame, shader);
            float[] one = draw(shader, frame, world, 1);
            lights(shader, preset, -1.5F, 1);
            float[] opposite = draw(shader, frame, world, 1);
            lights(shader, preset, 1.5F, 2);
            shader.safeGetUniform("LightPositionRadius1").set(-1.5F, 0F, -2F, preset.radius());
            float[] two = draw(shader, frame, world, 1);
            for (int i = 0; i < one.length; i += 4) {
                require(Math.abs(two[i] - one[i] - opposite[i]) < .01F, "two physical lights were merged or lost");
            }
            shader.safeGetUniform("OcclusionMaxSteps").set(1);
            // 平面光路第一层已经证明无遮挡；增加一格深度范围，强制触发真实“需要下钻但预算不足”。
            int location = GL20.glGetUniformLocation(shader.getId(), "OcclusionReady");
            require(location >= 0, "missing actual hierarchy uniform");
            shader.safeGetUniform("OcclusionReady").set(0);
            float[] budget = draw(shader, frame, world, 4);
            boolean exhausted = false;
            for (int i = 0; i < budget.length; i += 4) { exhausted |= budget[i + 2] > 0; }
            require(exhausted, "budget exhaustion not distinguished from clear path");
            // 调用统一绑定恢复预算后，将灯放到屏幕外；不得计作真实几何命中或预算耗尽。
            RVP_LightOcclusionPass.upload(shader, frame.depth());
            shader.safeGetUniform("LightPositionRadius0").set(30F, 0F, -2F, 100F);
            shader.safeGetUniform("LightCount").set(1);
            float[] outside = draw(shader, frame, world, 4);
            boolean offscreen = false;
            for (int i = 0; i < outside.length; i += 4) { offscreen |= outside[i + 1] > 0; require(outside[i + 2] == 0, "offscreen counted as exhausted"); }
            require(offscreen, "offscreen reason missing");
            for (float z : new float[] {1F, -.05F}) {
                // 调用相机后方和近裁剪面外的真实光路，必须安全标记为不可投影，不产生 NaN 或假命中。
                shader.safeGetUniform("LightPositionRadius0").set(0F, 0F, z, 100F);
                float[] clipped = draw(shader, frame, world, 4);
                boolean rejected = false;
                for (int i = 0; i < clipped.length; i += 4) {
                    require(Float.isFinite(clipped[i]) && clipped[i] == 0 && clipped[i + 2] == 0, "near-plane path became a fake hit or NaN");
                    rejected |= clipped[i + 1] > 0;
                }
                require(rejected, "near-plane clipping reason missing");
            }
        } finally { frame.release(); GL30.glDeleteFramebuffers(world); }
        require(GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING, 3) == externalSampler, "fourth sampler not restored");
        require(GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING, 4) == externalSampler
                && GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING, 5) == externalSampler, "geometry sampler state not restored");
        GL13.glActiveTexture(GL13.GL_TEXTURE3);
        require(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) == depth, "fourth texture not restored");
        GL33.glBindSampler(3, 0); GL33.glBindSampler(4, 0); GL33.glBindSampler(5, 0); GL33.glDeleteSamplers(externalSampler);
        System.out.println("PASS independent multiple light contributions, budget/offscreen/near-plane states and fourth texture/sampler restoration");
    }

    /** 1080p 合成专项计时包括本帧分层构建；不包含游戏世界、光晕或烟雾绘制。 */
    private static void benchmark(ShaderInstance shader, int count) {
        int width = 1920, height = 1080;
        Matrix4f projection = new Matrix4f().perspective((float) Math.PI / 2, 1, .1F, 100);
        float[] values = new float[width * height];
        Arrays.fill(values, (-projection.m22() + projection.m32() / 10) * .5F + .5F);
        // 真实细遮挡条让最高层范围覆盖前后景，不能只用整张平面的一次查询冒充压力计时。
        for (int y = 0; y < height; y++) { for (int x = width / 2 - 3; x <= width / 2 + 3; x++) {
            values[y * width + x] = (-projection.m22() + projection.m32() / 4) * .5F + .5F;
        } }
        int depth = texture(width, height, true, values);
        int color = texture(width, height, false, new float[width * height * 4]);
        int world = GL30.glGenFramebuffers();
        int timer = GL15.glGenQueries();
        RVP_ScreenFrameResources frame = new RVP_ScreenFrameResources();
        try (RVP_ScreenFrameResources.State ignored = new RVP_ScreenFrameResources.State()) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, world);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, color, 0);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0); GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            require(frame.captureDepth(world, depth, width, height), "benchmark near capture");
            require(frame.captureColor(world, width, height), "benchmark color capture");
            configure(shader, frame, projection, null, false);
            lights(shader, RVP_LightParameters.engine(), 1.5F, count);
            double[] milliseconds = new double[11];
            for (int iteration = -5; iteration < milliseconds.length; iteration++) {
                GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, timer);
                // 调用实际 Java 构建及正式表面程序，不用简化片元程序做性能替身。
                RVP_LightOcclusionPass.beginFrame();
                RVP_LightOcclusionPass.prepare(frame, shader);
                shader.safeGetUniform("OutputMode").set(1);
                frame.bind(world, width, height); frame.draw(shader);
                GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
                double elapsed = GL33.glGetQueryObjectui64(timer, GL15.GL_QUERY_RESULT) / 1_000_000.0;
                if (iteration >= 0) { milliseconds[iteration] = elapsed; }
            }
            Arrays.sort(milliseconds);
            System.out.printf(java.util.Locale.ROOT, "TIMING 1920x1080 lights=%d hierarchy+surface median=%.3fms p95=%.3fms%n",
                    count, milliseconds[5], milliseconds[10]);
        } finally { frame.release(); GL30.glDeleteFramebuffers(world); GL15.glDeleteQueries(timer); }
    }

    /** 正式三维路径计时：数据已经驻留，采用真正的地面与箱体深度；CPU 区段快照时间另由运行时状态显示。 */
    private static void benchmarkGeometry(ShaderInstance shader, int count) {
        int width = 1920, height = 1080;
        Matrix4f projection = new Matrix4f().perspective((float) Math.PI / 2, 1, .1F, 100);
        float[] values = new float[width * height];
        for (int y = 0; y < height; y++) { for (int x = 0; x < width; x++) {
            double dx = (x + .5) / width * 2 - 1, dy = (y + .5) / height * 2 - 1;
            double floor = dy < 0 ? -2 / dy : Double.POSITIVE_INFINITY;
            double at = Math.min(floor, boxIntersection(0, 0, 0, dx, dy, -1, 100));
            values[y * width + x] = at < 90 ? (float)((-projection.m22() + projection.m32() / at) * .5 + .5) : 1;
        } }
        int depth = texture(width, height, true, values);
        int color = texture(width, height, false, new float[width * height * 4]);
        int world = GL30.glGenFramebuffers(), timer = GL15.glGenQueries();
        RVP_ScreenFrameResources frame = new RVP_ScreenFrameResources();
        try (RVP_ScreenFrameResources.State ignored = new RVP_ScreenFrameResources.State()) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, world);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, color, 0);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0); GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            require(frame.captureDepth(world, depth, width, height), "geometry benchmark depth");
            require(frame.captureColor(world, width, height), "geometry benchmark color");
            configure(shader, frame, projection, null, false);
            lights(shader, RVP_LightParameters.engine(), -2.2F, count);
            // 调用与完整几何正确性回归相同的正式输入，八灯各自读取其真实纹理区块。
            installBoxGeometry(shader, false);
            for (int slot = 0; slot < 8; slot++) { shader.safeGetUniform("LightPositionRadius" + slot).set(-2.2F, 1F, -3F, 20F); }
            double[] milliseconds = new double[11];
            for (int iteration = -5; iteration < milliseconds.length; iteration++) {
                GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, timer);
                shader.safeGetUniform("OutputMode").set(1);
                frame.bind(world, width, height); frame.draw(shader);
                GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
                double elapsed = GL33.glGetQueryObjectui64(timer, GL15.GL_QUERY_RESULT) / 1_000_000.0;
                if (iteration >= 0) { milliseconds[iteration] = elapsed; }
            }
            Arrays.sort(milliseconds);
            System.out.printf(Locale.ROOT, "TIMING-3D 1920x1080 lights=%d cached-geometry+surface median=%.3fms max=%.3fms%n", count, milliseconds[5], milliseconds[10]);
        } finally { frame.release(); GL30.glDeleteFramebuffers(world); GL15.glDeleteQueries(timer); }
    }

    /** 同时设置近景、可选 DH 和当前窗口的正式 uniform；反向深度仅影响各自矩阵。 */
    private static void configure(ShaderInstance shader, RVP_ScreenFrameResources frame,
                                  Matrix4f projection, Matrix4f distant, boolean reverse) {
        shader.setSampler("SceneColor", frame.color()); shader.setSampler("SceneDepth", frame.depth());
        shader.setSampler("DistantDepth", distant == null ? frame.depth() : frame.distantDepth());
        shader.safeGetUniform("Projection").set(projection);
        shader.safeGetUniform("InverseProjection").set(new Matrix4f(projection).invert());
        shader.safeGetUniform("NearEmptyDepth").set(1F);
        shader.safeGetUniform("ScreenSize").set((float) frame.width(), (float) frame.height());
        shader.safeGetUniform("HandDepthLimit").set(0F);
        shader.safeGetUniform("HasDistantDepth").set(distant == null ? 0 : 1);
        shader.safeGetUniform("DistantEmptyDepth").set(reverse ? 0F : 1F);
        shader.safeGetUniform("DistantProjection").set(distant == null ? projection : distant);
        shader.safeGetUniform("InverseDistantProjection").set(new Matrix4f(distant == null ? projection : distant).invert());
        shader.safeGetUniform("DistantToView").set(new Matrix4f()); shader.safeGetUniform("ViewToDistant").set(new Matrix4f());
        // 调用生产几何采样器绑定；这些历史平面用例显式保留模式0，新箱体用例另测模式1。
        RVP_LightGeometryPass.upload(shader);
        shader.safeGetUniform("GeometryMode").set(0);
    }

    /** 填充全部八槽位，检查旧帧数据不会越过 LightCount 产生贡献。 */
    private static void lights(ShaderInstance shader, RVP_LightParameters preset, float lightX, int count) {
        shader.safeGetUniform("LightCount").set(count);
        for (int index = 0; index < 8; index++) {
            shader.safeGetUniform("LightPositionRadius" + index).set(lightX, 0F, -2F, preset.radius());
            shader.safeGetUniform("LightColorIntensity" + index).set(preset.red(), preset.green(), preset.blue(), preset.intensity());
            shader.safeGetUniform("LightShape" + index).set(preset.surfaceWeight(), 0F, 0F, 0F);
        }
    }

    /** 回读正式程序的浮点结果，诊断模式不会影响屏幕显示。 */
    private static float[] draw(ShaderInstance shader, RVP_ScreenFrameResources frame, int target, int mode) {
        shader.safeGetUniform("OutputMode").set(mode);
        frame.bind(target, frame.width(), frame.height()); frame.draw(shader);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target);
        float[] pixels = new float[frame.width() * frame.height() * 4];
        GL11.glReadPixels(0, 0, frame.width(), frame.height(), GL11.GL_RGBA, GL11.GL_FLOAT, pixels);
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "actual production GL error");
        return pixels;
    }

    /** 测试纹理仅在显存构造，深度使用生产同款最近点采样。 */
    private static int texture(int width, int height, boolean depth, float[] values) {
        int texture = GL11.glGenTextures(); TEXTURES.add(texture);
        GL13.glActiveTexture(GL13.GL_TEXTURE0); GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, depth ? GL30.GL_DEPTH_COMPONENT32F : GL30.GL_RGBA16F,
                width, height, 0, depth ? GL11.GL_DEPTH_COMPONENT : GL11.GL_RGBA, GL11.GL_FLOAT, values);
        return texture;
    }

    /** 所有几何、状态与生命周期检查必须真正通过，否则阻止诊断成功。 */
    private static void require(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }
}
