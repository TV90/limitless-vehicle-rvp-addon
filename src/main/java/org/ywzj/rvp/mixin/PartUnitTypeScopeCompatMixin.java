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
        if (!"crt_ui".equalsIgnoreCase(sightType)) {
            return jsonElement;
        }
        JsonObject patched = jsonObject.deepCopy();
        patched.addProperty("optical_sight_type", "crt");
        if (!patched.has("rvp_disable_crt_effect")) {
            patched.addProperty("rvp_disable_crt_effect", true);
        }
        return patched;
    }
}
