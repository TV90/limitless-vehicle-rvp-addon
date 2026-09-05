package org.ywzj.rvp.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void stageCMissionDoesNotCacheMutablePlayerOrItemStack() throws IOException {
        String mission = Files.readString(FIRE_SUPPORT_ROOT.resolve("server/RVP_FireSupportMission.java"));
        assertFalse(mission.contains("import net.minecraft.world.entity.player.Player")
                        || mission.contains("import net.minecraft.world.item.ItemStack"),
                "任务对象不得缓存可变玩家或物品堆");
        assertTrue(mission.contains("terminalInstanceId") && mission.contains("profileRevision")
                        && mission.contains("callDeadlineTick") && mission.contains("ceaseFireEffectiveTick"),
                "任务对象必须冻结终端、revision、呼叫和停火生命周期字段");
    }

    private static List<Path> javaSources(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }
}
