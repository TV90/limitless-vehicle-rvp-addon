package org.ywzj.rvp.client.laser.render;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockModel;
import net.minecraft.resources.ResourceLocation;
import org.ywzj.vehicle.YwzjVehicle;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;
import org.ywzj.vehicle.client.resource.vehicle.BaseDisplay;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * Resolves bedrock model/texture for laser beams from weapon display JSON.
 */
public final class RVP_LaserDisplayHelper {

    /** 自定义光束模型未另配贴图时，沿用原贴图。 */
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
        if (texture == null) {
            texture = DEFAULT_TEXTURE;
        }
        if (model == null) {
            return null;
        }
        return new ResolvedDisplay(model, texture);
    }
}
