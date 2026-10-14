package org.ywzj.rvp.mixin;

import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakedBedrockModel;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakedGeometryChunk;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BakedModelInstance;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BoneState;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.ywzj.rvp.client.cockpit.RVP_CockpitDiag;
import org.ywzj.rvp.client.cockpit.RVP_CockpitScreenManager;
import org.ywzj.rvp.client.render.RVP_CockpitPassengerRenderContext;
import org.ywzj.rvp.client.render.RVP_CockpitPassengerRenderer;
import org.ywzj.rvp.client.render.RVP_DistanceBoneHider;
import org.ywzj.rvp.client.render.RVP_RenderTypes;
import org.ywzj.rvp.client.resource.RVP_DisplayTransparentModeManager;
import org.ywzj.rvp.client.resource.vehicle.RVP_DisplayBackendUtil;
import org.ywzj.vehicle.client.render.ModRenderTypes;
import org.ywzj.vehicle.client.resource.vehicle.SpecialBoneEffect;
import org.ywzj.vehicle.client.resource.vehicle.VehicleBedrockModel;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;

import java.util.List;

@Mixin(value = VehicleBedrockModel.class, remap = false)
public abstract class VehicleBedrockModelCockpitRenderMixin {

    @Shadow(remap = false)
    private List<VehicleBedrockModel.BakedSpecialBoneEntry> bakedSpecialBoneEntries;

    @Shadow(remap = false)
    public abstract BakedBedrockModel getBakedModel();

    /** 屏骨渲染诊断节拍（每秒一条汇总）。 */
    private static long lastRenderDiagMs;

    /** 屏骨渲染诊断汇总行（与节拍同帧复用）。 */
    private static final StringBuilder diagLine = new StringBuilder();

    /**
     * 二分定位开关：true=屏骨画无纹理洋红 quad（零纹理采样依赖），
     * 用于劈开"渲染路径 vs 纹理采样"的问题域；定位完成后置 false。
     */
    private static boolean PURE_COLOR_TEST = true;

    /**
     * @author Codex
     * @reason Keep RVP transparent/cockpit rendering on top of the latest baked SBM pipeline while letting non-RVP displays use the base render behavior.
     */
    @Overwrite(remap = false)
    @OnlyIn(Dist.CLIENT)
    public void renderSpecialBones(BakedModelInstance instance,
                                        PoseStack poseStack,
                                        MultiBufferSource source,
                                        ResourceLocation texture,
                                        int packedLight,
                                        int packedOverlay,
                                        List<BoneState> invisibleBones,
                                        boolean isLocalPlayerVehicle) {
        BakedBedrockModel bakedModel = getBakedModel();
        if (bakedModel == null || bakedSpecialBoneEntries.isEmpty()) {
            return;
        }
        VehicleBedrockModel self = (VehicleBedrockModel) (Object) this;
        boolean rvpBackend = RVP_DisplayBackendUtil.isRvpBackend(self);
        boolean hasCockpitDepthFix = rvpBackend && RVP_DisplayTransparentModeManager.INSTANCE.hasCockpitDepthFix(self);
        for (VehicleBedrockModel.BakedSpecialBoneEntry entry : bakedSpecialBoneEntries) {
            BoneState bone = instance.getBone(entry.boneIndex());
            if (bone != null) {
                bone.visible = true;
            }
        }
        if (invisibleBones != null) {
            invisibleBones.forEach(invisibleBone -> invisibleBone.visible = false);
        }
        if (hasCockpitDepthFix) {
            RVP_CockpitPassengerRenderer.renderLocalPassengerBeforeCockpit(self, poseStack, source, packedLight);
        }
        // 排查期诊断节拍（方法级一次，每秒一条汇总；循环内所有条目共用同一拍）。
        boolean diagNow = RVP_CockpitDiag.isEnabled()
                && System.currentTimeMillis() - lastRenderDiagMs >= 1000;
        if (diagNow) {
            lastRenderDiagMs = System.currentTimeMillis();
            diagLine.setLength(0);
            diagLine.append("特殊骨渲染 ");
        }
        for (VehicleBedrockModel.BakedSpecialBoneEntry entry : bakedSpecialBoneEntries) {
            // 距离 LOD：该特殊骨骼（半透明座舱盖等）已到隐藏距离时保留网格、
            // 改用不透明材质渲染——既遮挡座舱丢失造成的难看，又去掉半透明排序开销。
            boolean distanceHidden = RVP_DistanceBoneHider.isBoneIndexHidden(instance, entry.boneIndex());
            SpecialBoneEffect effect = entry.effect();
            if (diagNow) {
                diagLine.append(effect.bone).append("[type=").append(effect.type)
                        .append(" idx=").append(entry.boneIndex());
                var bakedBone = getBakedModel().bones()[entry.boneIndex()];
                diagLine.append(" hasVerts=").append(bakedBone.hasVerticesInTree())
                        .append(" hasQuads=").append(bakedBone.hasQuadsInTree());
                var boneState = instance.getBone(entry.boneIndex());
                diagLine.append(" state=").append(boneState != null)
                        .append(" vis=").append(boneState != null && boneState.visible)
                        .append(']');
            }
            if (effect.type == SpecialBoneEffect.SpecialBoneEffectType.COCKPIT
                    && isLocalPlayerVehicle
                    && LocalVehiclePlayer.instance.viewType == LocalVehiclePlayer.ViewType.OPERATOR) {
                continue;
            }
            // 与本体 renderSpecialBones 保持一致：无显式纹理的效果继承渲染参数里的载具整体纹理，
            // 否则 effect.texture 为 null 时 Util.memoize 的 ConcurrentHashMap.computeIfAbsent 会拒收 null key 抛 NPE。
            if (effect.texture != null) {
                texture = effect.texture;
            }
            boolean cockpitDepthFix = hasCockpitDepthFix
                    && RVP_DisplayTransparentModeManager.INSTANCE.isCockpitDepthFix(self, effect.bone);
            RenderType quadType;
            RenderType meshType;
            switch (effect.type) {
                case MUZZLE_FLASH -> {
                    quadType = ModRenderTypes.muzzleFlash(texture);
                    meshType = ModRenderTypes.muzzleFlash(texture);
                }
                case TRANSPARENT, COCKPIT -> {
                    // 座舱屏骨（texture=rvp:cockpit/*）强制"不透明+自发光"：半透明通道的
                    // 片元深度排序对屏骨不可用（与座舱玻璃互相吞面/闪烁），且透明片元写
                    // 深度留空洞；详见 RVP_RenderTypes 屏骨类型注释（2026-10-14 定档）。
                    if (RVP_CockpitScreenManager.isCockpitScreenTexture(effect.texture)) {
                        quadType = RVP_RenderTypes.cubeScreenOpaque(texture);
                        meshType = RVP_RenderTypes.polyMeshScreenOpaque(texture);
                    } else if (distanceHidden) {
                        quadType = RVP_RenderTypes.cubeCutout(texture);
                        meshType = RVP_RenderTypes.polyMeshCutout(texture);
                    } else if (rvpBackend) {
                        quadType = cockpitDepthFix
                                ? RVP_RenderTypes.cubeCockpitTransparent(texture)
                                : RVP_RenderTypes.cubeTransparent(texture);
                        meshType = cockpitDepthFix
                                ? RVP_RenderTypes.polyMeshCockpitTransparent(texture)
                                : RVP_RenderTypes.polyMeshTransparent(texture);
                    } else {
                        quadType = ModRenderTypes.cubeTransparent(texture);
                        meshType = ModRenderTypes.polyMeshTransparent(texture);
                    }
                }
                default -> {
                    continue;
                }
            }
            boolean screenBone = RVP_CockpitScreenManager.isCockpitScreenTexture(effect.texture);
            if (!screenBone) {
                instance.renderSingleBone(poseStack, entry.boneIndex(), source, quadType, meshType, packedLight,
                        packedOverlay, 1.0F, 1.0F, 1.0F, 1.0F, false);
            }
            // 座舱屏骨双保险直画（2026-10-14 用户实验实锤：删除 display 条目后屏骨作为普通骨
            // 渲染完全正常=主通道 renderBoneTree→renderBone→translateAndRotateAndScale 路径
            // 在本模型可用，而特殊通道 renderSingleBone/mulParentGlobalTransform 链无视觉产出）。
            // 此处复刻主通道成功路径：屏骨自身姿态 + sourceBones 匹配的 mesh chunk 直画。
            if (screenBone) {
                // 距离钳制（2026-10-15 用户定案）：屏内容只画给"距本地玩家 N 格内"的载具——
                // ①开改装/武器界面、暂停时本体 onVehicle 为 false，但载具就在身边 ⇒ 屏继续刷新不熄灭；
                // ②远离的载具屏骨不再被重复画上本车 RT（此前每帧给场上所有带屏载具都画，
                //   骨直绘帧率虚高、观感串味）。渲染上下文缺失时保持旧行为（无法判定则不钳制）。
                var cockpitCtx = RVP_CockpitPassengerRenderContext.current();
                if (cockpitCtx != null
                        && !RVP_CockpitScreenManager.isScreenRenderTarget(cockpitCtx.vehicle())) {
                    continue;
                }
                // 实体缓存的模型实例可能与 display 当前模型失配（F3+T/重载后 display 重建而
                // 实体 vehicleModelInstance 仍指向旧烘焙模型）——失配时屏骨改用 display 配套
                // 的默认实例（屏骨为静止骨，无动画副作用）。
                BakedModelInstance renderInstance = instance;
                boolean sameModel = instance.baseModel() == getBakedModel();
                if (!sameModel) {
                    renderInstance = self.getDefaultModelInstance();
                }
                var boneState = renderInstance.getBone(entry.boneIndex());
                int matchedChunks = 0;
                if (boneState != null) {
                    // 找到屏骨几何所在 chunk
                    com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakedGeometryChunk
                            hitChunk = null;
                    for (var chunk : getBakedModel().meshChunks()) {
                        if (chunk == null || !chunk.hasVertices()) {
                            continue;
                        }
                        boolean hit = chunk.attachBoneIndex() == entry.boneIndex();
                        if (!hit && chunk.sourceBones() != null) {
                            for (String sourceBone : chunk.sourceBones()) {
                                if (effect.bone.equals(sourceBone)) {
                                    hit = true;
                                    break;
                                }
                            }
                        }
                        if (hit) {
                            hitChunk = chunk;
                            break;
                        }
                    }
                    if (hitChunk != null) {
                        matchedChunks = 1;
                        // 座舱屏骨直绘（2026-10-14b 回退至 20:32 基线）：Tesselator POSITION_TEX
                        // 立即绘制采样离屏 RT。已知限制：实体渲染流内的立即绘制路径存在显示
                        // 不稳定（详见交接文档），弹孔式 RenderType 批内方案曾尝试未成。
                        var target = RVP_CockpitScreenManager.getScreenTarget(effect.texture);
                        // 诊断键：rvp:cockpit/<kind> → kind（与 ScreenKind.path 对齐）
                        String screenKey = effect.texture.getPath().startsWith("cockpit/")
                                ? effect.texture.getPath().substring("cockpit/".length())
                                : effect.texture.toString();
                        var vtx = hitChunk.vertices();
                        float[] pos = vtx.positions();
                        float[] uvs = vtx.uvs();
                        int vertexCount = vtx.vertexCount();
                        if (target != null && pos != null && uvs != null && vertexCount > 0) {
                            // UV 语义（2026-10-15 用户定案：一切按模型 UV，不自动铺）：
                            // 默认直接采信模型 UV —— 展面决定画面取景/缩放/占比；
                            // 仅当 display 条目显式写 "ywzj_rvp_screen_uv_mode": "fill"
                            // 时才回退旧的「包围盒归一化铺满」语义（老配置逃生舱）。
                            // 注：RT 纹理 wrap=CLAMP_TO_EDGE，模型 UV 超界会边缘渗色而非重复。
                            boolean fillUv = RVP_CockpitScreenManager.isFillUvMode(effect.texture);
                            float uMin = Float.MAX_VALUE, vMin = Float.MAX_VALUE;
                            float uMax = -Float.MAX_VALUE, vMax = -Float.MAX_VALUE;
                            for (int i = 0; i < vertexCount; i++) {
                                float u = uvs[i * 2], v = uvs[i * 2 + 1];
                                uMin = Math.min(uMin, u);
                                uMax = Math.max(uMax, u);
                                vMin = Math.min(vMin, v);
                                vMax = Math.max(vMax, v);
                            }
                            float uSpan = Math.max(1.0E-5F, uMax - uMin);
                            float vSpan = Math.max(1.0E-5F, vMax - vMin);
                            poseStack.pushPose();
                            boneState.translateAndRotateAndScale(poseStack);
                            // 强制真实绑定 + 同步缓存：shader 应用时的 _bindTexture 走
                            // GlStateManager 缓存，缓存与真实 GL 状态被原生渲染 mod（Embeddium/
                            // Oculus）脱节后会命中跳过——采样到残留绑定纹理。
                            int glId = target.getColorTextureId();
                            org.lwjgl.opengl.GL11.glBindTexture(
                                    org.lwjgl.opengl.GL11.GL_TEXTURE_2D, glId);
                            com.mojang.blaze3d.platform.GlStateManager._bindTexture(glId);
                            RenderSystem.setShaderTexture(0, glId);
                            // 显式设置 shader（2026-10-14b 修复）：Tesselator.end 使用
                            // RenderSystem 当前 shader——不设置就会用到 ClientTick 残留的
                            // shader，与 POSITION_TEX 顶点格式不匹配时整批绘制失效（黑屏）。
                            RenderSystem.setShader(GameRenderer::getPositionTexShader);
                            // 无混合直写（2026-10-14b 终极定案）：RT 内容 alpha 通道存在写入
                            // 缺陷（圆盘区域 a=0 实测），启用混合时 a=0 区域会透出底下机体骨。
                            // disableBlend 后 alpha 通道无效，屏骨显示=RT 的 RGB 本身（黑就是
                            // 黑、地图就是地图），从物理上消灭透明问题。画完恢复混合供后续渲染。
                            RenderSystem.disableBlend();
                            RenderSystem.disableCull();
                            var tessellator = Tesselator.getInstance();
                            var buffer = tessellator.getBuilder();
                            buffer.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX);
                            Matrix4f matrix = poseStack.last().pose();
                            for (int i = 0; i < vertexCount; i++) {
                                // model 语义：UV 原值直采；fill 语义：包围盒归一化铺满
                                float u = fillUv ? (uvs[i * 2] - uMin) / uSpan : uvs[i * 2];
                                float v = fillUv ? (uvs[i * 2 + 1] - vMin) / vSpan : uvs[i * 2 + 1];
                                buffer.vertex(matrix, pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2])
                                        .uv(u, v).endVertex();
                            }
                            tessellator.end();
                            // 诊断 v2：屏骨直绘成功计数（显示侧；归零=直绘整条路径没跑）
                            RVP_CockpitDiag.noteBoneDraw(screenKey, vertexCount);
                            // 排查期 GL 取证（2026-10-16）：错误码/真实绑定/活动纹理单元——
                            // 定位"顶点提交正常但屏骨不可见"的 GL 层原因。
                            int glErr = org.lwjgl.opengl.GL11.glGetError();
                            RenderSystem.activeTexture(
                                    org.lwjgl.opengl.GL13.GL_TEXTURE0);
                            int realBound = org.lwjgl.opengl.GL11.glGetInteger(
                                    org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
                            RenderSystem.enableCull();
                            RenderSystem.enableBlend();
                            poseStack.popPose();
                            if (diagNow) {
                                diagLine.append(" glErr=0x").append(Integer.toHexString(glErr))
                                        .append(" bind=").append(realBound).append("/").append(glId);
                            }
                            if (diagNow) {
                                diagLine.append(" 直绘verts=").append(vertexCount);
                                // UV 取证：模型 UV 实测范围 + 当前语义（排查取景/边缘渗色/镜像）
                                diagLine.append(String.format(java.util.Locale.ROOT,
                                                " uv=%.3f~%.3f/%.3f~%.3f", uMin, uMax, vMin, vMax))
                                        .append(fillUv ? " UV铺满" : " UV模型");
                            }
                        }
                    }
                }
                if (diagNow) {
                    diagLine.append(" 直画chunk=").append(matchedChunks)
                            .append(" sameModel=").append(sameModel).append(" |");
                }
            }
        }
        // 排查期诊断：汇总本帧特殊骨条目状态（与循环内 diagNow 同节拍，每秒一条）。
        if (diagLine.length() > 0) {
            // 诊断 v2：附加渲染时点的 GL 快照（与 ClientTick 时点对照，暴露跨帧残留状态）
            diagLine.append(" 渲染时GL ").append(RVP_CockpitDiag.glSnapshot());
            RVP_CockpitDiag.log(diagLine.toString());
            diagLine.setLength(0);
        }
    }
}
