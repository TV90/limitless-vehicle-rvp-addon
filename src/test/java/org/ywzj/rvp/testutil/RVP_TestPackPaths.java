package org.ywzj.rvp.testutil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 多包拆分（rvp 母包 + rvp_eu / rvp_asia / rvp_america）后的测试路径解析。
 *
 * <p>按母包优先、子包依次的顺序查找，测试不再假定资源一定在 rvp 下。</p>
 */
public final class RVP_TestPackPaths {

    public static final List<String> PACKS = List.of("rvp", "rvp_eu", "rvp_asia", "rvp_america");

    private RVP_TestPackPaths() {
    }

    /** data/&lt;ns&gt;/&lt;kind&gt;/&lt;file&gt;。 */
    public static Path data(String kind, String file) {
        return resolve("data", kind, file);
    }

    /** assets/&lt;ns&gt;/&lt;kind&gt;/&lt;file&gt;。 */
    public static Path assets(String kind, String file) {
        return resolve("assets", kind, file);
    }

    /** 当前工作区里实际存在的武器目录（用于遍历全部武器）。 */
    public static List<Path> weaponDirs() {
        List<Path> dirs = new ArrayList<>();
        for (String ns : PACKS) {
            Path dir = Path.of("limitless_vehicle", ns, "data", ns, "weapons");
            if (Files.isDirectory(dir)) {
                dirs.add(dir);
            }
        }
        return dirs;
    }

    /** 找到指定武器（支持 rvp:xxx 或纯文件名）所在目录。 */
    public static Path weaponDirFor(String weaponId) {
        String file = weaponId.startsWith("rvp:") ? weaponId.substring(4) : weaponId;
        for (String ns : PACKS) {
            Path dir = Path.of("limitless_vehicle", ns, "data", ns, "weapons");
            if (Files.isRegularFile(dir.resolve(file + ".json"))) {
                return dir;
            }
        }
        return Path.of("limitless_vehicle", "rvp", "data", "rvp", "weapons");
    }

    private static Path resolve(String side, String kind, String file) {
        for (String ns : PACKS) {
            Path path = Path.of("limitless_vehicle", ns, side, ns, kind, file);
            if (Files.exists(path)) {
                return path;
            }
        }
        return Path.of("limitless_vehicle", "rvp", side, "rvp", kind, file);
    }
}
