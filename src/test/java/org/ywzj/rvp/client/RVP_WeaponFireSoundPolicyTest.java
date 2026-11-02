package org.ywzj.rvp.client;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 原类型/口径自动接管必须保持关闭，未来不能因新资源存在而自行恢复。 */
class RVP_WeaponFireSoundPolicyTest {
    @Test void noCaliberOrVehicleCombinationCanEnableAutomaticRocketDefaults() {
        for (boolean ground : new boolean[]{false,true}) for (boolean rocket : new boolean[]{false,true})
            for (Float caliber : new Float[]{null,70F,220F,1000F})
                assertFalse(RVP_WeaponFireSoundPolicy.useGroundRocketSound(ground,rocket,caliber,true,List.of()));
    }
    @Test void availableAudioAndNativeSoundsStillDoNotOptIntoAutomaticDefaults() {
        assertFalse(RVP_WeaponFireSoundPolicy.allowsDefaultSound(true,null));
        assertFalse(RVP_WeaponFireSoundPolicy.allowsDefaultSound(true,List.of(new ResourceLocation("ywzj_vehicle","missile_launch"))));
        assertFalse(RVP_WeaponFireSoundPolicy.allowsDefaultSound(false,List.of()));
    }
}
