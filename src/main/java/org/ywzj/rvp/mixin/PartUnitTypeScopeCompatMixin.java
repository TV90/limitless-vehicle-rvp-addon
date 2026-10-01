package org.ywzj.rvp.mixin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.ywzj.vehicle.custom.part.PartUnitType;

@Mixin(value = PartUnitType.class, remap = false)
public abstract class PartUnitTypeScopeCompatMixin {

    @Redirect(
            method = "parseAndCreate",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/ywzj/vehicle/custom/part/PartUnitType$DataSerializer;parse(Lcom/google/gson/JsonElement;)Ljava/lang/Object;"
            ),
            remap = false
    )
    private Object ywzj_rvp$rewriteCrtUiOpticalSight(PartUnitType.DataSerializer<?> serializer, JsonElement jsonElement) {
        return serializer.parse(ywzj_rvp$normalizeScopeConfig(jsonElement));
    }

    private JsonElement ywzj_rvp$normalizeScopeConfig(JsonElement jsonElement) {
        PartUnitType<?, ?> self = (PartUnitType<?, ?>) (Object) this;
        ResourceLocation id = self.id();
        if (id == null || !"ywzj_vehicle".equals(id.getNamespace())) {
            return jsonElement;
        }
        String path = id.getPath();
        if (!"weapon".equals(path) && !"auto_weapon".equals(path)) {
            return jsonElement;
        }
        if (!(jsonElement instanceof JsonObject jsonObject)) {
            return jsonElement;
        }
        JsonElement opticalSightType = jsonObject.get("optical_sight_type");
        if (opticalSightType == null || !opticalSightType.isJsonPrimitive()) {
            return jsonElement;
        }
        String sightType = opticalSightType.getAsString();
        // crt_ui：关 CRT 后处理、保留 CRT 观瞄 HUD（既有语法糖）；
        // crt_ui_operator：在 crt_ui 基础上再补 rvp_optical_sight_follow_pitch=true，
        // 让开镜相机挂点走炮口骨（xTurnGroup）跟随炮管俯仰（本体 operator 的位置行为）。
        // 语法糖必须在解析期把类型落成合法值 "crt"——本体枚举没有 crt_ui* 取值，
        // 不改写的话 Gson 解析为 null，开镜界面退化为 1x1 小点且不报错。
        boolean crtUi = "crt_ui".equalsIgnoreCase(sightType);
        boolean crtUiOperator = "crt_ui_operator".equalsIgnoreCase(sightType);
        if (!crtUi && !crtUiOperator) {
            return jsonElement;
        }
        JsonObject patched = jsonObject.deepCopy();
        patched.addProperty("optical_sight_type", "crt");
        if (!patched.has("rvp_disable_crt_effect")) {
            patched.addProperty("rvp_disable_crt_effect", true);
        }
        if (crtUiOperator && !patched.has("rvp_optical_sight_follow_pitch")) {
            patched.addProperty("rvp_optical_sight_follow_pitch", true);
        }
        return patched;
    }
}
