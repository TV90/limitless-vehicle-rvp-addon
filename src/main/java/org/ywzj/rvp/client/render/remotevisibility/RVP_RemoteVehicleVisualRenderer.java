package org.ywzj.rvp.client.render.remotevisibility;

import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BakedModelInstance;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.render.RVP_DistanceBoneHider;
import org.ywzj.rvp.client.render.RVP_LodModelManager;
import org.ywzj.rvp.client.render.remotevisibility.RVP_RemoteVehicleProjection.FarPlaneDemand;
import org.ywzj.rvp.client.render.remotevisibility.RVP_RemoteVehicleProjection.ProjectionPlan;
import org.ywzj.rvp.client.state.remotevisibility.RVP_ClientRemoteVehicleVisualState;
import org.ywzj.rvp.config.RVP_ClientConfig;
import org.ywzj.vehicle.client.render.entity.vehicle.VehicleRender;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;
import org.ywzj.vehicle.client.resource.vehicle.BaseDisplay;
import org.ywzj.vehicle.client.resource.vehicle.VehicleBedrockModel;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.joml.Matrix4f;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 在正常实体之后直接绘制服务端授权的远距载具静态主体。 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_RemoteVehicleVisualRenderer {
    /** 原生实体追踪与扩展视觉的水平接管边界，单位格。 */
    private static final double NATIVE_TRACKING_BOUNDARY = 512.0D;
    /** 原生实体追踪与扩展视觉的水平接管边界平方。 */
    private static final double NATIVE_TRACKING_BOUNDARY_SQ =
            NATIVE_TRACKING_BOUNDARY * NATIVE_TRACKING_BOUNDARY;
    /** 客户端没有目标区块时使用的受控中性光照。 */
    private static final int UNLOADED_CHUNK_LIGHT = LightTexture.pack(8, 10);
    /** 远距载具独立批次的初始缓冲容量，缓冲不足时会由 BufferBuilder 自动扩容。 */
    private static final int REMOTE_BUFFER_INITIAL_CAPACITY = 256;
    /** 远距载具专用缓冲，防止切换投影时提交其他世界渲染器遗留的顶点。 */
    private static final MultiBufferSource.BufferSource REMOTE_BUFFERS =
            MultiBufferSource.immediate(new BufferBuilder(REMOTE_BUFFER_INITIAL_CAPACITY));
    /** 客户端受控诊断日志。 */
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 是否已经报告过当前运行期不受支持的投影，避免逐帧刷日志。 */
    private static boolean warnedUnsupportedProjection;

    private RVP_RemoteVehicleVisualRenderer() {
    }

    /** 在 AFTER_ENTITIES 阶段先裁剪预算，再解析并绘制静态主体。 */
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }

        Vec3 cameraPosition = event.getCamera().getPosition();
        List<CandidateContext> preCandidates = new ArrayList<>();
        List<FarPlaneDemand> farPlaneDemands = new ArrayList<>();
        for (RVP_ClientRemoteVehicleVisualState.RenderEntry entry
                : RVP_ClientRemoteVehicleVisualState.renderEntries(level, event.getPartialTick())) {
            if (level.getEntity(entry.entityId()) != null) {
                continue;
            }
            double horizontalDistanceSquared = horizontalDistanceSquared(entry.position(), cameraPosition);
            if (!Double.isFinite(horizontalDistanceSquared)
                    || horizontalDistanceSquared <= NATIVE_TRACKING_BOUNDARY_SQ) {
                continue;
            }
            double distanceSquared = entry.position().distanceToSqr(cameraPosition);
            if (!Double.isFinite(distanceSquared)) {
                continue;
            }
            double rawStructureSize = entry.proxy().getStructureLength();
            if (!Double.isFinite(rawStructureSize)) {
                continue;
            }
            double structureSize = Math.max(1.0D, rawStructureSize);
            double cullRadius = Math.sqrt(3.0D) * structureSize * 0.5D;
            if (!Double.isFinite(cullRadius)
                    || cullRadius > RVP_RemoteVehicleProjection.MAX_CULL_RADIUS) {
                continue;
            }
            AABB cullingBox = AABB.ofSize(entry.position(), structureSize, structureSize, structureSize);
            boolean fallbackHighModel = !RVP_LodModelManager.hasRemoteLod(entry.proxy());
            double contribution = structureSize * structureSize / Math.max(1.0D, distanceSquared);
            double cameraDistance = Math.sqrt(distanceSquared);
            preCandidates.add(new CandidateContext(entry, cameraDistance, cullingBox,
                    contribution, fallbackHighModel));
            farPlaneDemands.add(new FarPlaneDemand(cameraDistance, cullRadius));
        }
        if (preCandidates.isEmpty()) {
            return;
        }

        // 调用 RVP 投影辅助，按本帧实际授权预候选计算受硬上限保护的动态远平面。
        ProjectionPlan projectionPlan = RVP_RemoteVehicleProjection
                .plan(event.getProjectionMatrix(), farPlaneDemands).orElse(null);
        Frustum remoteFrustum = event.getFrustum();
        if (projectionPlan != null && projectionPlan.extended()) {
            Matrix4f viewMatrix = new Matrix4f(event.getPoseStack().last().pose());
            remoteFrustum = new Frustum(viewMatrix, projectionPlan.projection());
            remoteFrustum.prepare(cameraPosition.x, cameraPosition.y, cameraPosition.z);
        } else if (projectionPlan == null && !warnedUnsupportedProjection) {
            warnedUnsupportedProjection = true;
            LOGGER.warn("RVP 远距载具无法安全扩展当前非标准投影，已回退原世界投影与 Frustum；"
                    + "这通常表示光影模组替换了透视矩阵");
        }

        Map<Integer, CandidateContext> contexts = new HashMap<>();
        List<RVP_RemoteVehicleRenderBudget.Candidate> budgetCandidates = new ArrayList<>();
        for (CandidateContext context : preCandidates) {
            if (!remoteFrustum.isVisible(context.cullingBox())) {
                continue;
            }
            RVP_ClientRemoteVehicleVisualState.RenderEntry entry = context.entry();
            budgetCandidates.add(new RVP_RemoteVehicleRenderBudget.Candidate(
                    entry.entityId(), context.cameraDistance() * context.cameraDistance(),
                    context.screenContribution(), context.fallbackHighModel()));
            contexts.put(entry.entityId(), context);
        }

        List<RVP_RemoteVehicleRenderBudget.Candidate> selected = RVP_RemoteVehicleRenderBudget.select(
                budgetCandidates,
                RVP_ClientConfig.getRemoteVehicleMaxRenderedVehicles(),
                RVP_ClientConfig.getRemoteVehicleMaxFallbackHighModels());
        if (selected.isEmpty()) {
            return;
        }

        if (projectionPlan == null) {
            renderSelected(level, selected, contexts, cameraPosition, event.getPoseStack());
            return;
        }
        // 调用 RVP 渲染作用域，在隔离批次期间临时应用并最终恢复投影与完整雾状态。
        try (RVP_RemoteVehicleRenderScope ignored =
                     RVP_RemoteVehicleRenderScope.open(projectionPlan, event.getCamera())) {
            renderSelected(level, selected, contexts, cameraPosition, event.getPoseStack());
        }
    }

    /** 在当前投影和雾状态下写入并提交隔离的远距载具批次。 */
    private static void renderSelected(ClientLevel level,
                                       List<RVP_RemoteVehicleRenderBudget.Candidate> selected,
                                       Map<Integer, CandidateContext> contexts,
                                       Vec3 cameraPosition, PoseStack poseStack) {
        try {
            for (RVP_RemoteVehicleRenderBudget.Candidate candidate : selected) {
                CandidateContext context = contexts.get(candidate.entityId());
                if (context != null) {
                    renderVehicle(level, context, cameraPosition, poseStack, REMOTE_BUFFERS);
                }
            }
        } finally {
            // 调用本体模型写入所使用的 RVP 隔离缓冲提交入口，确保扩展状态恢复前完成 GPU 绘制。
            REMOTE_BUFFERS.endBatch();
        }
    }

    /** 直接绘制 LOD 或静态原模型主体，不进入本体完整 EntityRenderer。 */
    private static boolean renderVehicle(ClientLevel level, CandidateContext context, Vec3 cameraPosition,
                                         PoseStack poseStack, MultiBufferSource.BufferSource buffers) {
        RVP_ClientRemoteVehicleVisualState.RenderEntry entry = context.entry;
        AbstractVehicle proxy = entry.proxy();
        BaseDisplay display = ClientAssetsManager.INSTANCE
                .getVehicleDisplay(proxy.getDisplayId()).orElse(null);
        if (display == null || display.getModel() == null || display.getTexture() == null
                || proxy.centerOffset == null) {
            return false;
        }

        RVP_LodModelManager.VehicleLodState lodState = RVP_LodModelManager.resolveRemote(
                proxy, context.cameraDistance, entry.heightAboveGround());
        VehicleBedrockModel model;
        BakedModelInstance instance;
        ResourceLocation texture;
        if (lodState != null) {
            model = lodState.model;
            instance = lodState.instance;
            texture = lodState.texture;
        } else {
            model = display.getModel();
            // 调用本体载具模型实例访问器，为无远距 LOD 的代理载具复用其独立静态模型实例。
            instance = proxy.getVehicleModelInstance();
            texture = display.getTexture();
            if (!model.hasBakedModel() || instance == null) {
                return false;
            }
            // 调用 RVP 显式距离骨骼入口，在静态原模型回退中复用 display 的既有规则。
            RVP_DistanceBoneHider.apply(proxy, instance, context.cameraDistance);
        }
        if (model == null || instance == null || texture == null || !model.hasBakedModel()) {
            return false;
        }

        int packedLight = packedLight(level, entry.position(), entry.destroyed());
        ProxyPose oldPose = ProxyPose.capture(proxy);
        applyRenderPose(proxy, entry);
        poseStack.pushPose();
        try {
            poseStack.translate(entry.position().x - cameraPosition.x,
                    entry.position().y - cameraPosition.y,
                    entry.position().z - cameraPosition.z);
            // 调用本体公开旋转辅助，以与正常载具保持完全一致的 Y-X-Z 枢轴旋转顺序。
            VehicleRender.applyVehicleRotation(proxy, 1.0F, poseStack);
            model.renderToBuffer(instance, poseStack, buffers, texture, packedLight);
            return true;
        } finally {
            poseStack.popPose();
            oldPose.restore(proxy);
        }
    }

    /** 已加载区块读取世界光照；未加载区块使用中性光照且不触发区块加载。 */
    private static int packedLight(ClientLevel level, Vec3 position, boolean destroyed) {
        BlockPos blockPos = BlockPos.containing(position);
        int light = level.hasChunkAt(blockPos)
                ? LevelRenderer.getLightColor(level, blockPos)
                : UNLOADED_CHUNK_LIGHT;
        if (!destroyed) {
            return light;
        }
        int blockLight = (int) (LightTexture.block(light) / 1.5F);
        int skyLight = (int) (LightTexture.sky(light) / 1.5F);
        return LightTexture.pack(blockLight, skyLight);
    }

    /** 临时应用状态层已经完成插值的三轴姿态，禁止再按速度推算朝向。 */
    private static void applyRenderPose(AbstractVehicle proxy,
                                        RVP_ClientRemoteVehicleVisualState.RenderEntry entry) {
        proxy.setPos(entry.position());
        proxy.xo = entry.position().x;
        proxy.yo = entry.position().y;
        proxy.zo = entry.position().z;
        proxy.setXRot(entry.xRot());
        proxy.setYRot(entry.yRot());
        proxy.setZRot(entry.zRot());
        proxy.xRotO = entry.xRot();
        proxy.yRotO = entry.yRot();
        proxy.zRotO = entry.zRot();
    }

    /** 计算相机与载具之间的水平距离平方。 */
    private static double horizontalDistanceSquared(Vec3 first, Vec3 second) {
        double dx = first.x - second.x;
        double dz = first.z - second.z;
        return dx * dx + dz * dz;
    }

    /**
     * 已通过预算前裁剪的渲染上下文。
     *
     * @param entry 插值后的代理条目
     * @param cameraDistance 相机到代理的三维距离，单位格
     * @param cullingBox 覆盖静态主体的世界坐标裁剪包围盒
     * @param screenContribution 结构尺寸相对距离得到的屏幕贡献分数
     * @param fallbackHighModel 是否没有整模型 LOD、需要使用静态原模型回退
     */
    private record CandidateContext(RVP_ClientRemoteVehicleVisualState.RenderEntry entry,
                                    double cameraDistance, AABB cullingBox,
                                    double screenContribution, boolean fallbackHighModel) {
    }

    /** 渲染临时改写前的代理位置与三轴姿态。 */
    private static final class ProxyPose {
        /** 当前世界位置。 */
        private final Vec3 position;
        /** 上一帧 X 坐标。 */
        private final double xo;
        /** 上一帧 Y 坐标。 */
        private final double yo;
        /** 上一帧 Z 坐标。 */
        private final double zo;
        /** 当前俯仰角。 */
        private final float xRot;
        /** 当前偏航角。 */
        private final float yRot;
        /** 当前滚转角。 */
        private final float zRot;
        /** 上一帧俯仰角。 */
        private final float xRotO;
        /** 上一帧偏航角。 */
        private final float yRotO;
        /** 上一帧滚转角。 */
        private final float zRotO;

        private ProxyPose(Vec3 position, double xo, double yo, double zo,
                          float xRot, float yRot, float zRot,
                          float xRotO, float yRotO, float zRotO) {
            this.position = position;
            this.xo = xo;
            this.yo = yo;
            this.zo = zo;
            this.xRot = xRot;
            this.yRot = yRot;
            this.zRot = zRot;
            this.xRotO = xRotO;
            this.yRotO = yRotO;
            this.zRotO = zRotO;
        }

        /** 捕获代理原始状态。 */
        private static ProxyPose capture(AbstractVehicle proxy) {
            return new ProxyPose(proxy.position(), proxy.xo, proxy.yo, proxy.zo,
                    proxy.getXRot(), proxy.getYRot(), proxy.getZRot(),
                    proxy.xRotO, proxy.yRotO, proxy.zRotO);
        }

        /** 在渲染成功或异常后恢复代理状态，防止污染缓存。 */
        private void restore(AbstractVehicle proxy) {
            proxy.setPos(position);
            proxy.xo = xo;
            proxy.yo = yo;
            proxy.zo = zo;
            proxy.setXRot(xRot);
            proxy.setYRot(yRot);
            proxy.setZRot(zRot);
            proxy.xRotO = xRotO;
            proxy.yRotO = yRotO;
            proxy.zRotO = zRotO;
        }
    }
}
