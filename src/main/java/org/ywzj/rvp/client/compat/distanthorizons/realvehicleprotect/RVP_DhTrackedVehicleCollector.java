package org.ywzj.rvp.client.compat.distanthorizons.realvehicleprotect;

import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BakedModelInstance;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.ywzj.rvp.client.compat.distanthorizons.RVP_DhCompatDiagnostics;
import org.ywzj.rvp.config.RVP_ClientConfig;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;
import org.ywzj.vehicle.client.resource.vehicle.BaseDisplay;
import org.ywzj.vehicle.client.resource.vehicle.VehicleBedrockModel;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 扫描客户端真实载具，并按视锥、第一人称语义和稳定预算建立保护层计划。 */
public final class RVP_DhTrackedVehicleCollector {
    /** 被摧毁载具使用的暗化光照除数，与本体正常渲染保持一致。 */
    private static final float DESTROYED_LIGHT_DIVISOR = 1.5F;

    private RVP_DhTrackedVehicleCollector() {
    }

    /** 收集当前客户端世界真实载具；没有候选或配置关闭时返回 {@code null}。 */
    public static RVP_DhTrackedVehicleFramePlan collect(RenderLevelStageEvent event) {
        if (!RVP_ClientConfig.shouldProtectDistantHorizonsTrackedVehicles()) {
            RVP_DhTrackedVehicleModelCache.clear();
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            RVP_DhTrackedVehicleModelCache.clear();
            return null;
        }

        RVP_DhTrackedVehicleModelCache.beginFrame();
        List<PreCandidate> preCandidates = new ArrayList<>();
        int loadedCount = 0;
        Vec3 cameraPosition = event.getCamera().getPosition();
        float partialTick = event.getPartialTick();
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof AbstractVehicle vehicle)
                    || !vehicle.isAlive() || vehicle.isRemoved() || vehicle.centerOffset == null) {
                continue;
            }
            BaseDisplay display = ClientAssetsManager.INSTANCE
                    .getVehicleDisplay(vehicle.getDisplayId()).orElse(null);
            if (display == null || display.getTexture() == null) {
                continue;
            }
            VehicleBedrockModel model = display.getModel();
            if (model == null || !model.hasBakedModel()
                    || vehicle.getVehicleModelInstance() == null) {
                continue;
            }
            loadedCount++;
            if (isFirstPersonLocalVehicle(vehicle)) {
                continue;
            }
            // 调用原版实体调度器的可见性判定，保持实体渲染距离与视锥语义一致。
            if (!minecraft.getEntityRenderDispatcher().shouldRender(vehicle, event.getFrustum(),
                    cameraPosition.x, cameraPosition.y, cameraPosition.z)) {
                continue;
            }
            Vec3 renderPosition = interpolatedPosition(vehicle, partialTick);
            double distanceSquared = renderPosition.distanceToSqr(cameraPosition);
            if (!Double.isFinite(distanceSquared)) {
                continue;
            }
            AABB box = vehicle.getBoundingBoxForCulling();
            double projectedSize = Math.max(box.getXsize(), Math.max(box.getYsize(), box.getZsize()));
            double contribution = projectedSize * projectedSize / Math.max(1.0D, distanceSquared);
            preCandidates.add(new PreCandidate(vehicle, renderPosition, model,
                    display.getTexture(), packedLight(vehicle, partialTick),
                    distanceSquared, contribution));
        }
        if (preCandidates.isEmpty()) {
            RVP_DhTrackedVehicleModelCache.endFrame();
            return null;
        }

        int limit = RVP_ClientConfig.getDistantHorizonsMaxProtectedTrackedVehicles();
        if (preCandidates.size() > limit) {
            RVP_DhCompatDiagnostics.warnOnce("TRACKED_CANDIDATE_LIMIT_REACHED",
                    "loaded=" + preCandidates.size() + ", limit=" + limit);
        }
        Map<Integer, PreCandidate> contexts = new HashMap<>();
        List<RVP_DhTrackedVehicleBudget.Candidate> budgetCandidates = new ArrayList<>();
        for (PreCandidate candidate : preCandidates) {
            int entityId = candidate.vehicle().getId();
            contexts.put(entityId, candidate);
            budgetCandidates.add(new RVP_DhTrackedVehicleBudget.Candidate(entityId,
                    candidate.distanceSquared(), candidate.screenContribution()));
        }
        // 调用纯逻辑预算选择器，以实体 ID 作为最终 tie-break，避免逐帧候选抖动。
        List<PreCandidate> selectedPreCandidates = RVP_DhTrackedVehicleBudget
                .select(budgetCandidates, limit).stream()
                .map(candidate -> contexts.get(candidate.entityId()))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(ArrayList::new));
        List<RVP_DhTrackedVehicleFramePlan.Candidate> candidates = new ArrayList<>();
        for (PreCandidate candidate : selectedPreCandidates) {
            // 调用本项目保护模型缓存，只为预算入选载具冻结姿态，避免超额候选占用复制成本。
            BakedModelInstance snapshot = RVP_DhTrackedVehicleModelCache.snapshot(
                    candidate.vehicle(), candidate.model(), candidate.vehicle().getDisplayId());
            if (snapshot == null) {
                RVP_DhCompatDiagnostics.warnOnce("TRACKED_MODEL_UNAVAILABLE",
                        "entity=" + candidate.vehicle().getId()
                                + ", display=" + candidate.vehicle().getDisplayId());
                continue;
            }
            candidates.add(new RVP_DhTrackedVehicleFramePlan.Candidate(
                    candidate.vehicle(), candidate.position(), candidate.model(), snapshot,
                    candidate.texture(), candidate.packedLight(), candidate.distanceSquared(),
                    candidate.screenContribution()));
        }
        RVP_DhTrackedVehicleModelCache.endFrame();
        if (candidates.isEmpty()) {
            return null;
        }
        PoseStack savedPoseStack = new PoseStack();
        savedPoseStack.mulPoseMatrix(new Matrix4f(event.getPoseStack().last().pose()));
        return new RVP_DhTrackedVehicleFramePlan(level, candidates, loadedCount, cameraPosition,
                savedPoseStack, event.getProjectionMatrix(), partialTick);
    }

    /** 第一人称座舱与瞄准镜排除本机外壳，第三人称仍允许保护。 */
    private static boolean isFirstPersonLocalVehicle(AbstractVehicle vehicle) {
        return LocalVehiclePlayer.instance.vehicle == vehicle
                && LocalVehiclePlayer.instance.viewType != LocalVehiclePlayer.ViewType.THIRD_PERSON;
    }

    /** 按当前 partial tick 冻结实体位置，避免 DH 回调读取下一时刻状态。 */
    private static Vec3 interpolatedPosition(AbstractVehicle vehicle, float partialTick) {
        return new Vec3(Mth.lerp(partialTick, vehicle.xo, vehicle.getX()),
                Mth.lerp(partialTick, vehicle.yo, vehicle.getY()),
                Mth.lerp(partialTick, vehicle.zo, vehicle.getZ()));
    }

    /** 使用正常实体渲染的取光位置与渲染器规则，并复用本体对摧毁载具的暗化语义。 */
    private static int packedLight(AbstractVehicle vehicle, float partialTick) {
        // 调用原版实体调度器的取光入口，间接使用本体 getLightProbePosition 的主碰撞盒中心。
        // 载具物理原点可能略低于地表；直接按 blockPosition 取光会采到实心地块，把整车副本压黑。
        // 此入口也保留实体渲染器的着火补光与可覆写取光规则，不硬编码高度或最低亮度。
        int light = Minecraft.getInstance().getEntityRenderDispatcher()
                .getPackedLightCoords(vehicle, partialTick);
        if (!vehicle.isDestroyed()) {
            return light;
        }
        int blockLight = (int) (LightTexture.block(light) / DESTROYED_LIGHT_DIVISOR);
        int skyLight = (int) (LightTexture.sky(light) / DESTROYED_LIGHT_DIVISOR);
        return LightTexture.pack(blockLight, skyLight);
    }

    /** 预算前候选；不持有保护模型副本，避免未入选实体产生复制成本。 */
    private record PreCandidate(AbstractVehicle vehicle,
                                Vec3 position,
                                VehicleBedrockModel model,
                                net.minecraft.resources.ResourceLocation texture,
                                int packedLight,
                                double distanceSquared,
                                double screenContribution) {
    }
}
