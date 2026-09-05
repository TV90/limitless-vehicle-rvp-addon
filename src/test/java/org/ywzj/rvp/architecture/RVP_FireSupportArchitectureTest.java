package org.ywzj.rvp.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;

class RVP_FireSupportArchitectureTest {
    private static final Path FIRE_SUPPORT_ROOT = Path.of("src/main/java/org/ywzj/rvp/firesupport");

    @Test
    void commonFireSupportDomainDoesNotImportClientTypesOrHardCodeWeaponPaths() throws IOException {
        for (Path source : javaSources(FIRE_SUPPORT_ROOT)) {
            String text = Files.readString(source);
            String compact = text.replaceAll("\\s+", "").toLowerCase();
            assertFalse(text.contains("import net.minecraft.client") || text.contains("import org.ywzj.rvp.client"),
                    source + " 不得引入客户端类型");
            assertFalse(compact.contains("weaponid.getpath().equals(") || compact.contains("weaponid.getpath().contains("),
                    source + " 不得按具体武器路径分支");
        }
    }

    @Test
    void fireSupportFeatureDoesNotAddMixinLogic() throws IOException {
        Path mixinRoot = Path.of("src/main/java/org/ywzj/rvp/mixin");
        for (Path source : javaSources(mixinRoot)) {
            assertFalse(Files.readString(source).contains("FireSupport"), source + " 不得承载炮火支援逻辑");
        }
    }

    private static List<Path> javaSources(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }
}
