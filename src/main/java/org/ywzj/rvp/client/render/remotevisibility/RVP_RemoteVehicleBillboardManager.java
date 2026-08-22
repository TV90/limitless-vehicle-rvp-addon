package org.ywzj.rvp.client.render.remotevisibility;

import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BakedModelInstance;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.state.remotevisibility.RVP_ClientRemoteVehicleVisualState.RenderPolicy;
import org.ywzj.rvp.config.RVP_CommonConfig.RemoteVehicleBillboardSource;
import org.ywzj.rvp.config.RVP_CommonConfig.RemoteVehicleSnapshotWarmupMode;
import org.ywzj.vehicle.client.resource.vehicle.BaseDisplay;
import org.ywzj.vehicle.client.resource.vehicle.VehicleBedrockModel;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 超视距载具 Billboard 管理器。
 * <p>
 * 该类集中管理服务端策略判定、动态高模快照的角度分桶与 LRU、离屏绘制、
 * 世界空间四边形提交及 GPU 资源释放，避免把这些状态继续堆入远距载具主渲染器。
 */
public final class RVP_RemoteVehicleBillboardManager {
    /** 动态快照宽高，单位像素。 */
    static final int SNAPSHOT_SIZE = 256;
    /** 动态快照相邻观察角度分桶跨度，单位度。 */
    static final float ANGLE_BUCKET_DEGREES = 22.5F;
    /** 偏航分桶总数。 */
    private static final int YAW_BUCKET_COUNT = 16;
    /** 俯仰分桶最小索引，对应 -90 度。 */
    private static final int MIN_PITCH_BUCKET = -4;
    /** 俯仰分桶最大索引，对应 90 度。 */
    private static final int MAX_PITCH_BUCKET = 4;
    /** 动态快照 LRU 最大条目数。 */
    static final int MAX_SNAPSHOT_CACHE_ENTRIES = 128;
    /** 单资源代际保留的失败快照键上限，防止不兼容渲染环境下无限增长。 */
    private static final int MAX_FAILED_SNAPSHOT_KEYS = 512;
    /** 离屏模型四周预留的透明边距倍率。 */
    private static final float SNAPSHOT_HALF_EXTENT_FACTOR = 0.60F;
    /** 世界 Billboard 使用的正方形边长相对结构尺寸倍率。 */
    private static final float BILLBOARD_SIZE_FACTOR = 1.0F;
    /** 离屏静态主体专用缓冲初始容量。 */
    private static final int SNAPSHOT_BUFFER_INITIAL_CAPACITY = 256;
    /** 离屏静态主体专用缓冲，禁止与世界远距批次跨 RenderTarget 混用。 */
    private static final MultiBufferSource.BufferSource SNAPSHOT_BUFFERS =
            MultiBufferSource.immediate(new BufferBuilder(SNAPSHOT_BUFFER_INITIAL_CAPACITY));
    /** 按访问顺序维护的动态快照 GPU 缓存。 */
    private static final Map<SnapshotKey, CachedSnapshot> SNAPSHOT_CACHE =
            new LinkedHashMap<>(16, 0.75F, true);
    /** 本资源代际已确认无法生成的快照键，后续直接走高模降级。 */
    private static final java.util.Set<SnapshotKey> FAILED_SNAPSHOTS = new java.util.LinkedHashSet<>();
    /** 为动态 RenderTarget 注册唯一纹理 ID 使用的单调序号。 */
    private static long textureSequence;
    /** 动态快照失败诊断日志。 */
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 是否已经报告过当前资源代际的快照失败，避免逐视角刷日志。 */
    private static boolean warnedSnapshotFailure;

    private RVP_RemoteVehicleBillboardManager() {
    }

    /**
     * 为一个已完成基础有效性检查的候选建立 Billboard 计划。
     *
     * @param policy 最近一份有效服务端快照携带的策略
     * @param proxy 非世界载具代理
     * @param display 代理当前 display
     * @param worldPosition 当前插值世界位置
     * @param xRot 当前插值俯仰角
     * @param yRot 当前插值偏航角
     * @param zRot 当前插值滚转角
     * @param structureSize 载具结构尺寸
     * @param cameraPosition 当前相机世界位置
     * @param hasValidLod display 是否至少具有一条成功烘焙的 LOD 规则
     * @param preferModelRendering 是否由客户端观瞄缩放选项覆盖 Billboard 策略
     */
    public static BillboardPlan plan(RenderPolicy policy,
                                     AbstractVehicle proxy,
                                     BaseDisplay display,
                                     Vec3 worldPosition,
                                     float xRot,
                                     float yRot,
                                     float zRot,
                                     double structureSize,
                                     Vec3 cameraPosition,
                                     boolean hasValidLod,
                                     boolean preferModelRendering) {
        if (!shouldUseBillboard(policy, hasValidLod, preferModelRendering)) {
            return BillboardPlan.normal();
        }
        Vec3 centerOffset = proxy.centerOffset;
        if (centerOffset == null) {
            return BillboardPlan.highModel();
        }
        Vec3 center = worldPosition.add(centerOffset);
        ResourceLocation slotTexture = display.getSlotTexture();
        if (policy.billboardSource() == RemoteVehicleBillboardSource.SLOT_TEXTURE) {
            RenderMode mode = decideRenderMode(
                    policy, hasValidLod, slotTexture != null, SnapshotState.MISSING);
            return mode == RenderMode.SLOT_TEXTURE
                    ? BillboardPlan.slot(center, structureSize, slotTexture)
                    : BillboardPlan.highModel();
        }

        VehicleBedrockModel model = display.getModel();
        ResourceLocation modelId = display.getModelPath();
        ResourceLocation texture = display.getTexture();
        if (model == null || modelId == null || texture == null || !model.hasBakedModel()) {
            return BillboardPlan.highModel();
        }
        int[] buckets = resolveViewBuckets(center, cameraPosition, xRot, yRot, zRot);
        SnapshotKey key = new SnapshotKey(
                proxy.getVehicleId(),
                proxy.getDisplayId(),
                modelId,
                texture,
                quantizeStructureSize(structureSize),
                buckets[0],
                buckets[1]);
        SnapshotState snapshotState = SNAPSHOT_CACHE.containsKey(key)
                ? SnapshotState.READY
                : FAILED_SNAPSHOTS.contains(key) ? SnapshotState.FAILED : SnapshotState.MISSING;
        RenderMode mode = decideRenderMode(policy, hasValidLod, slotTexture != null, snapshotState);
        if (mode == RenderMode.HIGH_MODEL_FALLBACK) {
            return BillboardPlan.highModel();
        }
        return BillboardPlan.dynamic(mode, center, structureSize,
                slotTexture, key, model, texture, centerOffset);
    }

    /** 返回当前策略是否要求该候选使用 Billboard。 */
    static boolean shouldUseBillboard(RenderPolicy policy, boolean hasValidLod) {
        return shouldUseBillboard(policy, hasValidLod, false);
    }

    /** 返回客户端模型优先选项生效后，当前候选是否仍应使用 Billboard。 */
    static boolean shouldUseBillboard(RenderPolicy policy,
                                      boolean hasValidLod,
                                      boolean preferModelRendering) {
        return !preferModelRendering
                && (policy.forceAllVehicleBillboard()
                || (policy.aggressiveLodBillboard() && !hasValidLod));
    }

    /** 可单元测试的完整来源、资源和预热模式决策。 */
    static RenderMode decideRenderMode(RenderPolicy policy,
                                       boolean hasValidLod,
                                       boolean hasSlotTexture,
                                       SnapshotState snapshotState) {
        if (!shouldUseBillboard(policy, hasValidLod)) {
            return RenderMode.NORMAL_MODEL;
        }
        if (policy.billboardSource() == RemoteVehicleBillboardSource.SLOT_TEXTURE) {
            return hasSlotTexture ? RenderMode.SLOT_TEXTURE : RenderMode.HIGH_MODEL_FALLBACK;
        }
        return switch (snapshotState) {
            case READY -> RenderMode.DYNAMIC_READY;
            case FAILED -> RenderMode.HIGH_MODEL_FALLBACK;
            case MISSING -> decidePendingMode(policy.dynamicSnapshotWarmupMode(), hasSlotTexture);
        };
    }

    /** 把动态快照预热方式规范化为渲染器可直接消费的模式。 */
    static RenderMode decidePendingMode(RemoteVehicleSnapshotWarmupMode warmupMode,
                                        boolean hasSlotTexture) {
        return switch (warmupMode) {
            case HIDE -> RenderMode.DYNAMIC_PENDING_HIDE;
            case SLOT_TEXTURE -> hasSlotTexture
                    ? RenderMode.DYNAMIC_PENDING_SLOT
                    : RenderMode.DYNAMIC_PENDING_HIDE;
            case MODEL -> RenderMode.DYNAMIC_PENDING_MODEL;
        };
    }

    /** 返回该计划是否会实际绘制基础静态高模并消耗高模预算。 */
    public static boolean usesHighModelBudget(BillboardPlan plan) {
        return usesHighModelBudget(plan.mode);
    }

    /** 可单元测试的模式级高模预算判定。 */
    static boolean usesHighModelBudget(RenderMode mode) {
        return mode == RenderMode.HIGH_MODEL_FALLBACK
                || mode == RenderMode.DYNAMIC_PENDING_MODEL;
    }

    /** 返回该计划是否完全沿用原有 LOD/基础模型路径。 */
    public static boolean usesNormalModelPath(BillboardPlan plan) {
        return plan.mode == RenderMode.NORMAL_MODEL;
    }

    /**
     * 按预算器已经排好的优先级预热至多一张动态快照。
     * <p>
     * 调用点位于远距投影作用域开启前，避免离屏投影与扩展远平面相互嵌套。
     */
    public static void prepareOneSnapshot(List<BillboardPlan> plans) {
        for (BillboardPlan plan : plans) {
            if (plan.snapshotKey == null
                    || SNAPSHOT_CACHE.containsKey(plan.snapshotKey)
                    || FAILED_SNAPSHOTS.contains(plan.snapshotKey)) {
                continue;
            }
            if (!generateSnapshot(plan)) {
                markSnapshotFailed(plan.snapshotKey);
            }
            return;
        }
    }

    /** 记录有界失败键，并在当前资源代际首次失败时输出一次诊断。 */
    private static void markSnapshotFailed(SnapshotKey key) {
        while (FAILED_SNAPSHOTS.size() >= MAX_FAILED_SNAPSHOT_KEYS) {
            Iterator<SnapshotKey> iterator = FAILED_SNAPSHOTS.iterator();
            if (!iterator.hasNext()) {
                break;
            }
            iterator.next();
            iterator.remove();
        }
        FAILED_SNAPSHOTS.add(key);
        if (!warnedSnapshotFailure) {
            warnedSnapshotFailure = true;
            LOGGER.warn("RVP 超视距载具动态 3D 快照生成失败，相关视角将回退基础静态高模；"
                    + "可在修复资源或渲染兼容问题后执行资源重载重试。key={}", key);
        }
    }

    /**
     * 尝试绘制计划中的 Billboard。
     *
     * @return 已写入 Billboard 顶点时返回 true；需要隐藏或转交高模路径时返回 false
     */
    public static boolean renderBillboard(BillboardPlan plan,
                                          PoseStack poseStack,
                                          MultiBufferSource.BufferSource buffers,
                                          Vec3 cameraPosition,
                                          Quaternionf cameraOrientation,
                                          int packedLight) {
        ResourceLocation texture = resolveRenderableTexture(plan);
        if (texture == null) {
            return false;
        }
        boolean renderTargetTexture = isDynamicSnapshotTexture(plan, texture);
        float leftU = resolveTextureU(renderTargetTexture, false);
        float rightU = resolveTextureU(renderTargetTexture, true);
        float bottomV = resolveTextureV(renderTargetTexture, false);
        float topV = resolveTextureV(renderTargetTexture, true);
        Vec3 center = plan.center;
        float size = (float) (Math.max(1.0D, plan.structureSize) * BILLBOARD_SIZE_FACTOR);
        poseStack.pushPose();
        try {
            poseStack.translate(center.x - cameraPosition.x,
                    center.y - cameraPosition.y,
                    center.z - cameraPosition.z);
            poseStack.mulPose(new Quaternionf(cameraOrientation));
            poseStack.scale(size, size, size);
            VertexConsumer buffer = buffers.getBuffer(RenderType.entityTranslucent(texture));
            PoseStack.Pose pose = poseStack.last();
            writeVertex(buffer, pose, -0.5F, -0.5F, leftU, bottomV, packedLight);
            writeVertex(buffer, pose, 0.5F, -0.5F, rightU, bottomV, packedLight);
            writeVertex(buffer, pose, 0.5F, 0.5F, rightU, topV, packedLight);
            writeVertex(buffer, pose, -0.5F, 0.5F, leftU, topV, packedLight);
            return true;
        } finally {
            poseStack.popPose();
        }
    }

    /** 返回计划当前可绘制的槽位纹理或动态快照纹理。 */
    private static ResourceLocation resolveRenderableTexture(BillboardPlan plan) {
        if (plan.mode == RenderMode.SLOT_TEXTURE || plan.mode == RenderMode.DYNAMIC_PENDING_SLOT) {
            return plan.slotTexture;
        }
        if (plan.snapshotKey != null) {
            CachedSnapshot snapshot = SNAPSHOT_CACHE.get(plan.snapshotKey);
            if (snapshot != null) {
                return snapshot.textureId;
            }
        }
        return null;
    }

    /** 判断本次提交的纹理是否为 OpenGL RenderTarget 直接持有的动态快照。 */
    private static boolean isDynamicSnapshotTexture(BillboardPlan plan, ResourceLocation texture) {
        if (plan.snapshotKey == null) {
            return false;
        }
        CachedSnapshot snapshot = SNAPSHOT_CACHE.get(plan.snapshotKey);
        return snapshot != null && snapshot.textureId.equals(texture);
    }

    /**
     * 返回 Billboard 顶点的 U 坐标。
     *
     * <p>JOML 离屏视图与 Minecraft 相机朝向 Billboard 的屏幕右轴手性相反，因此只水平翻转动态快照。</p>
     */
    static float resolveTextureU(boolean renderTargetTexture, boolean rightVertex) {
        if (renderTargetTexture) {
            return rightVertex ? 0.0F : 1.0F;
        }
        return rightVertex ? 1.0F : 0.0F;
    }

    /**
     * 返回 Billboard 顶点的 V 坐标。
     *
     * <p>普通资源纹理以顶部为 V=0；RenderTarget 颜色附件以底部为 V=0，动态快照必须单独翻转。</p>
     */
    static float resolveTextureV(boolean renderTargetTexture, boolean topVertex) {
        if (renderTargetTexture) {
            return topVertex ? 1.0F : 0.0F;
        }
        return topVertex ? 0.0F : 1.0F;
    }

    /** 写入一个带世界光照、透明贴图和正向法线的 Billboard 顶点。 */
    private static void writeVertex(VertexConsumer buffer, PoseStack.Pose pose,
                                    float x, float y, float u, float v, int packedLight) {
        buffer.vertex(pose.pose(), x, y, 0.0F)
                .color(1.0F, 1.0F, 1.0F, 1.0F)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(packedLight)
                .normal(pose.normal(), 0.0F, 0.0F, 1.0F)
                .endVertex();
    }

    /** 在透明 256×256 RenderTarget 中绘制一份基础高模静态主体。 */
    private static boolean generateSnapshot(BillboardPlan plan) {
        Minecraft minecraft = Minecraft.getInstance();
        TextureTarget target = null;
        ResourceLocation textureId = null;
        boolean projectionBackedUp = false;
        boolean modelViewPushed = false;
        try {
            target = new TextureTarget(SNAPSHOT_SIZE, SNAPSHOT_SIZE, true, Minecraft.ON_OSX);
            target.setFilterMode(GlConst.GL_LINEAR);
            target.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);

            float structureSize = (float) Math.max(1.0D, plan.structureSize);
            float halfExtent = structureSize * SNAPSHOT_HALF_EXTENT_FACTOR;
            float cameraDistance = structureSize * 3.0F;
            Matrix4f projection = new Matrix4f().setOrtho(
                    -halfExtent, halfExtent, -halfExtent, halfExtent,
                    0.1F, structureSize * 8.0F);
            RenderSystem.backupProjectionMatrix();
            projectionBackedUp = true;
            RenderSystem.setProjectionMatrix(projection, VertexSorting.ORTHOGRAPHIC_Z);

            PoseStack modelView = RenderSystem.getModelViewStack();
            modelView.pushPose();
            modelViewPushed = true;
            modelView.setIdentity();
            RenderSystem.applyModelViewMatrix();

            Vector3f viewDirection = directionForBuckets(
                    plan.snapshotKey.yawBucket, plan.snapshotKey.pitchBucket);
            Vector3f eye = new Vector3f(viewDirection).mul(cameraDistance);
            Vector3f up = Math.abs(viewDirection.y) > 0.98F
                    ? new Vector3f(0.0F, 0.0F, 1.0F)
                    : new Vector3f(0.0F, 1.0F, 0.0F);
            Matrix4f view = new Matrix4f().lookAt(
                    eye, new Vector3f(0.0F, 0.0F, 0.0F), up);
            PoseStack snapshotPose = new PoseStack();
            snapshotPose.mulPoseMatrix(view);
            snapshotPose.translate(-plan.centerOffset.x, -plan.centerOffset.y, -plan.centerOffset.z);

            BakedModelInstance instance = plan.model.createBakedInstance();
            if (instance == null) {
                return false;
            }
            // 调用本体公开静态模型入口，把基础高模主体写入独立离屏缓冲；不执行动画或完整实体渲染链。
            plan.model.renderToBuffer(
                    instance,
                    snapshotPose,
                    SNAPSHOT_BUFFERS,
                    plan.modelTexture,
                    LightTexture.FULL_BRIGHT);
            SNAPSHOT_BUFFERS.endBatch();

            textureId = RVP_MOD.modLocation("remote_vehicle_snapshot/" + textureSequence++);
            CachedRenderTargetTexture texture = new CachedRenderTargetTexture(target.getColorTextureId());
            minecraft.getTextureManager().register(textureId, texture);
            evictOldestIfNeeded(minecraft);
            SNAPSHOT_CACHE.put(plan.snapshotKey, new CachedSnapshot(textureId, target));
            target = null;
            textureId = null;
            return true;
        } catch (RuntimeException exception) {
            return false;
        } finally {
            try {
                SNAPSHOT_BUFFERS.endBatch();
            } catch (RuntimeException ignored) {
                // 离屏模型在写入中异常时仍继续恢复全局渲染状态。
            }
            if (modelViewPushed) {
                PoseStack modelView = RenderSystem.getModelViewStack();
                modelView.popPose();
                RenderSystem.applyModelViewMatrix();
            }
            if (projectionBackedUp) {
                RenderSystem.restoreProjectionMatrix();
            }
            // 调用 Minecraft 主 RenderTarget，恢复离屏生成前的世界渲染目标与 viewport。
            minecraft.getMainRenderTarget().bindWrite(true);
            if (textureId != null) {
                minecraft.getTextureManager().release(textureId);
            }
            if (target != null) {
                target.destroyBuffers();
            }
        }
    }

    /** 在插入新快照前释放最久未使用的 GPU 条目。 */
    private static void evictOldestIfNeeded(Minecraft minecraft) {
        while (SNAPSHOT_CACHE.size() >= MAX_SNAPSHOT_CACHE_ENTRIES) {
            Iterator<Map.Entry<SnapshotKey, CachedSnapshot>> iterator = SNAPSHOT_CACHE.entrySet().iterator();
            if (!iterator.hasNext()) {
                return;
            }
            CachedSnapshot snapshot = iterator.next().getValue();
            iterator.remove();
            releaseSnapshot(minecraft, snapshot);
        }
    }

    /** 清除世界退出、维度切换或资源重载后遗留的全部动态快照资源。 */
    public static void clear() {
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.recordRenderCall(RVP_RemoteVehicleBillboardManager::clearOnRenderThread);
            return;
        }
        clearOnRenderThread();
    }

    /** 在渲染线程注销纹理并销毁全部 RenderTarget。 */
    private static void clearOnRenderThread() {
        Minecraft minecraft = Minecraft.getInstance();
        for (CachedSnapshot snapshot : SNAPSHOT_CACHE.values()) {
            releaseSnapshot(minecraft, snapshot);
        }
        SNAPSHOT_CACHE.clear();
        FAILED_SNAPSHOTS.clear();
        warnedSnapshotFailure = false;
    }

    /** 释放一份已缓存快照的纹理注册和帧缓冲。 */
    private static void releaseSnapshot(Minecraft minecraft, CachedSnapshot snapshot) {
        minecraft.getTextureManager().release(snapshot.textureId);
        snapshot.target.destroyBuffers();
    }

    /** 返回当前动态快照缓存大小，供回归测试与诊断使用。 */
    static int snapshotCacheSize() {
        return SNAPSHOT_CACHE.size();
    }

    /** 按载具当前三轴姿态把相机方向转换到载具局部空间并量化。 */
    private static int[] resolveViewBuckets(Vec3 center,
                                            Vec3 cameraPosition,
                                            float xRot,
                                            float yRot,
                                            float zRot) {
        Vec3 worldDirection = cameraPosition.subtract(center);
        if (worldDirection.lengthSqr() < 1.0E-8D) {
            return new int[]{0, 0};
        }
        Vector3f localDirection = worldDirection.normalize().toVector3f();
        Quaternionf vehicleRotation = new Quaternionf()
                .rotateY((float) Math.toRadians(-yRot))
                .rotateX((float) Math.toRadians(xRot))
                .rotateZ((float) Math.toRadians(zRot));
        vehicleRotation.invert().transform(localDirection);
        float yaw = (float) Math.toDegrees(Math.atan2(localDirection.x, localDirection.z));
        float pitch = (float) Math.toDegrees(Math.asin(Mth.clamp(localDirection.y, -1.0F, 1.0F)));
        return new int[]{quantizeYaw(yaw), quantizePitch(pitch)};
    }

    /** 把任意偏航角规范化为 0..15 的环绕分桶。 */
    static int quantizeYaw(float angleDegrees) {
        int bucket = Math.round(Mth.wrapDegrees(angleDegrees) / ANGLE_BUCKET_DEGREES);
        return Math.floorMod(bucket, YAW_BUCKET_COUNT);
    }

    /** 把任意俯仰角钳制并量化为 -4..4。 */
    static int quantizePitch(float angleDegrees) {
        int bucket = Math.round(Mth.clamp(angleDegrees, -90.0F, 90.0F) / ANGLE_BUCKET_DEGREES);
        return Mth.clamp(bucket, MIN_PITCH_BUCKET, MAX_PITCH_BUCKET);
    }

    /** 把角度分桶还原为离屏正交相机所在的载具局部单位方向。 */
    static Vector3f directionForBuckets(int yawBucket, int pitchBucket) {
        // 局部方向已经是“载具中心到观察者”，直接作为 lookAt 的 eye 方向，禁止再次叠加半圈偏航。
        float yaw = (float) Math.toRadians(yawBucket * ANGLE_BUCKET_DEGREES);
        float pitch = (float) Math.toRadians(pitchBucket * ANGLE_BUCKET_DEGREES);
        float horizontal = Mth.cos(pitch);
        return new Vector3f(
                horizontal * Mth.sin(yaw),
                Mth.sin(pitch),
                horizontal * Mth.cos(yaw));
    }

    /** 把结构尺寸量化到 1/16 格，避免浮点微差制造重复缓存键。 */
    private static int quantizeStructureSize(double structureSize) {
        return Math.max(16, Mth.ceil(Math.max(1.0D, structureSize) * 16.0D));
    }

    /** 候选最终渲染方式。 */
    enum RenderMode {
        /** 完整沿用原有 LOD/基础模型路径。 */
        NORMAL_MODEL,
        /** 使用 display 槽位缩略图。 */
        SLOT_TEXTURE,
        /** 使用已经缓存的动态快照。 */
        DYNAMIC_READY,
        /** 动态快照未就绪，当前隐藏。 */
        DYNAMIC_PENDING_HIDE,
        /** 动态快照未就绪，临时使用槽位缩略图。 */
        DYNAMIC_PENDING_SLOT,
        /** 动态快照未就绪，临时使用基础静态高模。 */
        DYNAMIC_PENDING_MODEL,
        /** Billboard 缺少资源或生成失败，使用基础静态高模。 */
        HIGH_MODEL_FALLBACK
    }

    /** 动态快照键在当前资源代际的缓存状态。 */
    enum SnapshotState {
        /** 已有可直接绘制的 GPU 快照。 */
        READY,
        /** 尚未生成，允许进入逐帧预热队列。 */
        MISSING,
        /** 已生成失败，本资源代际直接回退高模。 */
        FAILED
    }

    /**
     * 一辆候选载具的只读 Billboard 计划。
     * 所有字段都由管理器生成，主渲染器只查询模式并回传计划。
     */
    public static final class BillboardPlan {
        /** 当前计划模式。 */
        private final RenderMode mode;
        /** Billboard 世界中心。 */
        private final Vec3 center;
        /** 载具结构尺寸。 */
        private final double structureSize;
        /** 可选的 display 槽位缩略图。 */
        private final ResourceLocation slotTexture;
        /** 可选的动态快照缓存键。 */
        private final SnapshotKey snapshotKey;
        /** 动态快照使用的基础静态高模。 */
        private final VehicleBedrockModel model;
        /** 动态快照使用的基础模型纹理。 */
        private final ResourceLocation modelTexture;
        /** 模型旋转中心与离屏居中偏移。 */
        private final Vec3 centerOffset;

        private BillboardPlan(RenderMode mode, Vec3 center, double structureSize,
                              ResourceLocation slotTexture, SnapshotKey snapshotKey,
                              VehicleBedrockModel model, ResourceLocation modelTexture,
                              Vec3 centerOffset) {
            this.mode = mode;
            this.center = center;
            this.structureSize = structureSize;
            this.slotTexture = slotTexture;
            this.snapshotKey = snapshotKey;
            this.model = model;
            this.modelTexture = modelTexture;
            this.centerOffset = centerOffset;
        }

        /** 创建完全沿用原模型路径的计划。 */
        private static BillboardPlan normal() {
            return new BillboardPlan(RenderMode.NORMAL_MODEL, Vec3.ZERO, 1.0D,
                    null, null, null, null, Vec3.ZERO);
        }

        /** 创建基础静态高模降级计划。 */
        private static BillboardPlan highModel() {
            return new BillboardPlan(RenderMode.HIGH_MODEL_FALLBACK, Vec3.ZERO, 1.0D,
                    null, null, null, null, Vec3.ZERO);
        }

        /** 创建槽位缩略图计划。 */
        private static BillboardPlan slot(Vec3 center, double structureSize,
                                          ResourceLocation slotTexture) {
            return new BillboardPlan(RenderMode.SLOT_TEXTURE, center, structureSize,
                    slotTexture, null, null, null, Vec3.ZERO);
        }

        /** 创建动态快照或其预热计划。 */
        private static BillboardPlan dynamic(RenderMode mode, Vec3 center, double structureSize,
                                             ResourceLocation slotTexture, SnapshotKey snapshotKey,
                                             VehicleBedrockModel model, ResourceLocation modelTexture,
                                             Vec3 centerOffset) {
            return new BillboardPlan(mode, center, structureSize, slotTexture, snapshotKey,
                    model, modelTexture, centerOffset);
        }
    }

    /** 动态快照稳定缓存键。 */
    private record SnapshotKey(ResourceLocation vehicleId,
                               ResourceLocation displayId,
                               ResourceLocation modelId,
                               ResourceLocation texture,
                               int structureSizeSixteenths,
                               int yawBucket,
                               int pitchBucket) {
    }

    /** 已注册纹理与其所有权 RenderTarget。 */
    private record CachedSnapshot(ResourceLocation textureId, TextureTarget target) {
    }

    /**
     * 把 RenderTarget 已有颜色纹理暴露给 TextureManager。
     * 纹理的创建与删除仍由 RenderTarget 独占，因此该包装器不得释放 OpenGL ID。
     */
    private static final class CachedRenderTargetTexture extends AbstractTexture {
        /** 绑定已经由 RenderTarget 创建的颜色纹理 ID。 */
        private CachedRenderTargetTexture(int textureId) {
            this.id = textureId;
        }

        /** RenderTarget 纹理不从资源包加载。 */
        @Override
        public void load(ResourceManager resourceManager) throws IOException {
        }

        /** 防止 TextureManager 注销时重复删除 RenderTarget 拥有的纹理。 */
        @Override
        public void releaseId() {
        }

        /** 防止 TextureManager 关闭包装器时重复删除 RenderTarget。 */
        @Override
        public void close() {
        }
    }
}
