package org.ywzj.rvp.firesupport;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RVP_FireSupportProfileParserTest {
    @Test
    void parsesCurrentSchemaIntoImmutableMapsAndActualResolvedWeaponData() {
        RVP_FireSupportProfile profile = RVP_FireSupportTestProfiles.parse();
        assertEquals(3, profile.fireModes().size());
        assertEquals(3, profile.patterns().size());
        assertInstanceOf(RVP_FireSupportDeliveryTypes.VerticalProjectileData.class,
                profile.munitions().get("he").deliveryData());
        assertThrows(UnsupportedOperationException.class, () -> profile.fireModes().clear());
    }

    @Test
    void rejectsUnknownFieldDuplicateIdAndNonFiniteNumberAsOneCandidateBatch() {
        JsonObject root = RVP_FireSupportTestProfiles.validJson().getAsJsonObject();
        root.addProperty("legacy_profile", true);
        root.getAsJsonArray("munitions").add(root.getAsJsonArray("munitions").get(0).deepCopy());
        root.getAsJsonObject("limits").addProperty("max_target_distance_m", Double.NaN);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> RVP_FireSupportProfileParser.parseAll(Map.of(RVP_FireSupportTestProfiles.PROFILE_ID, root),
                        id -> RVP_FireSupportTestProfiles.projectileWeapon()));
        assertAll(
                () -> assertTrue(error.getMessage().contains("未知字段")),
                () -> assertTrue(error.getMessage().contains("ID 重复")),
                () -> assertTrue(error.getMessage().contains("有限数值")));
    }

    @Test
    void rejectsMissingNonRvpAndUnsupportedWeaponKinds() {
        assertThrows(IllegalArgumentException.class,
                () -> RVP_FireSupportProfileParser.parseAll(
                        Map.of(RVP_FireSupportTestProfiles.PROFILE_ID, RVP_FireSupportTestProfiles.validJson()), id -> null));

        RVP_FireSupportResolvedWeapon laser = new RVP_FireSupportResolvedWeapon(
                RVP_EnumWeaponKind.LASER, false, false, false, 1200, 0, false, 0.0F, 0.0F);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> RVP_FireSupportProfileParser.parseAll(
                        Map.of(ResourceLocation.fromNamespaceAndPath("rvp", "laser"), RVP_FireSupportTestProfiles.validJson()), id -> laser));
        assertTrue(error.getMessage().contains("不是可投送"));
    }

    @Test
    void invalidCandidateDoesNotReplaceLastPublishedSnapshot() {
        RVP_FireSupportProfileManager manager = RVP_FireSupportProfileManager.INSTANCE;
        assertTrue(manager.tryPublish(Map.of(RVP_FireSupportTestProfiles.PROFILE_ID, RVP_FireSupportTestProfiles.validJson()),
                id -> RVP_FireSupportTestProfiles.projectileWeapon()));
        RVP_FireSupportSnapshot accepted = manager.snapshot();
        JsonObject invalid = RVP_FireSupportTestProfiles.validJson().getAsJsonObject();
        invalid.addProperty("schema_version", 0);
        assertFalse(manager.tryPublish(Map.of(RVP_FireSupportTestProfiles.PROFILE_ID, invalid),
                id -> RVP_FireSupportTestProfiles.projectileWeapon()));
        assertSame(accepted, manager.snapshot());
    }
}
