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
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_VisualArchitectureTest {
    /** 需要保持物理侧安全的新公共/服务端源码根目录。 */
    private static final List<Path> SIDE_SAFE_ROOTS = List.of(
            Path.of("src/main/java/org/ywzj/rvp/server/visual"),
            Path.of("src/main/java/org/ywzj/rvp/server/remotevisibility"),
            Path.of("src/main/java/org/ywzj/rvp/network/visual"),
            Path.of("src/main/java/org/ywzj/rvp/network/remotevisibility"),
            Path.of("src/main/java/org/ywzj/rvp/weapon/visual/api"));
    /** 阶段 A 前已经存在的网络物理侧债务；本功能不得扩大该集合。 */
    private static final Set<String> LEGACY_NETWORK_CLIENT_IMPORTS = Set.of(
            "C2SSetGPSTarget.java",
            "S2CApsFlameLink.java",
            "S2CApsHudSync.java",
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
            assertFalse(text.contains("RemoteVehicleVisual") || text.contains("RemoteAmmoVisual"),
                    source + " must not contain remote visibility feature logic");
        }
    }

    @Test
    void onlyDhApiBridgeMayReferenceDistantHorizonsTypes() throws IOException {
        Path mainRoot = Path.of("src/main/java/org/ywzj/rvp");
        for (Path source : javaSources(mainRoot)) {
            String text = Files.readString(source);
            if (text.contains("com.seibel.distanthorizons")) {
                assertEquals("RVP_DhApi7Bridge.java", source.getFileName().toString(),
                        source + " bypasses the optional DH class-loading boundary");
            }
        }
    }

    @Test
    void legacyExtendedAirClassesWereRemovedWithoutCompatibilityWrappers() {
        Path mainRoot = Path.of("src/main/java/org/ywzj/rvp");
        List<String> removedNames = List.of(
                "RVP_" + "ExtendedAirEntityBroadcastService.java",
                "S2C" + "ExtendedAirVisualSnapshot.java",
                "RVP_Client" + "ExtendedAirVisualState.java",
                "RVP_" + "ExtendedAirEntityRenderer.java");
        for (String removedName : removedNames) {
            assertFalse(javaSourcesUnchecked(mainRoot).stream()
                            .anyMatch(path -> path.getFileName().toString().equals(removedName)),
                    removedName + " must not remain as a compatibility wrapper");
        }
    }

    @Test
    void remoteVisibilityCodeDoesNotBranchOnConcreteResourceIds() throws IOException {
        for (Path root : List.of(
                Path.of("src/main/java/org/ywzj/rvp/server/remotevisibility"),
                Path.of("src/main/java/org/ywzj/rvp/client/render/remotevisibility"))) {
            for (Path source : javaSources(root)) {
                String compact = Files.readString(source).replaceAll("\\s+", "").toLowerCase();
                boolean forbidden = compact.contains("getvehicleid().getpath().equals(")
                        || compact.contains("getdisplayid().getpath().equals(")
                        || compact.contains("weaponid.getpath().equals(")
                        || compact.contains("modelid.getpath().equals(");
                assertFalse(forbidden, source + " hard-codes a vehicle, display, weapon, or model resource ID");
            }
        }
    }

    @Test
    void remoteVehicleProxyAndRendererStayOnTheIndependentStaticPath() throws IOException {
        Path state = Path.of("src/main/java/org/ywzj/rvp/client/state/remotevisibility/"
                + "RVP_ClientRemoteVehicleVisualState.java");
        Path renderer = Path.of("src/main/java/org/ywzj/rvp/client/render/remotevisibility/"
                + "RVP_RemoteVehicleVisualRenderer.java");
        Path billboardManager = Path.of("src/main/java/org/ywzj/rvp/client/render/remotevisibility/"
                + "RVP_RemoteVehicleBillboardManager.java");
        String stateText = Files.readString(state);
        String rendererText = Files.readString(renderer);
        String billboardManagerText = Files.readString(billboardManager);

        assertFalse(stateText.contains(".addEntity("),
                "remote vehicle proxies must never join ClientLevel");
        assertFalse(rendererText.contains("EntityRenderDispatcher")
                        || rendererText.contains("LocalVehiclePlayer")
                        || rendererText.contains("applyMotionFacing")
                        || rendererText.contains("FULL_BRIGHT"),
                "remote vehicles must use the independent static body path with controlled lighting");
        assertFalse(rendererText.contains("event.getFrustum().isVisible")
                        || rendererText.contains("minecraft.renderBuffers().bufferSource()"),
                "remote vehicles must not use the old world far plane or shared world buffers");
        assertTrue(rendererText.contains("RVP_RemoteVehicleRenderScope.open")
                        && rendererText.contains("REMOTE_BUFFERS.endBatch()"),
                "remote vehicle vertices must be submitted inside the scoped projection and fog pass");
        assertFalse(billboardManagerText.contains("EntityRenderDispatcher")
                        || billboardManagerText.contains("LocalVehiclePlayer"),
                "remote vehicle billboards must stay on the independent static body path");
        assertTrue(rendererText.indexOf("prepareOneSnapshot")
                        < rendererText.indexOf("RVP_RemoteVehicleRenderScope.open"),
                "dynamic snapshots must be prepared before entering the remote projection scope");
    }

    @Test
    void gunnerAndUavSelfPathsUseTheAuthoritativeLeaseService() throws IOException {
        Path gunner = Path.of("src/main/java/org/ywzj/rvp/entity/gunner/RVP_GunnerVehicleTickService.java");
        Path linkedUav = Path.of("src/main/java/org/ywzj/rvp/event/RVP_LinkedUavEventHandler.java");
        Path loiterUav = Path.of("src/main/java/org/ywzj/rvp/uav/RVP_UavLoiterTickService.java");
        String gunnerText = Files.readString(gunner).replaceAll("\\s+", "");
        String linkedUavText = Files.readString(linkedUav).replaceAll("\\s+", "");
        String loiterUavText = Files.readString(loiterUav).replaceAll("\\s+", "");

        assertFalse(gunnerText.contains("keepChunkLoaded("),
                "Gunner vehicle self paths must only be submitted by the remote vehicle lease service");
        assertFalse(linkedUavText.contains("keepChunkLoaded(vehicle,vehicle.position())")
                        || linkedUavText.contains("vehicle.position().add(vehicle.getLookAngle()"),
                "deployable UAV self paths must not duplicate the authoritative lease service");
        assertFalse(loiterUavText.contains("keepChunkLoaded(uav,uav.position())"),
                "loiter UAV self paths must not duplicate the authoritative lease service");
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

    private static List<Path> javaSourcesUnchecked(Path root) {
        try {
            return javaSources(root);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to scan Java sources under " + root, exception);
        }
    }
}
