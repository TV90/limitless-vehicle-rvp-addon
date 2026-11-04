package org.ywzj.rvp.client.compat.distanthorizons;

import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataCache;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataRepo;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

/** 使用官方 API 数据对象驱动正式选点路径；替代磁盘数据读取，不启动 Minecraft 或 DH 世界。 */
class RVP_DhFocusTerrainRaycastTest {
    @Test
    void aircraftAboveBuildHeightCanLockTerrainSixKilometersAway() {
        Vec3 origin = new Vec3(0.75D, 1000.0D, 0.25D);
        Vec3 expected = new Vec3(0.75D, 64.0D, 6000.5D);
        // 调用官方对象构造真实地面柱；其他柱显式缺失，不能让缺口提前终止整条射线。
        DhApiTerrainDataPoint ground = column(0, 64, false);
        ApiTerrain terrain = new ApiTerrain((x, z) -> x == 0 && z == 6000
                ? new DhApiTerrainDataPoint[]{ground} : null);
        // 调用正式 DH 适配和遍历；相机高于 320 格仍命中原始视线上的同一地面点。
        assertPoint(expected, terrain.cast(origin, expected.subtract(origin), 48_000));
        assertTrue(terrain.firstZ > 4000, "应先跳过世界高度上限以上的空中路段");
        assertTrue(terrain.closed);
    }

    @Test
    void missingColumnsDoNotHideDistantFractionalSurface() {
        Vec3 origin = new Vec3(0.99D, 200.0D, 0.99D);
        Vec3 expected = new Vec3(0.99D, 64.0D, 4000.99D);
        DhApiTerrainDataPoint ground = column(-20, 64, false);
        // 调用正式路径，落点在格子末端，不能把四舍五入后的相邻柱当作真实命中。
        ApiTerrain terrain = new ApiTerrain((x, z) -> x == 0 && z == 4000
                ? new DhApiTerrainDataPoint[]{ground} : null);
        assertPoint(expected, terrain.cast(origin, expected.subtract(origin), 48_000));
        assertTrue(terrain.reads > 4000);
    }

    @Test
    void airAndUnorderedHeightSegmentsCannotReplaceTheNearestSurface() {
        Vec3 origin = new Vec3(0.5D, 200.0D, 0.0D);
        Vec3 expected = new Vec3(0.5D, 100.0D, 2000.5D);
        DhApiTerrainDataPoint air = column(80, 200, true);
        DhApiTerrainDataPoint bottom = column(20, 60, false);
        DhApiTerrainDataPoint top = column(90, 100, false);
        // 调用正式路径；空气不能成为锁点，且实心段数组顺序不应决定命中。
        ApiTerrain terrain = new ApiTerrain((x, z) -> z == 1000
                ? new DhApiTerrainDataPoint[]{air}
                : z == 2000 ? new DhApiTerrainDataPoint[]{bottom, null, top} : null);
        assertPoint(expected, terrain.cast(origin, expected.subtract(origin), 48_000));
    }

    @Test
    void nearerWallOccludesFarTerrain() {
        Vec3 origin = new Vec3(0.5D, 200.0D, 0.0D);
        Vec3 far = new Vec3(0.5D, 64.0D, 4000.0D);
        DhApiTerrainDataPoint wall = column(0, 200, false);
        DhApiTerrainDataPoint ground = column(0, 64, false);
        // 调用正式路径，先命中 1500 格的墙面，不能穿透它锁住后方地面。
        ApiTerrain terrain = new ApiTerrain((x, z) -> z == 1500
                ? new DhApiTerrainDataPoint[]{wall}
                : z == 4000 ? new DhApiTerrainDataPoint[]{ground} : null);
        assertPoint(new Vec3(0.5D, 149.0D, 1500.0D),
                terrain.cast(origin, far.subtract(origin), 48_000));
    }

    @Test
    void negativeCoordinatesAndExactColumnBoundaryRemainAccurate() {
        Vec3 origin = new Vec3(-1.0D, 100.0D, 0.0D);
        DhApiTerrainDataPoint wall = column(0, 200, false);
        // 调用正式负向遍历，起点恰在整格边界时仍向负坐标推进，不漏掉目标柱。
        ApiTerrain terrain = new ApiTerrain((x, z) -> x == -1 && z == -2001
                ? new DhApiTerrainDataPoint[]{wall} : null);
        assertPoint(new Vec3(-1.0D, 100.0D, -2000.0D),
                terrain.cast(origin, new Vec3(0.0D, 0.0D, -1.0D), 48_000));
    }

    @Test
    void verticalRayAboveBuildHeightOnlyReadsOneColumn() {
        DhApiTerrainDataPoint ground = column(0, 64, false);
        ApiTerrain terrain = new ApiTerrain((x, z) -> new DhApiTerrainDataPoint[]{ground});
        // 调用正式路径，纯竖直射线不依赖水平步长，也不重复读取同一根柱。
        assertPoint(new Vec3(-0.5D, 64.0D, 0.5D), terrain.cast(
                new Vec3(-0.5D, 2000.0D, 0.5D), new Vec3(0.0D, -7.0D, 0.0D), 48_000));
        assertEquals(1, terrain.reads);
    }

    @Test
    void diagonalRayFindsAThinColumnNearItsFarCorner() {
        Vec3 origin = new Vec3(-0.9D, 100.0D, -0.99D);
        Vec3 expected = new Vec3(2000.01D, 100.0D, 2000.0D);
        DhApiTerrainDataPoint wall = column(0, 200, false);
        // 调用正式逐边界遍历；这根柱与视线只短暂相交，固定步长抽样容易跨过去。
        ApiTerrain terrain = new ApiTerrain((x, z) -> x == 2000 && z == 1999
                ? new DhApiTerrainDataPoint[]{wall} : null);
        Vec3 direction = expected.subtract(origin);
        Vec3 hit = terrain.cast(origin, direction, 48_000);
        assertNotNull(hit);
        assertEquals(2000.0D, hit.x, 1.0E-7D);
        assertTrue(hit.z > 1999.9D && hit.z < 2000.0D);
        assertTrue(hit.subtract(origin).normalize().distanceTo(direction.normalize()) < 1.0E-9D);
    }

    @Test
    void skyOutsideHeightRangeDoesNotReadTerrain() {
        ApiTerrain terrain = new ApiTerrain((x, z) -> { throw new AssertionError("天空不应读取地形"); });
        // 调用正式高度裁剪；向上和向外水平观察时不能构造地面点。
        assertNull(terrain.cast(new Vec3(0.0D, 1000.0D, 0.0D), new Vec3(0.0D, 1.0D, 1.0D), 48_000));
        assertNull(terrain.cast(new Vec3(0.0D, 1000.0D, 0.0D), new Vec3(0.0D, 0.0D, 1.0D), 48_000));
        assertEquals(0, terrain.reads);
        assertTrue(terrain.closed);
    }

    @Test
    void missingDataAndRangeLimitNeverInventAnEndpoint() {
        DhApiTerrainDataPoint wall = column(0, 200, false);
        ApiTerrain terrain = new ApiTerrain((x, z) -> z == 5000
                ? new DhApiTerrainDataPoint[]{wall} : null);
        // 调用正式射程门，范围外真实柱与完全无数据均返回未命中，不返回射线末端。
        assertNull(terrain.cast(new Vec3(0.5D, 100.0D, 0.5D), new Vec3(0.0D, 0.0D, 1.0D), 4096));
        assertTrue(terrain.reads <= 4097);
        assertTrue(terrain.closed);
    }

    @Test
    void fullFortyEightKilometerDiagonalMissHasBoundedWork() {
        ApiTerrain terrain = new ApiTerrain((x, z) -> null);
        // 调用正式最远查询，斜向缺失数据也必须有明确工作量上限。
        assertNull(terrain.cast(new Vec3(0.1D, 100.0D, 0.3D), new Vec3(1.0D, 0.0D, 0.99D), 48_000));
        assertTrue(terrain.reads > 60_000 && terrain.reads < 70_000);
        assertTrue(terrain.closed);
    }

    @Test
    void unavailableDataStillClosesTheOfficialCache() {
        ApiTerrain terrain = new ApiTerrain((x, z) -> { throw new IllegalStateException("模拟世界卸载"); });
        // 调用正式异常回退；查询失败应保留方向锁的未命中语义，并释放缓存。
        assertNull(terrain.cast(new Vec3(0.5D, 100.0D, 0.5D), new Vec3(0.0D, 0.0D, 1.0D), 48_000));
        assertTrue(terrain.closed);
    }

    /** 构造官方高度段对象；仅替代方块状态的空气判断，不模拟正式求交算法。 */
    private static DhApiTerrainDataPoint column(int bottom, int top, boolean air) {
        IDhApiBlockStateWrapper block = api(IDhApiBlockStateWrapper.class, (proxy, method, args) -> {
            if (method.getName().equals("isAir")) return air;
            throw new AssertionError("不应访问额外方块接口：" + method.getName());
        });
        return DhApiTerrainDataPoint.create((byte) 0, 0, 15, bottom, top, block, null);
    }

    /** 对照独立指定的世界坐标核对落点，不复用生产求交方法生成期望值。 */
    private static void assertPoint(Vec3 expected, Vec3 actual) {
        assertNotNull(actual);
        assertEquals(0.0D, actual.distanceTo(expected), 1.0E-6D);
    }

    /** 使用 JDK 接口代理替代 DH 的世界和磁盘入口；任何未预期接口调用均让测试失败。 */
    private static <T> T api(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    /** 官方数据接口的最小测试夹具，只提供指定柱数据并记录缓存是否释放。 */
    private static final class ApiTerrain {
        /** 测试场景的地形柱；null 表示官方接口查询失败，空数组表示已知空柱。 */
        private final BiFunction<Integer, Integer, DhApiTerrainDataPoint[]> columns;
        /** 本次查询实际读取的柱数，用于验证裁剪和遍历预算。 */
        private int reads;
        /** 第一根查询柱的 Z 坐标，用于验证高空路段确实被跳过。 */
        private int firstZ;
        /** 官方缓存是否已经关闭，异常和未命中也必须释放。 */
        private boolean closed;

        private ApiTerrain(BiFunction<Integer, Integer, DhApiTerrainDataPoint[]> columns) {
            this.columns = columns;
        }

        /** 调用正式 DH 数据适配层，接口代理只提供输入，不代替生产遍历与几何计算。 */
        private Vec3 cast(Vec3 origin, Vec3 direction, int range) {
            IDhApiTerrainDataCache cache = api(IDhApiTerrainDataCache.class, (proxy, method, args) -> {
                if (method.getName().equals("close")) {
                    closed = true;
                    return null;
                }
                throw new AssertionError("不应访问额外缓存接口：" + method.getName());
            });
            IDhApiLevelWrapper level = api(IDhApiLevelWrapper.class, (proxy, method, args) -> switch (method.getName()) {
                case "getMinHeight" -> -64;
                case "getMaxHeight" -> 320;
                default -> throw new AssertionError("不应访问额外世界接口：" + method.getName());
            });
            IDhApiTerrainDataRepo repository = api(IDhApiTerrainDataRepo.class, (proxy, method, args) -> {
                if (method.getName().equals("createSoftCache")) return cache;
                if (method.getName().equals("getColumnDataAtBlockPos")) {
                    assertSame(level, args[0]);
                    assertSame(cache, args[3]);
                    int x = (int) args[1];
                    int z = (int) args[2];
                    if (reads++ == 0) firstZ = z;
                    var points = columns.apply(x, z);
                    return points == null ? DhApiResult.createFail("测试地形数据暂未就绪")
                            : DhApiResult.createSuccess(points);
                }
                throw new AssertionError("禁止退回旧射线或调用写入接口：" + method.getName());
            });
            // 调用与真实后台任务相同的正式方法，覆盖公开 API 适配、逐柱扫描和缓存关闭。
            return RVP_DhApi7Bridge.raycastTerrainColumns(repository, level, origin, direction, range);
        }
    }
}
