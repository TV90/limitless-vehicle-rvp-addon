package org.ywzj.rvp.client.resource.vehicle;

import com.google.gson.annotations.SerializedName;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;

/**
 * 整模型 LOD 规则：距离/离地达到阈值时，用低面数模型整体替换载具模型渲染。
 * <p>
 * 每级规则对应一个 {@code models/bedrock/<id>.json} 低模与可选贴图：
 * <ul>
 *   <li>{@code model}：LOD 模型资源 ID（必填）；</li>
 *   <li>{@code model_air}：离地（飞行）状态使用的 LOD 模型资源 ID（可选，缺省沿用
 *       {@code model}——地面/空中共用同一模型）；</li>
 *   <li>{@code texture}：LOD 贴图资源 ID（缺省沿用原贴图）；</li>
 *   <li>{@code distance}：地面状态下的进入阈值（方块）；</li>
 *   <li>{@code air_distance}：离地（飞行）状态下的进入阈值（方块），通常小于
 *       {@code distance}——飞起来能看到更少细节，LOD 应更早生效；</li>
 *   <li>{@code air_height}：离地判定高度（方块），载具相对地面高于该值视为离地，缺省 10。</li>
 * </ul>
 * 切换带滞后防抖：进入某级使用配置阈值，退回使用 {@code 0.85 × 阈值}，避免边界抖动。
 */
public class RVP_LodModel {

    public final ResourceLocation model;
    public final ResourceLocation modelAir;
    public final ResourceLocation texture;
    public final double distance;
    public final double airDistance;
    public final double airHeight;

    public RVP_LodModel(ResourceLocation model, ResourceLocation modelAir, ResourceLocation texture,
                        double distance, double airDistance, double airHeight) {
        this.model = model;
        this.modelAir = modelAir;
        this.texture = texture;
        this.distance = distance;
        this.airDistance = airDistance;
        this.airHeight = airHeight;
    }

    /** 该级在指定离地状态下的进入阈值。 */
    public double threshold(boolean air) {
        return air ? airDistance : distance;
    }

    /** 该级在指定离地状态下实际使用的 LOD 模型 ID。 */
    public ResourceLocation modelId(boolean air) {
        return air && modelAir != null ? modelAir : model;
    }

    /** 解析 display pojo 中的 lod_models 列表，过滤非法项（model 缺失/不可解析）。 */
    public static List<RVP_LodModel> parse(List<Pojo> pojos) {
        if (pojos == null) {
            return List.of();
        }
        return pojos.stream()
                .filter(p -> p != null && p.model != null && !p.model.isBlank())
                .map(Pojo::toRule)
                .filter(Objects::nonNull)
                .toList();
    }

    public static class Pojo {
        @SerializedName("model")
        public String model = "";

        @SerializedName("model_air")
        public String modelAir = "";

        @SerializedName("texture")
        public String texture = "";

        @SerializedName("distance")
        public double distance = 32.0;

        @SerializedName("air_distance")
        public double airDistance = 16.0;

        @SerializedName("air_height")
        public double airHeight = 10.0;

        public RVP_LodModel toRule() {
            ResourceLocation modelId = ResourceLocation.tryParse(model);
            if (modelId == null) {
                return null;
            }
            ResourceLocation airModelId = modelAir == null || modelAir.isBlank()
                    ? null : ResourceLocation.tryParse(modelAir);
            ResourceLocation textureId = texture == null || texture.isBlank()
                    ? null : ResourceLocation.tryParse(texture);
            return new RVP_LodModel(modelId, airModelId, textureId,
                    Math.max(0, distance), Math.max(0, airDistance), Math.max(0, airHeight));
        }
    }
}
