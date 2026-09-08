package org.ywzj.rvp.firesupport;

import com.google.gson.JsonObject;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.firesupport.config.RVP_FireSupportProfileManager;
import org.ywzj.rvp.firesupport.config.RVP_FireSupportProfileParser;
import org.ywzj.rvp.firesupport.data.RVP_FireSupportResolvedWeapon;
import org.ywzj.rvp.firesupport.data.RVP_FireSupportProfile;
import org.ywzj.rvp.firesupport.data.RVP_FireSupportSnapshot;
import org.ywzj.rvp.firesupport.delivery.RVP_FireSupportDeliveryTypes;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;

import static org.junit.jupiter.api.Assertions.*;

class RVP_FireSupportProfileParserTest {
    @Test
    void parsesCurrentSchemaIntoImmutableMapsAndActualResolvedWeaponData() {
        RVP_FireSupportProfile profile = RVP_FireSupportTestProfiles.parse();
        assertEquals(3, profile.fireModes().size());
        assertEquals(3, profile.patterns().size());
        assertEquals(2, profile.munitions().get("he").weapons().size());
        assertInstanceOf(RVP_FireSupportDeliveryTypes.VerticalProjectileData.class,
                profile.munitions().get("he").weapons().get(0).deliveryData());
        assertThrows(UnsupportedOperationException.class, () -> profile.fireModes().clear());
        assertThrows(UnsupportedOperationException.class, () -> profile.munitions().get("he").weapons().clear());
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
    void rejectsLegacySingleWeaponShapeDuplicateWeaponsAndInvalidWeights() {
        JsonObject legacy = RVP_FireSupportTestProfiles.validJson().getAsJsonObject();
        JsonObject legacyMunition = legacy.getAsJsonArray("munitions").get(0).getAsJsonObject();
        legacyMunition.addProperty("weapon", "rvp:test_round");
        assertThrows(IllegalArgumentException.class, () -> RVP_FireSupportProfileParser.parseAll(
                Map.of(RVP_FireSupportTestProfiles.PROFILE_ID, legacy),
                id -> RVP_FireSupportTestProfiles.projectileWeapon()));

        JsonObject invalid = RVP_FireSupportTestProfiles.validJson().getAsJsonObject();
        var weapons = invalid.getAsJsonArray("munitions").get(0).getAsJsonObject().getAsJsonArray("weapons");
        weapons.get(0).getAsJsonObject().addProperty("weight", 512);
        weapons.get(1).getAsJsonObject().addProperty("weapon", "rvp:test_round");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> RVP_FireSupportProfileParser.parseAll(
                        Map.of(RVP_FireSupportTestProfiles.PROFILE_ID, invalid),
                        id -> RVP_FireSupportTestProfiles.projectileWeapon()));
        assertAll(
                () -> assertTrue(error.getMessage().contains("不得重复")),
                () -> assertTrue(error.getMessage().contains("权重总和不得超过 512")));
    }

    @Test
    void rejectsEmptyWeaponListAndNonPositiveWeight() {
        JsonObject empty = RVP_FireSupportTestProfiles.validJson().getAsJsonObject();
        empty.getAsJsonArray("munitions").get(0).getAsJsonObject()
                .add("weapons", new com.google.gson.JsonArray());
        IllegalArgumentException emptyError = assertThrows(IllegalArgumentException.class,
                () -> RVP_FireSupportProfileParser.parseAll(
                        Map.of(RVP_FireSupportTestProfiles.PROFILE_ID, empty),
                        id -> RVP_FireSupportTestProfiles.projectileWeapon()));
        assertTrue(emptyError.getMessage().contains("数量必须在 [1, 64] 内"));

        JsonObject zero = RVP_FireSupportTestProfiles.validJson().getAsJsonObject();
        zero.getAsJsonArray("munitions").get(0).getAsJsonObject().getAsJsonArray("weapons")
                .get(0).getAsJsonObject().addProperty("weight", 0);
        IllegalArgumentException weightError = assertThrows(IllegalArgumentException.class,
                () -> RVP_FireSupportProfileParser.parseAll(
                        Map.of(RVP_FireSupportTestProfiles.PROFILE_ID, zero),
                        id -> RVP_FireSupportTestProfiles.projectileWeapon()));
        assertTrue(weightError.getMessage().contains("必须在 [1, 512] 内"));

        JsonObject overflow = RVP_FireSupportTestProfiles.validJson().getAsJsonObject();
        overflow.getAsJsonArray("munitions").get(0).getAsJsonObject().getAsJsonArray("weapons")
                .get(0).getAsJsonObject().addProperty("weight", 513);
        IllegalArgumentException overflowError = assertThrows(IllegalArgumentException.class,
                () -> RVP_FireSupportProfileParser.parseAll(
                        Map.of(RVP_FireSupportTestProfiles.PROFILE_ID, overflow),
                        id -> RVP_FireSupportTestProfiles.projectileWeapon()));
        assertTrue(overflowError.getMessage().contains("必须在 [1, 512] 内"));
    }

    @Test
    void validatesEveryMixedMemberAndRejectsUnknownDeliveryType() {
        JsonObject missingSecond = RVP_FireSupportTestProfiles.validJson().getAsJsonObject();
        IllegalArgumentException missingError = assertThrows(IllegalArgumentException.class,
                () -> RVP_FireSupportProfileParser.parseAll(
                        Map.of(RVP_FireSupportTestProfiles.PROFILE_ID, missingSecond),
                        id -> RVP_FireSupportTestProfiles.WEAPON_ID.equals(id)
                                ? RVP_FireSupportTestProfiles.projectileWeapon() : null));
        assertTrue(missingError.getMessage().contains("munitions[0].weapons[1].weapon"));

        JsonObject unknownDelivery = RVP_FireSupportTestProfiles.validJson().getAsJsonObject();
        unknownDelivery.getAsJsonArray("munitions").get(0).getAsJsonObject().getAsJsonArray("weapons")
                .get(1).getAsJsonObject().getAsJsonObject("delivery")
                .addProperty("type", "rvp:unknown_delivery");
        IllegalArgumentException deliveryError = assertThrows(IllegalArgumentException.class,
                () -> RVP_FireSupportProfileParser.parseAll(
                        Map.of(RVP_FireSupportTestProfiles.PROFILE_ID, unknownDelivery),
                        id -> RVP_FireSupportTestProfiles.projectileWeapon()));
        assertTrue(deliveryError.getMessage().contains("未知投送工厂"));
    }

    @Test
    void rejectsMissingNonRvpAndUnsupportedWeaponKinds() {
        assertThrows(IllegalArgumentException.class,
                () -> RVP_FireSupportProfileParser.parseAll(
                        Map.of(RVP_FireSupportTestProfiles.PROFILE_ID, RVP_FireSupportTestProfiles.validJson()), id -> null));

        RVP_FireSupportResolvedWeapon laser = new RVP_FireSupportResolvedWeapon(
                RVP_EnumWeaponKind.LASER, RVP_EnumGuidanceType.NONE, false, false, false, false, false,
                1200, 0, false, 0.0F, 0.0F);
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
