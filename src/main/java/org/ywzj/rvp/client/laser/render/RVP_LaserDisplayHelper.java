package org.ywzj.rvp.client.laser.render;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockModel;
import net.minecraft.resources.ResourceLocation;
import org.ywzj.vehicle.YwzjVehicle;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;
import org.ywzj.vehicle.client.resource.vehicle.BaseDisplay;
import org.ywzj.vehicle.resource.BedrockModelLoader;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * Resolves bedrock model/texture for laser beams from weapon display JSON.
 */
public final class RVP_LaserDisplayHelper {

    private static final ResourceLocation DEFAULT_MODEL = YwzjVehicle.modLocation("entity/basic_bullet");
    private static final ResourceLocation DEFAULT_TEXTURE = YwzjVehicle.modLocation("textures/entity/basic_bullet.png");

    private RVP_LaserDisplayHelper() {}

    public record ResolvedDisplay(BedrockModel model, ResourceLocation texture) {}

    @Nullable
    public static ResolvedDisplay resolve(@Nullable ResourceLocation weaponId) {
        BedrockModel model = null;
        ResourceLocation texture = null;
        if (weaponId != null) {
            Optional<BaseDisplay> displayOptional = ClientAssetsManager.INSTANCE.getWeaponDisplay(weaponId);
            if (displayOptional.isPresent()) {
                BaseDisplay display = displayOptional.get();
                if (display.getModel() != null) {
                    model = display.getModel();
                }
                if (display.getTexture() != null) {
                    texture = display.getTexture();
                }
            }
        }
        if (model == null) {
            model = BedrockModelLoader.getModel(DEFAULT_MODEL);
        }
        if (texture == null) {
            texture = DEFAULT_TEXTURE;
        }
        if (model == null) {
            return null;
        }
        return new ResolvedDisplay(model, texture);
    }
}
