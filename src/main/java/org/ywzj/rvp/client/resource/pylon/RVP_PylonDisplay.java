package org.ywzj.rvp.client.resource.pylon;

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import org.jetbrains.annotations.Nullable;
import org.ywzj.vehicle.client.resource.vehicle.SpecialBoneEffect;
import org.ywzj.vehicle.custom.serialize.GsonUtil;

import java.lang.reflect.Type;
import java.util.List;

/**
 * RVP 自有挂架 display（{@code assets/rvp/display/pylon/**.json}）。
 *
 * <p>与本体 {@code display/decoration} 下的挂架条目不同：本目录只由 RVP 扫描，
 * 不会注册进本体的 vehicle / weapon / decoration 管理器，因此不会出现在饰品选择界面。</p>
 *
 * <p>消费字段只有 {@code model}（必填）与 {@code special_bone_effects}（可选，参与模型烘焙）；
 * display id 由文件路径推导，建议与 {@code model} 一致，使挂架查找可按 id 直接命中。</p>
 */
public record RVP_PylonDisplay(ResourceLocation id, ResourceLocation model, List<SpecialBoneEffect> effects) {

    /** 解析单条挂架 display；缺少合法 {@code model} 时返回 null。 */
    @Nullable
    public static RVP_PylonDisplay parse(ResourceLocation id, JsonObject obj) {
        String rawModel = GsonHelper.getAsString(obj, "model", "").trim();
        if (rawModel.isEmpty()) {
            return null;
        }
        ResourceLocation model = ResourceLocation.tryParse(rawModel);
        if (model == null) {
            return null;
        }
        return new RVP_PylonDisplay(id, model, parseEffects(obj));
    }

    private static List<SpecialBoneEffect> parseEffects(JsonObject obj) {
        if (!obj.has("special_bone_effects") || !obj.get("special_bone_effects").isJsonArray()) {
            return List.of();
        }
        Type type = new TypeToken<List<SpecialBoneEffect>>() {}.getType();
        List<SpecialBoneEffect> effects = GsonUtil.GSON.fromJson(obj.get("special_bone_effects"), type);
        return effects == null ? List.of() : List.copyOf(effects);
    }
}
