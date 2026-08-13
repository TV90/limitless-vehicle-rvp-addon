package org.ywzj.rvp.client.visual.thermobaric;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.ywzj.rvp.client.visual.RVP_ClientVisualEffect;
import org.ywzj.rvp.config.RVP_ClientConfig;
import org.ywzj.rvp.weapon.visual.api.RVP_VisualEffectEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** 单次温压爆炸的确定性客户端状态。 */
public final class RVP_ThermobaricEffectInstance implements RVP_ClientVisualEffect {
    /** 客户端区块包会同步的尘环地表候选高度图类型。 */
    private static final Heightmap.Types DUST_GROUND_HEIGHTMAP = Heightmap.Types.MOTION_BLOCKING;
    /** 实验关闭时复用的空尘环渐进顺序。 */
    private static final int[] NO_PROGRESSIVE_DUST_SEGMENTS = new int[0];
    /** 效果所属的客户端世界。 */
    private final ClientLevel level;
    /** 服务端权威爆心。 */
    private final Vec3 center;
    /** 基础爆炸半径与视觉尺寸倍率合并后的尺寸。 */
    private final float visualRadius;
    /** 已完成类型化合并的温压预设。 */
    private final RVP_ThermobaricPreset preset;
    /** 当前实例持续时间（tick）。 */
    private final int duration;
    /** 本实例是否启用实验性动态粒子预算；创建后保持不变。 */
    private final boolean dynamicParticleBudgetEnabled;
    /** 动态预算启用时经事件密度钳制的固定火球核心层容量。 */
    private final int dynamicCoreLayerCapacity;
    /** 确定性烟云参数列表。 */
    private final List<Cloud> clouds;
    /** 爆心覆盖层到中心上升层之间使用的稳定粒子连接锚点。 */
    private final RVP_ThermobaricCloudLink.AnchorPair cloudLink;
    /** 构成主火球的确定性团状云参数列表。 */
    private final List<FireballCloud> fireballClouds;
    /** 附着在球形压力波表面的确定性白烟粒子参数列表。 */
    private final List<PressureSmoke> pressureSmokeParticles;
    /** 压力波光学球壳在淡出阶段的确定性额外扩张倍率。 */
    private final float pressureWaveFadeSpread;
    /** 尘环各径向层、各环段预采样得到的地表高度。 */
    private final float[][] dustGroundHeights;
    /** 仅实验模式使用的尘环渐进稳定环段顺序。 */
    private final int[] progressiveDustSegmentOrder;
    /** 后燃烟云的竖直生成基准；贴地爆炸使用地表，空爆使用爆心。 */
    private final float cloudOriginY;
    /** 后燃烟云是否需要让粒子底边贴住地面。 */
    private final boolean cloudGroundAnchored;
    /** 当前效果年龄（tick），创建时已按服务端时间补帧。 */
    private int age;
    /** 当前实例的声速延迟、近远音与尾音控制器。 */
    private final RVP_ThermobaricSoundController soundController;

    RVP_ThermobaricEffectInstance(ClientLevel level, RVP_VisualEffectEvent event,
            RVP_ThermobaricPreset preset, int duration, int initialAge) {
        this.level = level;
        center = event.position();
        visualRadius = multiplyNonNegative(event.baseExplosionRadius(), event.scale());
        this.preset = preset;
        this.duration = duration;
        dynamicParticleBudgetEnabled = event.experimentalDynamicParticleBudget();
        // 调用客户端配置读取质量上限，只在实例创建时下调作者给出的粒子密度。
        float effectiveDensity = resolveClientLimitedDensity(
                event.density(), RVP_ClientConfig.getThermobaricQualityDensity());
        dynamicCoreLayerCapacity = dynamicParticleBudgetEnabled
                ? resolveDensityLimitedCount(3, effectiveDensity) : 3;
        age = initialAge;
        int cloudCount = resolveDensityLimitedCount(preset.maxClouds(), effectiveDensity);
        clouds = createClouds(event.seed(), cloudCount);
        // 调用温压连接锚点选择器，为高横向或高升起倍率预先固定跨帧不变的层间连接端点。
        cloudLink = RVP_ThermobaricCloudLink.selectAnchors(clouds, event.seed());
        int fireballCloudCount = resolveDensityLimitedCount(
                preset.maxFireballClouds(), effectiveDensity);
        fireballClouds = createFireballClouds(event.seed() ^ 0x54A2D91C6E8B37F1L, fireballCloudCount);
        // 按预设硬上限和事件视觉密度生成粒子凝结云，确保配置的最大显示数量不会被突破。
        int pressureSmokeMaximum = preset.showCondensationCloudParticles()
                ? preset.condensationCloudParticleMaxCount() : 0;
        int pressureSmokeCount = resolveDensityLimitedCount(
                pressureSmokeMaximum, effectiveDensity);
        pressureSmokeParticles = createPressureSmoke(
                event.seed() ^ 0x19C7E04AB53D826FL, pressureSmokeCount);
        Random fadeRandom = new Random(event.seed() ^ 0x6D2B79F5A4C381E7L);
        pressureWaveFadeSpread = 0.18F + fadeRandom.nextFloat() * 0.16F;
        int dustSegmentCount = resolveDensityLimitedCount(
                preset.maxDustSegments(), effectiveDensity);
        dustGroundHeights = sampleGround(dustSegmentCount);
        // 仅在实验开关开启时创建渐进顺序，确保默认关闭不增加旧路径的数组分配和选段变化。
        progressiveDustSegmentOrder = dynamicParticleBudgetEnabled
                ? RVP_ThermobaricParticleBudget.createProgressiveSegmentOrder(dustSegmentCount)
                : NO_PROGRESSIVE_DUST_SEGMENTS;
        float centerGroundHeight = sampleGroundSurfaceHeight(Mth.floor(center.x), Mth.floor(center.z));
        float groundAnchorDistance = Math.max(2.0F, visualRadius * 0.25F);
        cloudGroundAnchored = Float.isFinite(centerGroundHeight)
                && center.y >= centerGroundHeight - 1.0D
                && center.y - centerGroundHeight <= groundAnchorDistance;
        cloudOriginY = cloudGroundAnchored ? centerGroundHeight : (float) center.y;
        LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
        double listenerDistance = player == null ? 0.0D : player.position().distanceTo(center);
        soundController = new RVP_ThermobaricSoundController(
                level, event, preset, visualRadius, listenerDistance, initialAge);
        if (soundController.accepted() && event.flash()) {
            // 调用独立温压反馈服务，让闪光遵循光学即时到达并扣除网络补帧年龄。
            RVP_ThermobaricScreenFeedback.triggerFlash(
                    soundController.eventKey(), level, initialAge, listenerDistance, visualRadius);
        }
    }

    @Override
    public void tick() {
        age++;
        // 调用温压声音控制器推进主音、尾音和声波到达震动。
        soundController.tick(age);
    }

    @Override
    public void render(RenderLevelStageEvent event) {
        // 调用独立温压渲染器，把实例的只读状态提交到 AFTER_WEATHER 世界渲染阶段。
        RVP_ThermobaricRenderer.render(this, event);
    }

    @Override
    public boolean isFinished() {
        return age >= duration || level != net.minecraft.client.Minecraft.getInstance().level;
    }

    @Override
    public void close() {
        // 调用温压声音控制器，取消实例关闭后尚未到达的声音与屏幕反馈。
        soundController.close();
    }

    ClientLevel level() {
        return level;
    }

    Vec3 center() {
        return center;
    }

    float visualRadius() {
        return visualRadius;
    }

    RVP_ThermobaricPreset preset() {
        return preset;
    }

    int duration() {
        return duration;
    }

    boolean dynamicParticleBudgetEnabled() {
        return dynamicParticleBudgetEnabled;
    }

    int dynamicCoreLayerCapacity() {
        return dynamicCoreLayerCapacity;
    }

    int age() {
        return age;
    }

    List<Cloud> clouds() {
        return clouds;
    }

    RVP_ThermobaricCloudLink.AnchorPair cloudLink() {
        return cloudLink;
    }

    List<FireballCloud> fireballClouds() {
        return fireballClouds;
    }

    List<PressureSmoke> pressureSmokeParticles() {
        return pressureSmokeParticles;
    }

    float pressureWaveFadeSpread() {
        return pressureWaveFadeSpread;
    }

    float cloudOriginY() {
        return cloudOriginY;
    }

    boolean cloudGroundAnchored() {
        return cloudGroundAnchored;
    }

    int dustSegmentCount() {
        return dustGroundHeights.length == 0 ? 0 : dustGroundHeights[0].length;
    }

    /** 返回实验渐进顺序中的原始尘环环段索引；无效请求返回 {@code -1}。 */
    int progressiveDustSegmentIndex(int renderIndex) {
        if (renderIndex < 0 || renderIndex >= progressiveDustSegmentOrder.length) {
            return -1;
        }
        return progressiveDustSegmentOrder[renderIndex];
    }

    /** 按尘环当前径向进度在预采样地表层之间插值，避免扩散途中悬空或埋地。 */
    float dustGroundHeight(int segmentIndex, float radialProgress) {
        int radialSampleCount = dustGroundHeights.length;
        if (radialSampleCount == 0 || segmentIndex < 0
                || segmentIndex >= dustGroundHeights[0].length) {
            return Float.NaN;
        }
        if (radialSampleCount == 1) {
            return dustGroundHeights[0][segmentIndex];
        }
        float normalizedProgress = Float.isFinite(radialProgress)
                ? Mth.clamp(radialProgress, 0.0F, 1.0F)
                : 0.0F;
        float samplePosition = normalizedProgress
                * (radialSampleCount - 1);
        int lowerIndex = Mth.floor(samplePosition);
        int upperIndex = Math.min(lowerIndex + 1, radialSampleCount - 1);
        float interpolation = samplePosition - lowerIndex;
        return interpolateGroundHeight(
                dustGroundHeights[lowerIndex][segmentIndex],
                dustGroundHeights[upperIndex][segmentIndex],
                interpolation);
    }

    /**
     * 在相邻地表采样之间插值；若一端因区块未加载或无碰撞表面而无效，则使用另一端，
     * 避免单个 {@code NaN} 经线性插值污染整段尘环。
     */
    static float interpolateGroundHeight(float lowerHeight, float upperHeight, float interpolation) {
        boolean lowerFinite = Float.isFinite(lowerHeight);
        boolean upperFinite = Float.isFinite(upperHeight);
        if (!lowerFinite) {
            return upperFinite ? upperHeight : Float.NaN;
        }
        if (!upperFinite) {
            return lowerHeight;
        }
        return Mth.lerp(Mth.clamp(interpolation, 0.0F, 1.0F), lowerHeight, upperHeight);
    }

    private List<Cloud> createClouds(long seed, int count) {
        Random random = new Random(seed);
        List<Cloud> generated = new ArrayList<>(count);
        int clusterCount = Math.max(1, Math.min(12, count));
        float[] clusterAngles = new float[clusterCount];
        float[] clusterPhases = new float[clusterCount];
        float[] clusterCurls = new float[clusterCount];
        float[] clusterOutwardDrifts = new float[clusterCount];
        float[] clusterVerticalDrifts = new float[clusterCount];
        float[] clusterTangentialDrifts = new float[clusterCount];
        float explosionRotation = random.nextFloat() * Mth.TWO_PI;
        for (int clusterIndex = 0; clusterIndex < clusterCount; clusterIndex++) {
            clusterAngles[clusterIndex] = explosionRotation
                    + Mth.TWO_PI * clusterIndex / clusterCount
                    + (random.nextFloat() - 0.5F) * 0.52F;
            clusterPhases[clusterIndex] = random.nextFloat() * 0.18F;
            clusterCurls[clusterIndex] = (random.nextFloat() - 0.5F) * 1.35F;
            clusterOutwardDrifts[clusterIndex] = 0.12F + random.nextFloat() * 0.34F;
            clusterVerticalDrifts[clusterIndex] = 0.06F + random.nextFloat() * 0.24F;
            clusterTangentialDrifts[clusterIndex] = (random.nextFloat() - 0.5F) * 0.24F;
        }
        for (int index = 0; index < count; index++) {
            CloudLayer layer = resolveCloudLayer(index);
            int clusterIndex = index % clusterCount;
            boolean innerCenterCloud = layer == CloudLayer.CENTER && index % 20 == 0;
            float radialFactor = switch (layer) {
                case CENTER -> innerCenterCloud
                        ? 0.015F + random.nextFloat() * 0.065F
                        : 0.05F + random.nextFloat() * 0.60F;
                case UPDRAFT -> 0.04F + random.nextFloat() * 0.30F;
                case ROLLER -> 0.35F + random.nextFloat() * 0.75F;
            };
            float riseFactor = switch (layer) {
                case CENTER -> 0.08F + random.nextFloat() * 0.28F;
                case UPDRAFT -> 0.46F + random.nextFloat() * 0.72F;
                case ROLLER -> 0.78F + random.nextFloat() * 0.52F;
            };
            float sizeFactor = switch (layer) {
                case CENTER -> 0.090F + random.nextFloat() * 0.075F;
                case UPDRAFT -> 0.080F + random.nextFloat() * 0.075F;
                case ROLLER -> 0.100F + random.nextFloat() * 0.090F;
            };
            float spawnHeightFactor = switch (layer) {
                case CENTER -> -0.68F + random.nextFloat() * 0.80F;
                case UPDRAFT -> -0.55F + random.nextFloat() * 0.78F;
                case ROLLER -> -0.38F + random.nextFloat() * 0.82F;
            };
            generated.add(new Cloud(
                    layer,
                    clusterAngles[clusterIndex] + (random.nextFloat() - 0.5F) * 0.30F,
                    radialFactor,
                    riseFactor,
                    sizeFactor,
                    clusterPhases[clusterIndex] + random.nextFloat() * 0.025F,
                    clusterCurls[clusterIndex]
                            + (random.nextFloat() - 0.5F)
                            * (layer == CloudLayer.ROLLER ? 0.38F : 0.18F),
                    (random.nextFloat() - 0.5F) * 0.32F,
                    layer == CloudLayer.ROLLER
                            ? 0.13F + random.nextFloat() * 0.13F : 0.0F,
                    random.nextFloat() * Mth.TWO_PI,
                    0.78F + random.nextFloat() * 0.44F,
                    clusterOutwardDrifts[clusterIndex]
                            * driftMultiplier(layer)
                            + (random.nextFloat() - 0.5F) * 0.04F,
                    clusterVerticalDrifts[clusterIndex]
                            * driftMultiplier(layer)
                            + (random.nextFloat() - 0.5F) * 0.035F,
                    clusterTangentialDrifts[clusterIndex]
                            * driftMultiplier(layer)
                            + (random.nextFloat() - 0.5F) * 0.045F,
                    spawnHeightFactor,
                    0.78F + random.nextFloat() * 0.52F,
                    0.72F + random.nextFloat() * 0.68F,
                    0.010F + random.nextFloat() * 0.055F,
                    0.008F + random.nextFloat() * 0.045F,
                    random.nextFloat() * Mth.TWO_PI,
                    0.75F + random.nextFloat() * 2.10F));
        }
        return Collections.unmodifiableList(generated);
    }

    /**
     * 以稳定交错顺序分配爆心覆盖、中心上升和外缘翻滚云团，保证低密度下三层仍尽早出现。
     */
    static CloudLayer resolveCloudLayer(int index) {
        return switch (Math.floorMod(index, 10)) {
            case 0, 3, 7 -> CloudLayer.CENTER;
            case 1, 5, 8 -> CloudLayer.UPDRAFT;
            default -> CloudLayer.ROLLER;
        };
    }

    /** 爆心覆盖层降低后期漂移，防止整个烟团在淡出阶段同时离开爆心。 */
    private static float driftMultiplier(CloudLayer layer) {
        return switch (layer) {
            case CENTER -> 0.22F;
            case UPDRAFT -> 0.62F;
            case ROLLER -> 1.0F;
        };
    }

    private List<FireballCloud> createFireballClouds(long seed, int count) {
        Random random = new Random(seed);
        List<FireballCloud> generated = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            double offsetX = random.nextGaussian();
            double offsetY = random.nextGaussian() * 0.82D;
            double offsetZ = random.nextGaussian();
            double length = Math.max(1.0E-5D,
                    Math.sqrt(offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ));
            float radial = 0.18F + (float) Math.cbrt(random.nextFloat()) * 0.82F;
            float normalizedX = (float) (offsetX / length);
            float normalizedY = (float) (offsetY / length);
            float normalizedZ = (float) (offsetZ / length);
            generated.add(new FireballCloud(
                    normalizedX * radial,
                    normalizedY * radial,
                    normalizedZ * radial,
                    0.13F + random.nextFloat() * 0.13F,
                    random.nextFloat() * 0.28F,
                    random.nextFloat() * Mth.TWO_PI));
        }
        return Collections.unmodifiableList(generated);
    }

    private List<PressureSmoke> createPressureSmoke(long seed, int count) {
        Random random = new Random(seed);
        List<PressureSmoke> generated = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            float directionY = random.nextFloat() * 2.0F - 1.0F;
            float horizontal = Mth.sqrt(Math.max(0.0F, 1.0F - directionY * directionY));
            float angle = random.nextFloat() * Mth.TWO_PI;
            generated.add(new PressureSmoke(
                    horizontal * Mth.cos(angle),
                    directionY,
                    horizontal * Mth.sin(angle),
                    random.nextFloat() * 2.0F - 1.0F,
                    0.035F + random.nextFloat() * 0.055F,
                    random.nextFloat() * Mth.TWO_PI,
                    random.nextFloat()));
        }
        // 按独立随机样本排序后，距离 LOD 可以取稳定前缀，质量切换不会重新随机整面云墙。
        generated.sort((left, right) -> Float.compare(left.lodSample(), right.lodSample()));
        return Collections.unmodifiableList(generated);
    }

    private float[][] sampleGround(int segmentCount) {
        int radialSampleCount = preset.dustGroundRadialSamples();
        float[][] heights = new float[radialSampleCount][segmentCount];
        float maximumRadius = resolveDustGroundSampleRadius(
                visualRadius, preset.dustRadiusFactor());
        for (int radialIndex = 0; radialIndex < radialSampleCount; radialIndex++) {
            float radialProgress = radialSampleCount <= 1
                    ? 0.0F : radialIndex / (float) (radialSampleCount - 1);
            float radius = maximumRadius * radialProgress;
            for (int segmentIndex = 0; segmentIndex < segmentCount; segmentIndex++) {
                double angle = Mth.TWO_PI * segmentIndex / segmentCount;
                int x = Mth.floor(center.x + Mth.cos((float) angle) * radius);
                int z = Mth.floor(center.z + Mth.sin((float) angle) * radius);
                heights[radialIndex][segmentIndex] = sampleGroundSurfaceHeight(x, z);
            }
        }
        return heights;
    }

    /**
     * 返回尘环地表预采样的最大半径；派生淡出阶段会以原速度再移动一个配置半径。
     */
    static float resolveDustGroundSampleRadius(float visualRadius, float dustRadiusFactor) {
        return multiplyNonNegative(multiplyNonNegative(visualRadius, dustRadiusFactor), 2.0F);
    }

    /**
     * 让通用视觉密度只下调对应预设的最大数量；密度大于 {@code 1} 时仍不突破该最大值。
     */
    static int resolveDensityLimitedCount(int maximumCount, float density) {
        if (maximumCount <= 0 || !Float.isFinite(density) || density <= 0.0F) {
            return 0;
        }
        double scaledCount = maximumCount * (double) density;
        if (scaledCount >= maximumCount) {
            return maximumCount;
        }
        return Math.max(0, (int) Math.round(scaledCount));
    }

    /** 客户端质量只能下调服务端作者密度，不能把事件密度增强到更高值。 */
    static float resolveClientLimitedDensity(float serverDensity, float clientQualityDensity) {
        float normalizedServer = Float.isFinite(serverDensity)
                ? Math.max(0.0F, serverDensity) : 0.0F;
        float normalizedClient = Float.isFinite(clientQualityDensity)
                ? Mth.clamp(clientQualityDensity, 0.0F, 1.0F) : 1.0F;
        double product = (double) normalizedServer * normalizedClient;
        return product >= Float.MAX_VALUE ? Float.MAX_VALUE : (float) product;
    }

    /** 对两个非负有限 float 做饱和乘法，避免自由倍率计算产生无穷值。 */
    private static float multiplyNonNegative(float left, float right) {
        double product = (double) left * right;
        return product >= Float.MAX_VALUE ? Float.MAX_VALUE : (float) product;
    }

    /**
     * 采样给定水平坐标的实际碰撞表面高度。
     * 高度图只用于定位候选方块，最终使用碰撞形状顶面，避免把尘环中心塞进命中方块。
     */
    private float sampleGroundSurfaceHeight(int x, int z) {
        BlockPos chunkProbe = BlockPos.containing(x, center.y, z);
        if (!level.hasChunkAt(chunkProbe)) {
            return Float.NaN;
        }
        int heightmapY = level.getHeight(DUST_GROUND_HEIGHTMAP, x, z);
        int minimumY = Math.max(level.getMinBuildHeight(), heightmapY - 8);
        for (int y = heightmapY - 1; y >= minimumY; y--) {
            BlockPos blockPos = new BlockPos(x, y, z);
            BlockState blockState = level.getBlockState(blockPos);
            VoxelShape collisionShape = blockState.getCollisionShape(level, blockPos);
            if (!collisionShape.isEmpty()) {
                return y + (float) collisionShape.max(Direction.Axis.Y) + 0.04F;
            }
        }
        return Float.NaN;
    }

    /**
     * 确定性烟云参数。
     *
     * @param layer 云团所属的爆心覆盖、中心上升或外缘翻滚层
     * @param angle 水平展开角度（弧度）
     * @param radialFactor 横向位置随机倍率
     * @param riseFactor 上升位置随机倍率
     * @param sizeFactor 云团尺寸相对视觉半径的倍率
     * @param phase 云团全生命周期连续运动的起始错相比例
     * @param curlRadians 云团上卷过程中的水平偏转弧度
     * @param rollPhase 外缘翻滚环流的起始相位扰动（弧度）
     * @param rollerRadiusFactor 外缘翻滚截面半径相对视觉半径的倍率
     * @param rotation 粒子方块在相机平面内的旋转弧度
     * @param brightnessFactor 云团亮度随机倍率
     * @param outwardDrift 所属云团共享的水平向外漂移倍率，叠加少量粒子扰动
     * @param verticalDrift 所属云团共享的竖直漂移倍率，叠加少量粒子扰动
     * @param tangentialDrift 所属云团共享的水平切向漂移倍率，叠加少量粒子扰动
     * @param spawnHeightFactor 云团相对火球中心的初始竖直位置倍率，负值位于火球下部
     * @param formationSpeedFactor 云团聚集成形速度的随机倍率
     * @param rollSpeedFactor 云团环面翻滚速度的随机倍率
     * @param radialNoiseFactor 云团横向卷吸噪声相对视觉半径的振幅
     * @param verticalNoiseFactor 云团竖直卷吸噪声相对视觉半径的振幅
     * @param noisePhase 云团连续噪声的初始相位（弧度）
     * @param noiseFrequency 云团连续噪声的频率倍率
     */
    record Cloud(CloudLayer layer, float angle, float radialFactor, float riseFactor, float sizeFactor,
                 float phase, float curlRadians, float rollPhase, float rollerRadiusFactor,
                 float rotation, float brightnessFactor, float outwardDrift, float verticalDrift,
                 float tangentialDrift, float spawnHeightFactor, float formationSpeedFactor,
                 float rollSpeedFactor, float radialNoiseFactor, float verticalNoiseFactor,
                 float noisePhase, float noiseFrequency) {
    }

    /** 后燃烟云的空间运动层。 */
    enum CloudLayer {
        /** 持续留在爆心低处并覆盖火球消退后的空洞。 */
        CENTER,
        /** 从爆心中央上升并向外缘输送烟云。 */
        UPDRAFT,
        /** 沿低矮菌盖截面向上、向外翻滚的烟云。 */
        ROLLER
    }

    /**
     * 主火球团状云的确定性参数。
     *
     * @param offsetX 主火球局部 X 方向偏移倍率
     * @param offsetY 主火球局部 Y 方向偏移倍率
     * @param offsetZ 主火球局部 Z 方向偏移倍率
     * @param sizeFactor 团状云尺寸相对视觉半径的倍率
     * @param phase 团状云延迟出现比例
     * @param rotation 粒子方块在相机平面内的旋转弧度
     */
    record FireballCloud(float offsetX, float offsetY, float offsetZ,
                          float sizeFactor, float phase, float rotation) {
    }

    /**
     * 球形压力波白烟粒子的确定性参数。
     *
     * @param directionX 单位球面 X 分量
     * @param directionY 单位球面 Y 分量
     * @param directionZ 单位球面 Z 分量
     * @param radialOffset 压力波墙半厚度内的归一化偏移，范围 {@code -1..1}
     * @param sizeFactor 粒子尺寸相对视觉半径的倍率
     * @param rotation 粒子方块在相机平面内的旋转弧度
     * @param lodSample 与空间分布独立的稳定 LOD 排序样本，范围 {@code 0..1}
     */
    record PressureSmoke(float directionX, float directionY, float directionZ,
                         float radialOffset, float sizeFactor, float rotation, float lodSample) {
    }
}
