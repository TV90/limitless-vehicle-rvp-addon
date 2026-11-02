package org.ywzj.rvp.client.light;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import org.ywzj.rvp.client.render.RVP_GlStateSync;
import org.ywzj.rvp.config.RVP_LightingConfig;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeOBB;

import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.*;

/** 光源周围的真实三维碰撞形状；参考 Sable 的几何场与表面受光分离，不再从相机轮廓猜遮挡厚度。 */
final class RVP_LightGeometryPass {
    /** 每盏光的纹理槽位按 64³ 布局，光心位于中央；实际只填写并上传半径内的子盒。 */
    static final int SIZE = 64;
    /** 64 个 Z 切片平铺为 8×8 小图，每盏灯在普通二维纹理中占用一个 512² 区域。 */
    static final int ATLAS_SIZE = 512;
    /** 形状表的方盒数量上限；方块的多个碰撞盒保留为并集，不用一个大外框填满台阶空隙。 */
    private static final int MAX_SHAPES = 4096;
    /** 动态实体的旋转碰撞盒预算；玩家为普通轴对齐盒，载具使用本体结构碰撞盒。 */
    private static final int MAX_BODIES = 48;
    /** 一个实体盒的数据：中心3、半长3、旋转四元数4、适用光源位掩码1。 */
    private static final int BODY_STRIDE = 11;
    /** 光心贴住实体盒时的接触容差，单位格；2毫米只吸收坐标/姿态误差，不扩大整个遮挡盒。 */
    static final double SOURCE_CONTACT_MARGIN = 0.002;
    /** 空区段共用零数组，避免对空中数十万个方块逐一查询。 */
    private static final short[] AIR = new short[4096];
    /** 当逻辑刻的区段快照，多个光源覆盖同一区段时只读取一次。 */
    private static final Map<Long, short[]> SECTIONS = new HashMap<>();
    /** 无动态几何方块的形状编号缓存；动态形状仍按当前位置获取。 */
    private static final Map<BlockState, Integer> STATES = new HashMap<>();
    /** 相同碰撞盒并集共享一段形状表，减少相同朝向台阶的重复数据。 */
    private static final Map<List<AABB>, Integer> SHAPES = new HashMap<>();
    /** 单盏灯上传缓冲，按平铺切片顺序写入，无逐帧磁盘资源。 */
    private static final ShortBuffer CELLS = BufferUtils.createShortBuffer(SIZE * SIZE * SIZE);
    /** 清除旧原点区域用的全零缓冲；一次最多上传 64×64 个短整数。 */
    private static final ShortBuffer ZEROS = BufferUtils.createShortBuffer(SIZE * SIZE);
    /** 两行形状表：第一行最小点与盒数，第二行最大点。 */
    private static final FloatBuffer PALETTE = BufferUtils.createFloatBuffer(MAX_SHAPES * 8);
    /** 八盏灯的网格原点及有效区域编号，先减 double 相机坐标再转 float。 */
    private static final float[] ORIGINS = new float[32];
    /** 实体盒上传数据，与 GLSL 的 scalar 数组一致，避免向 vec4 数组错误调用 glUniform1fv。 */
    private static final float[] BODIES = new float[MAX_BODIES * BODY_STRIDE];
    /** 每盏灯的纹理区域已上传的原点。 */
    private static final BlockPos[] UPLOADED_ORIGINS = new BlockPos[8];
    /** 每盏灯当前原点下已上传的体素子盒（低端含、高端不含），用于换原点时清掉旧数据。 */
    private static final int[][] UPLOADED_BOUNDS = new int[8][6];
    /** 每盏灯已上传的逻辑刻；同刻多显示帧只更新实体插值。 */
    private static final long[] UPLOADED_TICKS = new long[8];
    /** 方块形状编号纹理，R16UI，八盏灯按四列两行放置；兼容 Minecraft 仅绑定二维采样器的接口。 */
    private static int cellsTexture;
    /** 碰撞盒形状表纹理，RGBA32F。 */
    private static int paletteTexture;
    /** 形状表下一个可用编号，0 空、1 完整方块。 */
    private static int nextShape = 2;
    /** 形状表发生变化时才重新上传。 */
    private static boolean paletteDirty = true;
    /** 当前数据所属世界，切换世界不能复用旧碰撞体。 */
    private static ClientLevel world;
    /** 区段缓存所属逻辑刻。 */
    private static long tick = Long.MIN_VALUE;
    /** 当前帧三维数据是否准备完成；失败时只保留表面照亮，不回到旧轮廓投影。 */
    private static boolean ready;
    /** 本次资源生命周期是否仍允许重试分配。 */
    private static boolean available = true;
    /** 当前实体碰撞盒数量。 */
    private static int bodyCount;
    /** 本帧的相机坐标到相机相对世界坐标变换。 */
    private static final Matrix4f VIEW_TO_WORLD = new Matrix4f();
    /** 最近一次构建摘要，用于区分真实三维遮挡与没有可用几何时的降级。 */
    private static String description = "三维遮挡尚未准备";

    private RVP_LightGeometryPass() { }

    /** 每显示帧失效一次；实体位置逐帧插值，方块快照仍按逻辑刻复用。 */
    static void beginFrame() { ready = false; }

    /** 不加载新世界区块，只查询客户端已有的方块和实体，不新增 Mixin。 */
    static void prepare(ClientLevel level, List<RVP_ScreenLight> lights, Matrix4f view, Vec3 camera, float partialTick) {
        ready = false;
        Arrays.fill(ORIGINS, 0);
        if (!available || level == null || lights.isEmpty()) { return; }
        long started = System.nanoTime();
        try (UnpackState ignored = new UnpackState()) {
            if (world != level) {
                // 调用世界清理，换维度和重连均丢弃形状缓存与旧纹理身份。
                release(); world = level;
            }
            if (tick != level.getGameTime()) { tick = level.getGameTime(); SECTIONS.clear(); }
            // 调用显存申请；不操作外部帧缓冲，不需要逐灯重新画世界。
            ensureTextures();
            VIEW_TO_WORLD.set(view).invert();
            for (int slot = 0; slot < lights.size(); slot++) {
                RVP_ScreenLight light = lights.get(slot);
                if (light.radius() <= 0 || light.intensity() <= 0 || light.surfaceWeight() <= 0) { continue; }
                BlockPos origin = BlockPos.containing(light.position()).offset(-SIZE / 2, -SIZE / 2, -SIZE / 2);
                // 光源只照半径以内的表面，光路也整段落在球内，所以子盒取半径外扩两格即可覆盖全部查询体素。
                int reach = Math.min(SIZE / 2, (int) Math.ceil(light.radius()) + 2);
                int x0 = SIZE / 2 - reach, y0 = SIZE / 2 - reach, z0 = SIZE / 2 - reach;
                int x1 = SIZE / 2 + reach, y1 = SIZE / 2 + reach, z1 = SIZE / 2 + reach;
                boolean moved = !origin.equals(UPLOADED_ORIGINS[slot]);
                if (UPLOADED_TICKS[slot] != tick || moved) {
                    // 调用旧区域清除：换原点后旧体素不再对应当前世界位置，不能留给以后换回大半径的灯。
                    if (moved) { uploadZeros(slot, UPLOADED_BOUNDS[slot]); }
                    zeroCells(x0, y0, z0, x1, y1, z1);
                    // 调用区段复用的体积构建，稠密三维格仅存形状编号，不复制每个方块的完整盒数据。
                    fillCells(level, origin, x0, y0, z0, x1, y1, z1);
                    uploadCells(slot, x0, y0, z0, x1, y1, z1);
                    int[] bounds = UPLOADED_BOUNDS[slot];
                    bounds[0] = x0; bounds[1] = y0; bounds[2] = z0;
                    bounds[3] = x1; bounds[4] = y1; bounds[5] = z1;
                    UPLOADED_ORIGINS[slot] = origin;
                    UPLOADED_TICKS[slot] = tick;
                }
                ORIGINS[slot * 4] = (float) (origin.getX() - camera.x);
                ORIGINS[slot * 4 + 1] = (float) (origin.getY() - camera.y);
                ORIGINS[slot * 4 + 2] = (float) (origin.getZ() - camera.z);
                ORIGINS[slot * 4 + 3] = slot + 1;
            }
            if (paletteDirty) {
                PALETTE.position(0).limit(PALETTE.capacity());
                RVP_GlStateSync.activeTexture(GL13.GL_TEXTURE5); RVP_GlStateSync.bindTexture2D(paletteTexture);
                GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, MAX_SHAPES, 2, GL11.GL_RGBA, GL11.GL_FLOAT, PALETTE);
                paletteDirty = false;
            }
            // 调用实体采集，载具用已有旋转结构盒，普通实体用其碰撞体积。
            collectBodies(level, lights, camera, partialTick);
            ready = true;
            description = "三维方块场=半径子盒/灯（≤64³），形状=" + nextShape + "，实体盒=" + bodyCount
                    + "，本帧准备=" + String.format(Locale.ROOT, "%.2fms", (System.nanoTime() - started) / 1_000_000.0);
        } catch (RuntimeException exception) {
            // 调用自有资源清理，失败时关闭投影猜测，不能重启之前会重复轮廓的屏幕空间算法。
            release(); available = false;
            description = "三维遮挡不可用，保留受光：" + exception.getClass().getSimpleName() + " " + exception.getMessage();
        }
    }

    /** 所有正式材质都显式绑定相同几何；模式2表示缺数据，不把未知区域当实心。 */
    static void upload(ShaderInstance shader) {
        ensureTextures();
        shader.setSampler("GeometryCells", cellsTexture);
        shader.setSampler("GeometryPalette", paletteTexture);
        shader.safeGetUniform("GeometryMode").set(ready ? 1 : 2);
        shader.safeGetUniform("GeometryOrigins").set(ORIGINS);
        shader.safeGetUniform("GeometryBodies").set(BODIES);
        shader.safeGetUniform("GeometryBodyCount").set(ready ? bodyCount : 0);
        shader.safeGetUniform("GeometryViewToWorld").set(VIEW_TO_WORLD);
    }

    /** 命令只读显示当前使用的几何来源及明确边界。 */
    static String status() { return description + "；未加载区段不猜测遮挡"; }

    /** 构建一盏灯半径子盒的切片图；空区段整段跳过，其余按行复制。 */
    private static void fillCells(ClientLevel level, BlockPos origin, int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int sy = (origin.getY() + y0) >> 4; sy <= (origin.getY() + y1 - 1) >> 4; sy++) {
            for (int sz = (origin.getZ() + z0) >> 4; sz <= (origin.getZ() + z1 - 1) >> 4; sz++) {
                for (int sx = (origin.getX() + x0) >> 4; sx <= (origin.getX() + x1 - 1) >> 4; sx++) {
                    short[] section = section(level, sx, sy, sz);
                    if (section == AIR) { continue; }
                    int sx0 = Math.max(x0, (sx << 4) - origin.getX()), sx1 = Math.min(x1, (sx + 1 << 4) - origin.getX());
                    int sy0 = Math.max(y0, (sy << 4) - origin.getY()), sy1 = Math.min(y1, (sy + 1 << 4) - origin.getY());
                    int sz0 = Math.max(z0, (sz << 4) - origin.getZ()), sz1 = Math.min(z1, (sz + 1 << 4) - origin.getZ());
                    for (int y = sy0; y < sy1; y++) { for (int z = sz0; z < sz1; z++) {
                        int source = ((origin.getY() + y & 15) << 8) + ((origin.getZ() + z & 15) << 4) + (origin.getX() + sx0 & 15);
                        CELLS.position(atlasIndex(sx0, y, z)); CELLS.put(section, source, sx1 - sx0);
                    } }
                }
            }
        }
        CELLS.position(0).limit(CELLS.capacity());
    }

    /** 把子盒区域先置零；子盒外保持原内容，但纹理里也从未上传过，不会被采样。 */
    private static void zeroCells(int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int y = y0; y < y1; y++) {
            for (int z = z0; z < z1; z++) {
                CELLS.position(atlasIndex(x0, y, z));
                for (int x = x0; x < x1; x++) { CELLS.put((short) 0); }
            }
        }
        CELLS.position(0).limit(CELLS.capacity());
    }

    /** 只上传子盒的切片；行距按平铺图集宽度，避免把未使用的 64³ 区域整块复制上传。 */
    private static void uploadCells(int slot, int x0, int y0, int z0, int x1, int y1, int z1) {
        RVP_GlStateSync.activeTexture(GL13.GL_TEXTURE4);
        RVP_GlStateSync.bindTexture2D(cellsTexture);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, ATLAS_SIZE);
        try {
            // 超大半径已覆盖整个立方体：恢复单次整块上传，避免拆成 64 次小切片。
            if (x0 == 0 && y0 == 0 && z0 == 0 && x1 == SIZE && y1 == SIZE && z1 == SIZE) {
                CELLS.position(0).limit(CELLS.capacity());
                GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
                GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, (slot & 3) * ATLAS_SIZE, (slot >> 2) * ATLAS_SIZE,
                        ATLAS_SIZE, ATLAS_SIZE, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_SHORT, CELLS);
                return;
            }
            for (int z = z0; z < z1; z++) {
                CELLS.position(atlasIndex(x0, y0, z));
                GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0,
                        (slot & 3) * ATLAS_SIZE + (z & 7) * SIZE + x0,
                        (slot >> 2) * ATLAS_SIZE + (z >> 3) * SIZE + y0,
                        x1 - x0, y1 - y0, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_SHORT, CELLS);
            }
        } finally {
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        }
    }

    /** 用全零缓冲清掉同一槽位上一次原点留下的子盒；空区域直接跳过。 */
    private static void uploadZeros(int slot, int[] bounds) {
        if (bounds[0] >= bounds[3] || bounds[1] >= bounds[4] || bounds[2] >= bounds[5]) { return; }
        RVP_GlStateSync.activeTexture(GL13.GL_TEXTURE4);
        RVP_GlStateSync.bindTexture2D(cellsTexture);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        ZEROS.position(0);
        try {
            for (int z = bounds[2]; z < bounds[5]; z++) {
                GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0,
                        (slot & 3) * ATLAS_SIZE + (z & 7) * SIZE + bounds[0],
                        (slot >> 2) * ATLAS_SIZE + (z >> 3) * SIZE + bounds[1],
                        bounds[3] - bounds[0], bounds[4] - bounds[1],
                        GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_SHORT, ZEROS);
            }
        } finally {
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        }
    }

    /** 世界 XYZ 体素到一层 512² 切片图的地址，与 GLSL 完全一致。 */
    static int atlasIndex(int x, int y, int z) { return x + (z & 7) * SIZE + (y + (z >> 3) * SIZE) * ATLAS_SIZE; }

    /** 同刻只读取一次已有区段；缺失和超出建筑高度的区域保持未知/空，不强制加载。 */
    private static short[] section(ClientLevel level, int x, int y, int z) {
        long key = SectionPos.asLong(x, y, z);
        short[] cached = SECTIONS.get(key);
        if (cached != null) { return cached; }
        LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
        int index = y - (level.getMinBuildHeight() >> 4);
        if (chunk == null || index < 0 || index >= chunk.getSections().length || chunk.getSections()[index].hasOnlyAir()) {
            SECTIONS.put(key, AIR); return AIR;
        }
        LevelChunkSection section = chunk.getSections()[index];
        short[] values = new short[4096];
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        for (int by = 0; by < 16; by++) { for (int bz = 0; bz < 16; bz++) { for (int bx = 0; bx < 16; bx++) {
            BlockState state = section.getBlockState(bx, by, bz);
            if (!state.isAir()) {
                position.set((x << 4) + bx, (y << 4) + by, (z << 4) + bz);
                // 调用原版真实碰撞形状，楼梯和半砖保留多个局部盒，不按武器或方块ID硬编码。
                values[(by << 8) + (bz << 4) + bx] = (short) shape(level, state, position);
            }
        } } }
        SECTIONS.put(key, values); return values;
    }

    /** 碰撞形状按相同盒并集去重；动态方块不按 BlockState 永久缓存。 */
    private static int shape(ClientLevel level, BlockState state, BlockPos position) {
        boolean dynamic = state.getBlock().hasDynamicShape();
        Integer cached = dynamic ? null : STATES.get(state);
        if (cached != null) { return cached; }
        List<AABB> boxes = List.copyOf(state.getCollisionShape(level, position).toAabbs());
        Integer previous = SHAPES.get(boxes);
        int result;
        if (boxes.isEmpty()) { result = 0; }
        else if (previous != null) { result = previous; }
        else {
            if (boxes.size() > 64 || nextShape + boxes.size() > MAX_SHAPES) { throw new IllegalStateException("碰撞形状表超出预算"); }
            result = nextShape;
            for (AABB box : boxes) { putShape(nextShape++, box, boxes.size()); }
            SHAPES.put(boxes, result); paletteDirty = true;
        }
        if (!dynamic) { STATES.put(state, result); }
        return result;
    }

    /** 记录局部碰撞盒的真实边界，第一盒的 w 保存整个并集的盒数。 */
    private static void putShape(int index, AABB box, int count) {
        int a = index * 4, b = (MAX_SHAPES + index) * 4;
        PALETTE.put(a, (float) box.minX).put(a + 1, (float) box.minY).put(a + 2, (float) box.minZ).put(a + 3, count);
        PALETTE.put(b, (float) box.maxX).put(b + 1, (float) box.maxY).put(b + 2, (float) box.maxZ).put(b + 3, 0);
    }

    /** 实体逐帧插值；载具复用本体已经计算的结构盒，不重新生成或修改结构模型。 */
    private static void collectBodies(ClientLevel level, List<RVP_ScreenLight> lights, Vec3 camera, float partialTick) {
        Arrays.fill(BODIES, 0); bodyCount = 0;
        List<Entity> entities = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (!entity.isAlive() || entity.isSpectator() || entity instanceof Projectile || entity instanceof RVP_BaseBullet
                    || entity.getBbWidth() < .2F) { continue; }
            if (entity == Minecraft.getInstance().getCameraEntity() && Minecraft.getInstance().options.getCameraType().isFirstPerson()) { continue; }
            if (lights.stream().anyMatch(light -> entity.getBoundingBox().inflate(light.radius()).contains(light.position()))) { entities.add(entity); }
        }
        entities.sort(Comparator.comparingDouble(entity -> entity.distanceToSqr(camera)));
        for (Entity entity : entities) {
            if (bodyCount >= MAX_BODIES) { break; }
            if (entity instanceof AbstractVehicle vehicle) {
                // 调用本体结构碰撞盒列表，优先大块主体；不把整车包成一个堵住车底空隙的大方盒。
                List<VehicleCubeOBB> cubes = vehicle.getVehicleCubeOBBs().stream()
                        .sorted(Comparator.comparingDouble((VehicleCubeOBB cube) -> cube.obb().getSize()).reversed()).limit(16).toList();
                for (VehicleCubeOBB cube : cubes) {
                    if (bodyCount >= MAX_BODIES || cube.position == null || cube.rotation == null) { continue; }
                    Vec3 center = cube.positionO == null ? cube.position : cube.positionO.lerp(cube.position, partialTick);
                    // 调用稳定姿态插值：静止车体不做相同旋转之间的浮点混合，避免贴面爆心反复进出碰撞盒。
                    Quaternionf rotation = interpolateBodyRotation(cube.rotationO, cube.rotation, partialTick);
                    // 调用已有结构盒半长，与车身/炮塔当前插值姿态一起提交；发出该灯的载具默认不遮自己的灯。
                    addBody(center, cube.obb().extents(), rotation, lights, camera, vehicle.getUUID());
                }
            } else {
                Vec3 offset = entity.getPosition(partialTick).subtract(entity.position());
                AABB box = entity.getBoundingBox().move(offset);
                addBody(box.getCenter(), new Vector3f((float) box.getXsize() / 2, (float) box.getYsize() / 2, (float) box.getZsize() / 2),
                        new Quaternionf(), lights, camera, null);
            }
        }
    }

    /** 相同姿态直接复制；变化的姿态正常插值，统一归一化后供实体盒求交使用。 */
    static Quaternionf interpolateBodyRotation(Quaternionf previous, Quaternionf current, float partialTick) {
        return (previous == null || previous.equals(current)
                ? new Quaternionf(current) : new Quaternionf(previous).slerp(current, partialTick)).normalize();
    }

    /** 光心在实体盒内或贴着盒面时豁免该盒；先用 double 相减，判定不随相机位置和观察矩阵改变。 */
    static boolean sourceTouchesBody(Vec3 source, Vec3 center, Vector3f extents, Quaternionf rotation) {
        Vector3d local = new Vector3d(source.x - center.x, source.y - center.y, source.z - center.z);
        new Quaterniond(rotation).transformInverse(local);
        return Math.abs(local.x) <= extents.x + SOURCE_CONTACT_MARGIN
                && Math.abs(local.y) <= extents.y + SOURCE_CONTACT_MARGIN
                && Math.abs(local.z) <= extents.z + SOURCE_CONTACT_MARGIN;
    }

    /** 每个盒只关联能照到它的光源；先做 CPU 半径过滤，显卡避免遍历所有无关实体。 */
    private static void addBody(Vec3 center, Vector3f extents, Quaternionf rotation, List<RVP_ScreenLight> lights,
            Vec3 camera, UUID sourceVehicle) {
        boolean occludeOwn = RVP_LightingConfig.sourceVehicleOccludesOwnLight();
        int mask = 0;
        for (int index = 0; index < lights.size(); index++) {
            RVP_ScreenLight light = lights.get(index);
            // 调用本车遮灯开关；关闭时发出这盏灯的载具结构盒不参与该灯的光路，避免车体把自己的灯埋进盒子里。
            if (!occludeOwn && sourceVehicle != null && sourceVehicle.equals(light.persistentKey())) { continue; }
            if (center.distanceTo(light.position()) > light.radius() + extents.length()) { continue; }
            // 调用世界坐标中的光心接触判定，再写入灯位标记；显卡不能用低精度观察坐标重新翻转此决定。
            if (sourceTouchesBody(light.position(), center, extents, rotation)) { continue; }
            mask |= 1 << index;
        }
        if (mask == 0) { return; }
        int at = bodyCount++ * BODY_STRIDE;
        BODIES[at] = (float) (center.x - camera.x); BODIES[at + 1] = (float) (center.y - camera.y); BODIES[at + 2] = (float) (center.z - camera.z);
        BODIES[at + 3] = extents.x; BODIES[at + 4] = extents.y; BODIES[at + 5] = extents.z;
        BODIES[at + 6] = rotation.x; BODIES[at + 7] = rotation.y; BODIES[at + 8] = rotation.z; BODIES[at + 9] = rotation.w;
        BODIES[at + 10] = mask;
    }

    /** 分配两张自有几何纹理；分辨率不依赖画面尺寸，也不逐光创建帧缓冲。 */
    private static void ensureTextures() {
        if (cellsTexture != 0) { return; }
        try (UnpackState ignored = new UnpackState()) {
            Arrays.fill(UPLOADED_TICKS, Long.MIN_VALUE);
            for (int[] bounds : UPLOADED_BOUNDS) { Arrays.fill(bounds, 0); }
            cellsTexture = GL11.glGenTextures();
            RVP_GlStateSync.activeTexture(GL13.GL_TEXTURE4); RVP_GlStateSync.bindTexture2D(cellsTexture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R16UI, ATLAS_SIZE * 4, ATLAS_SIZE * 2, 0,
                    GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_SHORT, (java.nio.ByteBuffer) null);
            paletteTexture = GL11.glGenTextures();
            RVP_GlStateSync.activeTexture(GL13.GL_TEXTURE5); RVP_GlStateSync.bindTexture2D(paletteTexture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, MAX_SHAPES, 2, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (java.nio.ByteBuffer) null);
            putShape(1, new AABB(0, 0, 0, 1, 1, 1), 1);
            SHAPES.put(List.of(new AABB(0, 0, 0, 1, 1, 1)), 1);
            paletteDirty = true;
        }
    }

    /** CPU→GPU 上传独立保存像素行距、跳过区域、字节序及 PBO，防止外部材质上传状态把切片复制错行。 */
    private static final class UnpackState implements AutoCloseable {
        /** 所有会改变整数/浮点二维数据地址解释的像素上传参数，首项为对齐。 */
        private static final int[] PARAMETERS = {GL11.GL_UNPACK_ALIGNMENT, GL11.GL_UNPACK_ROW_LENGTH,
                GL11.GL_UNPACK_SKIP_PIXELS, GL11.GL_UNPACK_SKIP_ROWS, GL12.GL_UNPACK_IMAGE_HEIGHT,
                GL12.GL_UNPACK_SKIP_IMAGES, GL11.GL_UNPACK_SWAP_BYTES, GL11.GL_UNPACK_LSB_FIRST};
        /** 调用者的原像素上传参数。 */
        private final int[] values = new int[PARAMETERS.length];
        /** 原上传缓冲；有 PBO 时 Java 缓冲地址不能被当成偏移解释。 */
        private final int buffer = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);

        private UnpackState() {
            for (int index = 0; index < PARAMETERS.length; index++) {
                values[index] = GL11.glGetInteger(PARAMETERS[index]);
                GL11.glPixelStorei(PARAMETERS[index], index == 0 ? 1 : 0);
            }
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        }

        @Override public void close() {
            for (int index = 0; index < PARAMETERS.length; index++) { GL11.glPixelStorei(PARAMETERS[index], values[index]); }
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, buffer);
        }
    }

    /** 世界卸载和资源重载释放显存与强引用；不删除任何磁盘文件。 */
    static void release() {
        ready = false; available = true; world = null; tick = Long.MIN_VALUE; bodyCount = 0;
        SECTIONS.clear(); STATES.clear(); SHAPES.clear(); nextShape = 2;
        Arrays.fill(ORIGINS, 0); Arrays.fill(BODIES, 0); Arrays.fill(UPLOADED_ORIGINS, null);
        for (int[] bounds : UPLOADED_BOUNDS) { Arrays.fill(bounds, 0); }
        if (cellsTexture != 0) { GL11.glDeleteTextures(cellsTexture); cellsTexture = 0; }
        if (paletteTexture != 0) { RenderSystem.deleteTexture(paletteTexture); paletteTexture = 0; }
        description = "三维遮挡尚未准备";
    }
}
