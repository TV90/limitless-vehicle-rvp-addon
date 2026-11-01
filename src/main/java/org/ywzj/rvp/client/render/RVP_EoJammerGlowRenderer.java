package org.ywzj.rvp.client.render;

import com.github.mcmodderanchor.simplebedrockmodel.v1.client.renderer.BedrockModelRenderTypes;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BakedModelInstance;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BoneState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.ywzj.rvp.client.light.RVP_LightSourceType;
import org.ywzj.rvp.client.light.RVP_ScreenLightManager;
import org.ywzj.rvp.client.resource.RVP_EoJammerGlowManager;
import org.ywzj.rvp.client.shader.RVP_NvgHandler;
import org.ywzj.rvp.client.state.RVP_ClientBoneModuleState;
import org.ywzj.rvp.config.RVP_LightingConfig;
import org.ywzj.rvp.light.RVP_LightParameters;
import org.ywzj.rvp.vehicle.BoneModuleType;
import org.ywzj.vehicle.client.render.entity.vehicle.VehicleRender;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;
import org.ywzj.vehicle.client.resource.vehicle.VehicleBedrockModel;
import org.ywzj.vehicle.client.resource.vehicle.VehicleDisplay;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 光电干扰机玻片夜间红光：在载具本体渲染完成后，对 display JSON {@code eo_jammer_glow}
 * 指定的玻片骨再画一遍，用顶点色乘红色并强制满亮度，同时注册一盏红色屏幕光。
 *
 * <p>只追加不改写：本体渲染仍是常规 cutout 通道，白天、残骸、干扰模块失效时不画第二遍，
 * 玻片保持原样。第二遍复用同一 {@link BakedModelInstance}，动画姿态由本体渲染器本帧写入，
 * 因此不能再次 tick 动画。</p>
 *
 * <p>发光分两遍：红色 cutout 定义形状，再按叠加混合画一遍形成自发光亮感。</p>
 *
 * <p>屏幕光锚点直接取玻片骨的枢轴（模型已把 {@code jammer*} 的枢轴改到真正的镜片位置）：
 * 枢轴经 {@code getGlobalTransform} 带着炮塔动画回到模型空间，再按载具朝向落到世界坐标。
 * display JSON 列了几块玻片就登记几盏灯，左右各一块时画面上一左一右两个光斑。</p>
 *
 * <p>屏幕光是<b>定向光</b>：轴向取玻片骨自己的法线（模型 +Z，即镜片朝外的一侧），
 * 跟着骨骼一起旋转，所以炮塔转到车体后方时红光就照向后方，不会固定照着车体前部；
 * Shader 内再按水平 140° × 竖直 180° 收窄，避免像半球那样往玻片两侧洒光。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class RVP_EoJammerGlowRenderer {

    /** 环境亮度低于该值即点亮：1 为正午露天，0 为深夜或洞顶下。 */
    private static final float DARK_AMBIENT_THRESHOLD = 0.6F;
    /** 亮度采样点在车体上方的高度：贴地会落进实心方块（不透光、天光恒 0），抬到车身与玻片附近。 */
    private static final double AMBIENT_SAMPLE_HEIGHT = 1.5;
    /** 红光顶点色：乘在原贴图上，保留纹理明暗细节。 */
    private static final float GLOW_RED = 1.0F;
    private static final float GLOW_GREEN = 0.20F;
    private static final float GLOW_BLUE = 0.14F;
    /** 叠加自发光：在红色底上再加几层，让玻片像通电的镜片而不是一块深红塑料。 */
    private static final float GLOW_ADD_RED = 0.90F;
    private static final float GLOW_ADD_GREEN = 0.22F;
    private static final float GLOW_ADD_BLUE = 0.15F;
    /** 叠加层数：玻片贴图本身偏暗，单层叠加亮度不够。 */
    private static final int GLOW_ADDITIVE_LAYERS = 3;
    /** 屏幕光只在相机附近注册，避免远处载具挤占八盏灯预算。 */
    private static final double MAX_LIGHT_DISTANCE_SQR = 128.0 * 128.0;
    /** 灯从枢轴沿镜片法线外移的距离，单位格；避免灯陷在镜片/外壳内部。 */
    private static final double ANCHOR_FORWARD_OFFSET = 0.35;

    private RVP_EoJammerGlowRenderer() {
    }

    /**
     * 在 {@code super.render} 之后调用：姿势栈已回到实体原点（本体渲染器已 popPose）。
     * 本方法自行 push/pop，并在其中套用与本体一致的载具朝向。
     */
    public static void render(AbstractVehicle vehicle, float partialTick, PoseStack poseStack,
                              MultiBufferSource bufferSource, int packedLight) {
        if (!RVP_LightingConfig.eoJammerGlowEnabled()) {
            RVP_ScreenLightManager.INSTANCE.removePersistent(vehicle.getUUID(), RVP_LightSourceType.EO_JAMMER);
            return;
        }
        // 残骸与已移除实体不发光：干扰机随车体一起熄灭。
        if (vehicle.isRemoved() || !vehicle.isAlive() || vehicle.isDestroyed()) {
            RVP_ScreenLightManager.INSTANCE.removePersistent(vehicle.getUUID(), RVP_LightSourceType.EO_JAMMER);
            return;
        }
        VehicleDisplay<?, ?> display = ClientAssetsManager.INSTANCE
                .getVehicleDisplay(vehicle.getDisplayId()).orElse(null);
        if (display == null) {
            RVP_ScreenLightManager.INSTANCE.removePersistent(vehicle.getUUID(), RVP_LightSourceType.EO_JAMMER);
            return;
        }
        // 座舱视角渲染的是座舱 display，玻片在外部机体上，跳过避免画进驾驶舱。
        boolean isCabinView = LocalVehiclePlayer.instance.vehicle == vehicle
                && LocalVehiclePlayer.instance.viewType == LocalVehiclePlayer.ViewType.OPERATOR
                && display.getCabinDisplay() != null;
        if (isCabinView) {
            RVP_ScreenLightManager.INSTANCE.removePersistent(vehicle.getUUID(), RVP_LightSourceType.EO_JAMMER);
            return;
        }
        List<RVP_EoJammerGlowManager.Rule> rules = RVP_EoJammerGlowManager.INSTANCE.rulesFor(vehicle.getDisplayId());
        if (rules.isEmpty()) {
            RVP_ScreenLightManager.INSTANCE.removePersistent(vehicle.getUUID(), RVP_LightSourceType.EO_JAMMER);
            return;
        }
        VehicleBedrockModel model = display.getModel();
        ResourceLocation texture = display.getTexture();
        if (model == null || texture == null || !model.hasBakedModel()) {
            RVP_ScreenLightManager.INSTANCE.removePersistent(vehicle.getUUID(), RVP_LightSourceType.EO_JAMMER);
            return;
        }
        // 与本体渲染器同源：优先载具当前实例，缺失时退回模型默认实例（姿态同样已由本体写入）。
        BakedModelInstance modelInstance = vehicle.getVehicleModelInstance();
        if (modelInstance == null) {
            modelInstance = model.getDefaultModelInstance();
        }
        if (modelInstance == null) {
            RVP_ScreenLightManager.INSTANCE.removePersistent(vehicle.getUUID(), RVP_LightSourceType.EO_JAMMER);
            return;
        }

        // 筛出本帧要发红的玻片骨：干扰模块被摧毁的玻片不再发红；父级被隐藏（部件脱落、模块骨失效
        // 动画）时玻片本身也不可见，不能凭空发光。这里记 rules 下标，一块玻片一盏灯，灯键由下标派生。
        List<Integer> glowPlates = new ArrayList<>(rules.size());
        for (int plate = 0; plate < rules.size(); plate++) {
            RVP_EoJammerGlowManager.Rule rule = rules.get(plate);
            int boneIndex = modelInstance.getIndex(rule.glassBone());
            if (boneIndex < 0) {
                continue;
            }
            if (rule.moduleBone() != null && !RVP_ClientBoneModuleState.isModuleActive(
                    vehicle.getId(), rule.moduleBone(), BoneModuleType.JAMMER)) {
                continue;
            }
            BoneState bone = modelInstance.getBone(boneIndex);
            if (bone == null || !isTreeVisible(modelInstance, bone)) {
                continue;
            }
            glowPlates.add(plate);
        }
        UUID vehicleKey = vehicle.getUUID();
        // 本帧熄灭的玻片单独撤自己那盏灯，不能整车一起撤，否则左右两盏互相牵连。
        for (int plate = 0; plate < rules.size(); plate++) {
            if (!glowPlates.contains(plate)) {
                RVP_ScreenLightManager.INSTANCE.removePersistent(plateLightKey(vehicleKey, plate),
                        RVP_LightSourceType.EO_JAMMER);
            }
        }
        if (glowPlates.isEmpty()) {
            return;
        }
        // 白天只是人眼看不出这盏灯：窗帘干扰灯的能量在红外波段，像增强管会把它整片转成可见光。
        // 所以开着夜视时不看天光，玻片发红、光晕、周围照亮全都照常出；夜视开关是本机视角的判据
        // （isActive 只跟随本机夜视状态，与环境亮度无关），没戴夜视的观察者仍按下面的环境亮度条件走。
        boolean nightVision = RVP_NvgHandler.isActive();
        if (!nightVision) {
            Vec3 sample = entityPosition(vehicle, partialTick).add(0.0, AMBIENT_SAMPLE_HEIGHT, 0.0);
            // 调用环境亮度判定：天光遮蔽 × 昼夜/天气黑暗度，洞顶、车库、黄昏、雷雨都会提前/延后点亮。
            if (!isDarkEnough(vehicle.level(), sample, partialTick)) {
                RVP_ScreenLightManager.INSTANCE.removePersistent(vehicleKey, RVP_LightSourceType.EO_JAMMER);
                return;
            }
        }

        // 屏幕光总开关关闭时撤掉本车全部灯，顶点红光照常画（那是玻片本身的样子）。
        // 自己在车上时这盏灯只留照亮、不带光晕：观瞄与第三人称镜头就贴在玻片后面，
        // 光晕会糊住整个画面；玻片仍然发红、车体照样被红光照到，别人看你这辆车一切照旧。
        boolean ownVehicle = LocalVehiclePlayer.instance.vehicle == vehicle;
        boolean screenLight = RVP_LightingConfig.screenEnabled() && RVP_LightingConfig.maxLights() > 0;
        if (!screenLight) {
            RVP_ScreenLightManager.INSTANCE.removePersistent(vehicleKey, RVP_LightSourceType.EO_JAMMER);
        }

        poseStack.pushPose();
        try {
            // 调用本体同款载具朝向：绕 centerOffset 旋转，第二遍与第一遍完全重合。
            VehicleRender.applyVehicleRotation(vehicle, partialTick, poseStack);
            for (int plate : glowPlates) {
                int boneIndex = modelInstance.getIndex(rules.get(plate).glassBone());
                BoneState bone = modelInstance.getBone(boneIndex);
                // 只在本遍临时切换满亮度，finally 恢复，避免影响其它渲染通道。
                boolean illuminated = bone.illuminated;
                bone.illuminated = true;
                try {
                    // 第一遍：不透明红片，负责形状与深度，纹理细节保留。
                    modelInstance.renderSingleBone(poseStack, boneIndex, bufferSource,
                            RenderType.entityCutout(texture), BedrockModelRenderTypes.polyMeshCutout(texture),
                            packedLight, OverlayTexture.NO_OVERLAY,
                            GLOW_RED, GLOW_GREEN, GLOW_BLUE, 1.0F, false);
                    // 后续各遍：叠加混合直接加到画面上。贴图本身偏暗，单层叠加不够亮，
                    // 重复叠加把红光堆到接近饱和，玻片才会像通电的镜片。
                    for (int layer = 0; layer < GLOW_ADDITIVE_LAYERS; layer++) {
                        modelInstance.renderSingleBone(poseStack, boneIndex, bufferSource,
                                RVP_RenderTypes.texturedAdditiveNoDepthWrite(texture),
                                RVP_RenderTypes.polyMeshAdditiveNoDepthWrite(texture),
                                packedLight, OverlayTexture.NO_OVERLAY,
                                GLOW_ADD_RED, GLOW_ADD_GREEN, GLOW_ADD_BLUE, 1.0F, false);
                    }
                } finally {
                    bone.illuminated = illuminated;
                }
                if (screenLight) {
                    upsertPlateLight(vehicle, vehicleKey, plate, modelInstance, boneIndex, partialTick,
                            Minecraft.getInstance().player, ownVehicle);
                }
            }
        } finally {
            poseStack.popPose();
        }
    }

    /** 一块玻片一盏灯的稳定键：同一辆车 + 同一个玻片序号；归属仍是车，便于整车一次清灯。 */
    private static UUID plateLightKey(UUID vehicleKey, int plate) {
        return UUID.nameUUIDFromBytes(("ywzj_rvp:eo_jammer:" + vehicleKey + ':' + plate)
                .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 上报单块玻片的屏幕光：灯位在玻片枢轴沿镜片法线外移，半球轴就是这条法线，
     * 所以炮塔转到车体后方时灯光跟着朝后，不会再照着车体前部。
     *
     * @param ownVehicle 是否玩家当前驾驶的这辆车；本车只保留照亮，光晕由调用方摘掉
     */
    private static void upsertPlateLight(AbstractVehicle vehicle, UUID vehicleKey, int plate,
                                         BakedModelInstance instance, int boneIndex, float partialTick,
                                         Player player, boolean ownVehicle) {
        Matrix4f boneTransform = instance.getGlobalTransform(boneIndex);
        var definition = instance.getBone(boneIndex).definition();
        // 骨骼局部原点不含枢轴（自变换里枢轴只有 +pivot/−pivot 抵消项），必须显式取 pivot 再变换。
        Vector3f pivot = new Vector3f(definition.pivotX(), definition.pivotY(), definition.pivotZ());
        boneTransform.transformPosition(pivot);
        // 玻片网格在模型 Z 方向是薄片，法线即模型 +Z；被骨骼旋转后就是镜片真正朝向的那一侧。
        Vector3f axis = new Vector3f(0.0F, 0.0F, 1.0F);
        boneTransform.transformDirection(axis);
        if (axis.lengthSquared() < 1.0E-8F) {
            axis.set(0.0F, 0.0F, 1.0F);
        }
        axis.normalize();
        // 载具朝向把"骨骼动画后的模型空间"整体旋到世界：贴片位置与法线走同一套旋转。
        Quaternionf rotation = vehicle.rotYXZ(partialTick);
        Vec3 centerOffset = vehicle.centerOffset == null ? Vec3.ZERO : vehicle.centerOffset;
        Vec3 lens = toWorld(entityPosition(vehicle, partialTick), rotation, centerOffset, pivot);
        rotation.transform(axis);
        Vec3 anchor = lens.add(axis.x * ANCHOR_FORWARD_OFFSET,
                axis.y * ANCHOR_FORWARD_OFFSET, axis.z * ANCHOR_FORWARD_OFFSET);
        UUID key = plateLightKey(vehicleKey, plate);
        // 屏幕光只补环境受光与光晕，顶点红光是主体；远处载具不占八盏灯的预算。
        if (player == null || player.position().distanceToSqr(anchor) > MAX_LIGHT_DISTANCE_SQR) {
            RVP_ScreenLightManager.INSTANCE.removePersistent(key, RVP_LightSourceType.EO_JAMMER);
            return;
        }
        // 调用持续光统一入口：每帧心跳续期，条件失效时立即删除；定向轴向随炮塔朝向，
        // Shader 内按水平 140° × 竖直 180° 裁切，不会像半球那样往玻片两侧洒光。
        // 夜视仪下换用更大更亮的一档：干扰灯的能量主要在红外波段，肉眼只见微弱红晕，
        // 像增强管会把它整片放大，所以同一盏灯在滤镜里半径和亮度都要抬一档。
        RVP_LightParameters parameters = RVP_NvgHandler.isActive()
                ? RVP_LightParameters.eoJammerNightVision()
                : RVP_LightParameters.eoJammer();
        // 本车摘掉光晕、保留照亮：bloom 只门控光晕那一路，intensity 与 surfaceWeight 不受影响。
        if (ownVehicle) {
            parameters = parameters.withoutHalo();
        }
        RVP_ScreenLightManager.INSTANCE.upsertPersistent(key, vehicleKey, anchor,
                RVP_LightSourceType.EO_JAMMER, parameters,
                new Vec3(axis.x, axis.y, axis.z));
    }

    /**
     * 环境亮度判定：玻片处的天光遮蔽 × 昼夜/天气黑暗度，低于阈值即视为"暗"。
     *
     * <p>不能用 {@code Level.getSkyDarken()}（int 字段）或 {@code getMaxLocalRawBrightness}：
     * 该字段只在进世界时由 {@code updateSkyBrightness} 算一次，后者拿它减天光，夜里仍按 15 算，
     * 会把整夜判成白天。这里改用原版光照贴图同款的 {@link ClientLevel#getSkyDarken(float)}，
     * 它每次调用都按时间与天气实时重算。</p>
     *
     * <p>只取天光层、不取方块光：玩家在车边点火把不该把拟真设备判亮。屏幕光是渲染阶段的附加光，
     * 不写入光照引擎，因此自身发光不会反过来把自己判成"不暗"。</p>
     */
    private static boolean isDarkEnough(Level level, Vec3 samplePos, float partialTick) {
        if (level == null) {
            return false;
        }
        // 无天光维度（下界、末地）没有昼夜与天空遮蔽，按常暗处理。
        if (!level.dimensionType().hasSkyLight()) {
            return true;
        }
        // 天光遮蔽：露天 15，洞顶、车库顶、密林树冠下更低甚至为 0。
        float skyExposure = level.getBrightness(LightLayer.SKY, BlockPos.containing(samplePos)) / 15.0F;
        // skyDarken：0.2 为正午、1.0 为深夜（天气已计入），换算成"日光比例"。
        float skyDarken = ((ClientLevel) level).getSkyDarken(partialTick);
        float daylight = Mth.clamp(1.0F - (skyDarken - 0.2F) / 0.8F, 0.0F, 1.0F);
        return skyExposure * daylight < DARK_AMBIENT_THRESHOLD;
    }

    /** 实体插值位置，与本体渲染器同帧一致。 */
    private static Vec3 entityPosition(AbstractVehicle vehicle, float partialTick) {
        return new Vec3(
                Mth.lerp(partialTick, vehicle.xo, vehicle.getX()),
                Mth.lerp(partialTick, vehicle.yo, vehicle.getY()),
                Mth.lerp(partialTick, vehicle.zo, vehicle.getZ()));
    }

    /** 骨骼自身及全部祖先都可见时才允许叠加；与本体 renderBone 的父链语义一致。 */
    private static boolean isTreeVisible(BakedModelInstance instance, BoneState bone) {
        for (BoneState current = bone; current != null; ) {
            if (!current.visible) {
                return false;
            }
            int parentIndex = current.parentIndex();
            current = parentIndex < 0 ? null : instance.getBone(parentIndex);
        }
        return true;
    }

    /**
     * 模型空间点还原到世界坐标：实体插值位置 + 绕 centerOffset 的载具旋转，
     * 与 {@link VehicleRender#applyVehicleRotation} 的 rotateAround 完全一致。
     */
    private static Vec3 toWorld(Vec3 entityPos, Quaternionf rotation, Vec3 centerOffset, Vector3f pivot) {
        Vector3f relative = new Vector3f(
                pivot.x - (float) centerOffset.x,
                pivot.y - (float) centerOffset.y,
                pivot.z - (float) centerOffset.z);
        rotation.transform(relative);
        return entityPos.add(relative.x + centerOffset.x,
                relative.y + centerOffset.y,
                relative.z + centerOffset.z);
    }
}
