package org.ywzj.rvp.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RVP_VisualArchitectureTest {
    /** 需要保持物理侧安全的新公共/服务端源码根目录。 */
    private static final List<Path> SIDE_SAFE_ROOTS = List.of(
            Path.of("src/main/java/org/ywzj/rvp/server/visual"),
            Path.of("src/main/java/org/ywzj/rvp/network/visual"),
            Path.of("src/main/java/org/ywzj/rvp/weapon/visual/api"));
    /** 阶段 A 前已经存在的网络物理侧债务；本功能不得扩大该集合。 */
    private static final Set<String> LEGACY_NETWORK_CLIENT_IMPORTS = Set.of(
            "C2SSetGPSTarget.java",
            "S2CApsFlameLink.java",
            "S2CApsHudSync.java",
            "S2CExtendedAirVisualSnapshot.java",
            "S2CExternalRadarSnapshot.java",
            "S2CGpsStateSync.java",
            "S2CGunnerVehicleSync.java",
            "S2CHbmMissileSnapshot.java",
            "S2CLoiterStateSync.java",
            "S2CMarkedBlockSync.java",
            "S2CRemoteAmmoSnapshot.java",
            "S2CTacticalRevealSnapshot.java");

    @Test
    void newCommonServerAndNetworkVisualCodeDoesNotImportClientTypes() throws IOException {
        for (Path root : SIDE_SAFE_ROOTS) {
            for (Path source : javaSources(root)) {
                String text = Files.readString(source);
                assertFalse(text.contains("import net.minecraft.client"), source + " imports Minecraft client code");
                assertFalse(text.contains("import org.ywzj.rvp.client"), source + " imports RVP client code");
            }
        }
    }

    @Test
    void networkPackageDoesNotAddClientImportsBeyondLegacyBaseline() throws IOException {
        Set<String> actual = new TreeSet<>();
        for (Path source : javaSources(Path.of("src/main/java/org/ywzj/rvp/network"))) {
            String text = Files.readString(source);
            if (text.contains("import net.minecraft.client") || text.contains("import org.ywzj.rvp.client")) {
                actual.add(source.getFileName().toString());
            }
        }

        assertEquals(new TreeSet<>(LEGACY_NETWORK_CLIENT_IMPORTS), actual,
                "network 包出现了新的客户端直接依赖；新消息必须通过公共消费端口分发");
    }

    @Test
    void visualFeatureDoesNotAddOrExpandMixins() throws IOException {
        Path mixinRoot = Path.of("src/main/java/org/ywzj/rvp/mixin");
        for (Path source : javaSources(mixinRoot)) {
            String text = Files.readString(source);
            assertFalse(text.contains("RVP_VisualEffect") || text.toLowerCase().contains("thermobaric"),
                    source + " must not contain thermobaric visual feature logic");
        }
    }

    @Test
    void entityAndRendererCodeDoesNotUseWeaponIdForThermobaricDispatch() throws IOException {
        for (Path root : List.of(
                Path.of("src/main/java/org/ywzj/rvp/entity"),
                Path.of("src/main/java/org/ywzj/rvp/client/render"))) {
            for (Path source : javaSources(root)) {
                String compact = Files.readString(source).replaceAll("\\s+", "").toLowerCase();
                boolean forbidden = compact.contains("weaponid.getpath().equals(\"thermobaric")
                        || compact.contains("weaponid.getpath().contains(\"thermobaric");
                assertFalse(forbidden, source + " hard-codes a thermobaric weapon ID");
            }
        }
    }

    private static List<Path> javaSources(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }
}
