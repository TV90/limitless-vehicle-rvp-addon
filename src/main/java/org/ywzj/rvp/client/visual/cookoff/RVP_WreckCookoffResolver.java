package org.ywzj.rvp.client.visual.cookoff;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.weapon.data.VehicleCannonWeaponData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.item.AmmoItem;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeOBB;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleMultiWeapons;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** 从现有部件与 OBB 自动推导出口；只读结构，不增加骨骼或车型配置。 */
public final class RVP_WreckCookoffResolver {
    /**
     * 接缝采样时长表的周期，单位"段"。
     * {@link RVP_WreckCookoffGeometry#sampleBurstTicks(int)} 在 4 个取值上循环，因此取 4；
     * 段序对它取模后，时长表进入稳定循环，"每段停留 3～6 tick"成为精确性质而非近似。
     */
    public static final int SEAM_CYCLE_TICKS = 4;
    /**
     * 每座炮塔同时进行的接缝采样点数；同一时刻这么多处在喷火，而不是只有一处。
     * 各点用不同的采样点序号，因而落在不同底缘弧段并各自独立换点。
     */
    public static final int SEAM_POINTS_PER_TURRET = 12;
    /**
     * 每个舱盖同时进行的火星采样点数；用户定版：每个舱盖保留 3 个火星出口。
     * 三点沿 OBB 局部 X 轴等分展开，避免火星叠在同一处；舱盖火柱只取中心点。
     */
    public static final int HATCH_POINTS_PER_HATCH = 3;
    /** 每个舱盖用于绘制火柱的代表采样点；火星仍使用同一舱盖的全部采样点。 */
    public static final int HATCH_COLUMN_SAMPLE_INDEX = HATCH_POINTS_PER_HATCH / 2;

    /** 三种出口分别参与分类粒子配额；炮口已不参与火星，只保留火柱。 */
    public enum Kind {
        /** 舱盖高柱与舱盖火星。 */
        HATCH,
        /** 炮塔接缝火星。 */
        SEAM,
        /** 炮口轴向火柱（不喷火星）。 */
        MUZZLE
    }

    /**
     * 缓存的出口绑定，只持有当前世界实体已有的结构引用。
     * @param kind 出口类别
     * @param cube OBB 模式的锚点，炮口模式为 null
     * @param local 相对 OBB 中心的坐标（格）
     * @param localDirection 部件局部方向；舱盖/接缝改用采样方向，故舱盖留 UP 仅作占位
     * @param weapon 炮口所属武器站，其余为 null
     * @param sampleIndex 采样点序号（舱盖 3 点、接缝 12 点各自编号）；炮口恒为 0
     * @param width 柱宽，单位格
     * @param owner 所属部件，车体回退为 null；脱离后关闭该出口
     */
    public record Anchor(Kind kind, VehicleCubeOBB cube, Vec3 local, Vec3 localDirection,
                         WeaponUnit weapon, int sampleIndex, double width, PartUnit<?> owner) {}

    /** 炮口火柱锚点；炮口既不参与火星预算，也不参与采样，因此与 {@link Anchor} 分开。 */
    public record MuzzleAnchor(WeaponUnit weapon, int muzzleIndex, double width) {}

    /** 一次发现的结果：参与火星的出口 + 只画火柱的炮口。 */
    public record Discovery(List<Anchor> anchors, List<MuzzleAnchor> muzzles, List<VehicleCubeOBB> turrets) {}

    /** 一帧/一 tick 的世界空间出口，不包含客户端渲染类。 */
    public record Pose(Vec3 position, Vec3 direction) {}

    private RVP_WreckCookoffResolver() {}

    /** 判断舱盖采样点是否代表该舱盖唯一的一根火柱。 */
    public static boolean isHatchColumnAnchor(Anchor anchor) {
        return anchor != null && anchor.kind() == Kind.HATCH
                && anchor.sampleIndex() == HATCH_COLUMN_SAMPLE_INDEX;
    }

    /**
     * 每个实体建立一次绑定；所有识别都基于公开部件和武器数据。
     *
     * @param includeMuzzles 是否收集炮口火柱锚点；控制器在初始化时收集一次，
     *                       每 tick 的重新发现只需要火星出口
     */
    public static Discovery discover(AbstractVehicle vehicle, boolean includeMuzzles) {
        List<Anchor> result = new ArrayList<>();
        List<MuzzleAnchor> muzzles = new ArrayList<>();
        List<WeaponUnit> cannons = new ArrayList<>();
        int hatchCount = 0;
        // 调用本体部件入口，扫描现有炮塔和可识别的舱盖语义部件。
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (part.isDetached()) continue;
            if (part instanceof WeaponUnit weapon && weapon.getStructureGroup() != null
                    && weapon.getData().getRawXTurnGroup() != null
                    && weapon.getIndexedWeapons().stream().anyMatch(RVP_WreckCookoffResolver::isCannon)) {
                cannons.add(weapon);
            }
            // 调用本体数据访问器，以通用舱盖语义识别，而非固定某个车型的骨名称。
            String name = (part.getId() + " " + part.getData().getStructureBone()).toLowerCase(Locale.ROOT);
            if (name.contains("hatch") || name.contains("cupola") || name.contains("舱盖")) {
                // 调用本体 OBB 列表，取该舱盖最大有效几何作为出口。
                VehicleCubeOBB cube = largest(part.getPartCubeOBBs());
                if (cube != null) {
                    // 调用本类舱盖出口构造：每个舱盖登记 3 个采样点，各自独立换点
                    for (int i = 0; i < HATCH_POINTS_PER_HATCH; i++) {
                        result.add(hatch(cube, part, i));
                    }
                    hatchCount++;
                }
            }
        }
        // 调用本类体积排序，优先处理有真实炮塔几何的大型火炮站，最多两座炮塔。
        cannons.sort(Comparator.comparingDouble((WeaponUnit unit) -> volume(largest(unit.getPartCubeOBBs()))).reversed());
        List<VehicleCubeOBB> usedTurrets = new ArrayList<>();
        int muzzleCount = 0;
        for (WeaponUnit weapon : cannons) {
            // 调用本体出弹口配置，最多保留四个真实炮口用于绘制火柱。
            for (int i = 0; i < weapon.getBolts().size() && muzzleCount < 4; i++) {
                if (includeMuzzles) {
                    muzzles.add(new MuzzleAnchor(weapon, i, 0.4));
                }
                muzzleCount++;
            }
            // 调用本体部件 OBB，炮塔接缝按炮塔底部矩形轮廓近似，不重写结构。
            VehicleCubeOBB cube = largest(weapon.getPartCubeOBBs());
            if (cube == null || usedTurrets.size() >= 2 || usedTurrets.contains(cube)
                    || cube.width < 0.65 || cube.depth < 0.65) continue;
            usedTurrets.add(cube);
            if (hatchCount == 0) {
                // 调用本类顶部出口构造，无明确舱盖部件时在炮塔顶部近似一个舱盖（3 个采样点）
                for (int i = 0; i < HATCH_POINTS_PER_HATCH; i++) {
                    result.add(hatch(cube, weapon, i));
                }
                hatchCount = 1;
            }
            for (int i = 0; i < SEAM_POINTS_PER_TURRET; i++) {
                // 调用本类接缝锚点构造：每座炮塔登记多个采样点，各点用不同序号错开方位槽与换点节奏
                result.add(seam(cube, weapon, i));
            }
        }
        if (hatchCount == 0) {
            // 调用本体主结构，未识别炮塔的地面车以车体顶部近似泄压口；不伪造炮口/接缝。
            VehicleCubeOBB main = vehicle.getMainCubeOBB();
            if (volume(main) > 0) {
                for (int i = 0; i < HATCH_POINTS_PER_HATCH; i++) {
                    result.add(hatch(main, null, i));
                }
            }
        }
        return new Discovery(List.copyOf(result), List.copyOf(muzzles), List.copyOf(usedTurrets));
    }

    /** 优先装填物的机炮/火炮枚举类型，未知第三方弹药再按口径回退；不匹配武器 ID。 */
    private static boolean isCannon(AbstractVehicleWeapon<?> weapon) {
        if (weapon instanceof VehicleMultiWeapons multi) {
            // 调用本体多弹种列表，切换到导弹弹种也不能让同站火炮出口消失。
            return multi.getSubWeapons().stream().anyMatch(RVP_WreckCookoffResolver::isCannon);
        }
        // 调用本体及本项目武器数据，只用类型化字段判断，不硬编码弹药名称。
        var data = weapon.getData();
        boolean ballistic = data instanceof RVP_WeaponData rvp
                ? rvp.getWeaponKind() == RVP_EnumWeaponKind.MACHINEGUN : data instanceof VehicleCannonWeaponData;
        double caliber = data instanceof RVP_WeaponData rvp ? rvp.getEffectsData().getCaliber()
                : data.getCaliber() == null ? 0 : data.getCaliber();
        boolean typedAmmo = false;
        // 调用本体装填配方与 AmmoItem 枚举，机炮的可视口径缩小也不影响自动识别。
        if (data.getReload() != null && data.getReload().getAmmo() != null) {
            for (var stack : data.getReload().getAmmo().getItems()) {
                if (stack.getItem() instanceof AmmoItem ammo) {
                    typedAmmo = true;
                    // 调用本项目火炮规则，显式机枪/导弹装填物不能靠粗曳光冒充火炮。
                    if (RVP_WreckCookoffWeaponPolicy.accepts(ballistic, ammo.getAmmoType(), caliber)) return true;
                }
            }
        }
        // 调用本项目保守回退，仅未识别弹药类型时参考已有口径字段。
        return !typedAmmo && RVP_WreckCookoffWeaponPolicy.accepts(ballistic, null, caliber);
    }

    /** 最大体积的有限 OBB；缺失时不接受默认车体中心充当炮口。 */
    private static VehicleCubeOBB largest(List<VehicleCubeOBB> cubes) {
        // 调用本类体积规则，忽略零尺寸和非有限几何。
        return cubes.stream().filter(cube -> volume(cube) > 0)
                .max(Comparator.comparingDouble(RVP_WreckCookoffResolver::volume)).orElse(null);
    }

    /** 计算有效结构体积，供自动定位与排序共用。 */
    private static double volume(VehicleCubeOBB cube) {
        if (cube == null || cube.width <= 0 || cube.height <= 0 || cube.depth <= 0) return 0;
        double volume = cube.width * cube.height * cube.depth;
        return Double.isFinite(volume) ? volume : 0;
    }

    /**
     * 舱盖出口：登记 OBB、柱宽与采样点序号。每 tick 的喷口位置固定在该舱盖顶部，
     * 方向由 {@link RVP_WreckCookoffGeometry#hatchConeDirection} 在世界 Y 轴 30° 圆锥内现采样；
     * 同一舱盖的 3 个点用于火星分布，只有中心点用于绘制该舱盖的一根火柱。
     *
     * @param cube        舱盖 OBB
     * @param owner       所属部件
     * @param sampleIndex 采样点序号（0..2），决定横向展开位置与换点相位
     */
    private static Anchor hatch(VehicleCubeOBB cube, PartUnit<?> owner, int sampleIndex) {
        // 3 个采样点沿局部 X 轴等分展开：-0.22 / 0 / +0.22 个半宽，避免叠在同一处
        double lateral = (sampleIndex - (HATCH_POINTS_PER_HATCH - 1) / 2.0) * 0.22;
        return new Anchor(Kind.HATCH, cube, new Vec3(cube.width * lateral, cube.height * 0.5 + 0.025, 0),
                RVP_WreckCookoffGeometry.UP, null, sampleIndex,
                Mth.clamp(cube.width * 0.23, 0.5, 0.9), owner);
    }

    /**
     * 接缝锚点只登记所属炮塔 OBB、柱宽与采样点序号；局部坐标与外法线由
     * {@link RVP_WreckCookoffGeometry#seamSample} 每 tick 按环绕方位随机采样，
     * 因此这里不再预置方向，{@code local}/{@code localDirection} 留空。
     *
     * @param cube        炮塔 OBB
     * @param owner       所属部件
     * @param sampleIndex 采样点序号，从 0 开始；决定该点占用的方位槽
     */
    private static Anchor seam(VehicleCubeOBB cube, PartUnit<?> owner, int sampleIndex) {
        return new Anchor(Kind.SEAM, cube, Vec3.ZERO, Vec3.ZERO, null, sampleIndex, 0.3, owner);
    }

    /**
     * 按世界 tick 解析出口姿态；采样种子由 tick 派生。
     *
     * <p>刻意不叫 {@code resolve} 重载：重载解析在 {@code long}/{@code int} 两个版本间
     * 由实参静态类型决定，稍有不慎就会自递归。</p>
     *
     * @param anchor 出口绑定
     * @param tick   当前世界 tick
     */
    public static Pose resolveAtTick(Anchor anchor, long tick) {
        return resolve(anchor, samplingSeed(tick, anchor.sampleIndex()));
    }

    /**
     * 采样种子：单个采样点持续 {@link RVP_WreckCookoffGeometry#SAMPLE_BURST_MIN_TICKS}～
     * {@link RVP_WreckCookoffGeometry#SAMPLE_BURST_MAX_TICKS} tick 后换点。
     *
     * <p>时长表按段序确定并对 {@link #SEAM_CYCLE_TICKS} 取模，因此"每段确实停留 3～12 tick"
     * 是数学上成立的，而不是近似。反过来若用 {@code seed} 去推导段长，会与段边界互相打架，
     * 出现长短不一的段。</p>
     *
     * <p>实现用"整周期除法 + 周期内查表"求段号，是 O(1)；逐段累加到 {@code step} 的写法在世界
     * 运行久了以后会退化成上亿次循环，属于不能用在这里的开销。</p>
     *
     * @param tick        当前世界 tick
     * @param sampleIndex 采样点序号；同组各点用不同相位错开换点时刻，避免齐刷刷同时跳
     */
    public static int samplingSeed(long tick, int sampleIndex) {
        // 先把 tick 收进正区间：直接强转 int 会在世界运行约 2.1e9 tick 后溢出成负数，
        // 那会让 seedRandom 的取模落到负半边，采样点分布退化。错相偏移远小于该周期，不会溢出。
        int step = (int) Math.floorMod(tick + sampleIndex * 3L, 1L << 30);
        int cycleTicks = seamCycleTicks();
        // 整周期直接算出已跨过的段数与周期内余量，余量最多一个周期，再在周期内逐段推进即可
        int segment = Math.floorDiv(step, cycleTicks) * SEAM_CYCLE_TICKS;
        int cursor = Math.floorMod(step, cycleTicks);
        int remaining = cursor;
        int indexInCycle = 0;
        while (indexInCycle < SEAM_CYCLE_TICKS
                && remaining >= RVP_WreckCookoffGeometry.sampleBurstTicks(indexInCycle)) {
            remaining -= RVP_WreckCookoffGeometry.sampleBurstTicks(indexInCycle);
            segment++;
            indexInCycle++;
        }
        return segment;
    }

    /** 采样时长表一个完整周期覆盖的 tick 数：{@code sampleBurstTicks(0..3)} 之和。 */
    public static int seamCycleTicks() {
        int total = 0;
        for (int i = 0; i < SEAM_CYCLE_TICKS; i++) {
            total += RVP_WreckCookoffGeometry.sampleBurstTicks(i);
        }
        return total;
    }

    /**
     * 每 tick 更新世界锚点；渲染器插值相邻 tick 的结果。
     *
     * <p>采样出口必须由调用方传入本 tick 的采样种子（见 {@link #samplingSeed(long, int)}），
     * 这样火星与火柱读到的是同一个采样点，不会各算一份而错位。</p>
     *
     * @param anchor       出口绑定
     * @param sampleSeed   本 tick 的采样种子
     */
    public static Pose resolve(Anchor anchor, int sampleSeed) {
        // 调用本体脱离状态，已脱落部件不在原车上继续喷火。
        if (anchor.owner != null && anchor.owner.isDetached()) return null;
        if (anchor.kind == Kind.SEAM) {
            return resolveSeam(anchor, sampleSeed);
        }
        return resolveHatch(anchor, sampleSeed);
    }

    /**
     * 炮口火柱姿态：调用本体出弹计算，取包含炮管长度的真实起点与俯仰/偏航。
     * 炮口不参与火星，因此没有采样种子参数。
     */
    public static Pose resolveMuzzle(MuzzleAnchor muzzle) {
        // 调用本体脱离状态与结构组有效性，无炮塔结构时不给出口
        if (muzzle.weapon().isDetached() || muzzle.weapon().getData().getRawXTurnGroup() == null) return null;
        var bolts = muzzle.weapon().getBolts();
        if (muzzle.muzzleIndex() >= bolts.size()) return null;
        var aim = muzzle.weapon().aimContext(bolts.get(muzzle.muzzleIndex()));
        Vec3 direction = VectorUtil.rotToVec(aim.direction.x, aim.direction.y);
        return new Pose(aim.from, direction.normalize());
    }

    /**
     * 舱盖出口：位置固定在舱盖顶部（3 个采样点横向错开），
     * 方向按世界 Y 轴 30° 圆锥随机采样，单点停留 3～12 tick 后换向。
     */
    private static Pose resolveHatch(Anchor anchor, int sampleSeed) {
        VehicleCubeOBB cube = anchor.cube;
        if (cube == null || cube.position == null || cube.rotation == null) return null;
        Quaternionf rotation = new Quaternionf(cube.rotation);
        // 调用本项目几何规则得到世界坐标；调用舱盖圆锥采样得到本段的方向
        return new Pose(RVP_WreckCookoffGeometry.point(cube.position, rotation, anchor.local),
                RVP_WreckCookoffGeometry.hatchConeDirection(sampleSeed * 31 + anchor.sampleIndex()));
    }

    /**
     * 接缝出口：沿炮塔 OBB 底缘按环绕方位随机采样一个点、方向为水平外法线叠加 0°～+30° 俯仰，
     * 使同一辆车每个采样周期的火星位置与朝向都不同，且同炮塔的多个采样点互相错开。
     */
    private static Pose resolveSeam(Anchor anchor, int sampleSeed) {
        VehicleCubeOBB cube = anchor.cube;
        if (cube == null || cube.position == null || cube.rotation == null) return null;
        int sampleIndex = anchor.sampleIndex();
        // 调用本项目接缝采样，按采样点序号取它的方位槽、再叠加槽内抖动
        RVP_WreckCookoffGeometry.SeamSample sample =
                RVP_WreckCookoffGeometry.seamSample(cube.width, cube.depth, sampleSeed, sampleIndex,
                        SEAM_POINTS_PER_TURRET);
        Quaternionf rotation = new Quaternionf(cube.rotation);
        // 底缘高度沿用旧实现的 40% 下沿，保持接缝仍在炮塔底部
        Vec3 local = sample.local(-cube.height * 0.40);
        // 调用本项目接缝方向规则，得到世界水平面内外法线并叠加 0°～+30° 俯仰
        return new Pose(RVP_WreckCookoffGeometry.point(cube.position, rotation, local),
                RVP_WreckCookoffGeometry.seamDirection(rotation, sample.normal(), sampleSeed, sampleIndex));
    }
}
