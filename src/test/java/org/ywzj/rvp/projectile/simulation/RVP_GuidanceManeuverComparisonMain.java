package org.ywzj.rvp.projectile.simulation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 两个隔离进程分别执行原 PIP 与当前 PPN，共用不可变场景清单和权威武器内容指纹。 */
public final class RVP_GuidanceManeuverComparisonMain {
    /** 固定初态的默认随机种子，保证镜像和重复运行可追踪。 */
    private static final long SEED = 721;

    /** 工具类不允许实例化。 */
    private RVP_GuidanceManeuverComparisonMain() {}

    /** 六类目标运动，不包含武器实现或任何机动能力覆盖。 */
    enum Category {
        /** 匀速横穿，只有一份条件，不重复计入不相关的机动时机。 */ CROSSING,
        /** 水平航向保持、速率连续增加。 */ CROSSING_ACCELERATING,
        /** 水平航向保持、速率连续降低，最低速度不反向。 */ CROSSING_DECELERATING,
        /** 速率保持、水平航向连续变化。 */ TURNING,
        /** 航向与速率同时连续变化，速率增加。 */ TURNING_ACCELERATING,
        /** 航向与速率同时连续变化，速率降低。 */ TURNING_DECELERATING
    }

    /** 武器 ID 仅用于测试读取，不在实体或渲染类中区分弹种。 */
    record WeaponProfile(/** 权威武器 ID。 */ String id, /** 报告显示名。 */ String label,
                         /** 本武器的初始目标距离，格。 */ double[] distances) {}
    /** 目标运动档位，不是武器参数。 */
    record MotionProfile(/** 档位标识。 */ String id, /** 初始水平速度，格/秒。 */ double speed,
                         /** 加速末速，格/秒。 */ double acceleratedSpeed,
                         /** 减速末速，格/秒。 */ double deceleratedSpeed,
                         /** 变向角，度，镜像时反号。 */ double turnDeg,
                         /** 机动持续 Tick。 */ int durationTicks) {}
    /** 明确记录抽样范围，不在看到结果后修改目标条件。 */
    record Matrix(/** 格式 1。 */ int schemaVersion, /** 单场上限 Tick。 */ int ticks,
                  /** 相对原 PIP 匀速交会时间的机动起点。 */ double[] timingFractions,
                  /** 左右镜像，-1 或 1。 */ int[] mirrors,
                  /** 原参数武器。 */ WeaponProfile[] weapons,
                  /** 外部目标机动档位。 */ MotionProfile[] profiles) {}
    /** 保存完整初态和标签，PPN 只能读取 PIP 已生成的这一份清单。 */
    record CaseDefinition(/** 唯一标识。 */ String id, /** 运动类别。 */ Category category,
                          /** 武器 ID。 */ String weapon, /** 原文件内容指纹。 */ String weaponSHA256,
                          /** 是否原配高抛。 */ boolean loft, /** 目标档位。 */ String profile,
                          /** 左右镜像。 */ int mirror, /** 匀速基线交会参考时刻，Tick。 */ int referenceTick,
                          /** 机动起点，Tick；匀速为 -1。 */ int maneuverStart,
                          /** 机动持续 Tick，匀速为 0。 */ int maneuverDuration,
                          /** 完整场景，不覆盖武器参数。 */ RVP_ProjectileSimulationScenario scene) {}
    /** 清单先写完后才允许第二算法读取，摘要防止混用旧结果。 */
    record Plan(/** 格式 1。 */ int schemaVersion, /** 矩阵配置指纹。 */ String matrixSHA256,
                /** 完整场景集。 */ List<CaseDefinition> cases) {}
    /** 逐场景结果包含输入摘要，不能只凭显示名拼接对照。 */
    record CaseResult(/** 场景标识。 */ String id, /** 实际输入场景指纹。 */ String sceneSHA256,
                      /** 实体运行结果。 */ RVP_GuidanceEncounterSimulation.Score score) {}
    /** 算法、输入和完整结果一同归档。 */
    record Results(/** 实际加载的制导类。 */ RVP_GuidanceSimulationIdentity.Identity runtime,
                   /** 固定场景清单指纹。 */ String planSHA256,
                   /** 原始武器 JSON 快照。 */ Map<String, Object> weapons,
                   /** 每一场的完整测量结果。 */ List<CaseResult> cases) {}

    /** 参数为算法名、矩阵文件、输出根目录、权威 weapons 根目录。 */
    public static void main(String[] args) throws IOException {
        if (args.length != 4) throw new IllegalArgumentException("需要：PIP/PPN 矩阵文件 输出根目录 权威武器目录");
        // 调用实际 class 指纹校验，禁止同一个算法被两个标签重复运行。
        var identity = RVP_GuidanceSimulationIdentity.current();
        if (!identity.mode().equals(args[0])) throw new IllegalStateException("算法标签与进程类路径不一致");
        Path matrixFile = Path.of(args[1]), output = Path.of(args[2]), weaponsRoot = Path.of(args[3]);
        String matrixHash = RVP_GuidanceSimulationIdentity.sha256(Files.readAllBytes(matrixFile));
        Matrix matrix = RVP_ProjectileSimulationMain.JSON.fromJson(Files.readString(matrixFile), Matrix.class);
        validate(matrix);
        var weapons = new LinkedHashMap<String, RVP_ProjectileSimulationWeapon>();
        for (WeaponProfile profile : matrix.weapons()) {
            // 调用同一权威解析器，不修改或复制出一套物理参数。
            weapons.put(profile.id(), RVP_ProjectileSimulationWeapon.read(weaponsRoot, profile.id()));
        }
        Files.createDirectories(output);
        Path planFile = output.resolve("plan.json");
        var measured = new LinkedHashMap<String, CaseResult>();
        Plan plan;
        if (identity.mode().equals("PIP")) {
            // 保存矩阵原文，报告可核对目标档位、距离与标签，不依赖未来可变的测试配置。
            Files.copy(matrixFile, output.resolve("matrix.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            var cases = generate(matrix, weapons, measured);
            plan = new Plan(1, matrixHash, cases);
            Files.writeString(planFile, RVP_ProjectileSimulationMain.JSON.toJson(plan), StandardCharsets.UTF_8);
            Path scenarios = output.resolve("scenarios"); Files.createDirectories(scenarios);
            for (CaseDefinition sample : cases) Files.writeString(scenarios.resolve(sample.id() + ".json"),
                    RVP_ProjectileSimulationMain.JSON.toJson(sample.scene()), StandardCharsets.UTF_8);
        } else {
            plan = RVP_ProjectileSimulationMain.JSON.fromJson(Files.readString(planFile), Plan.class);
            if (plan.schemaVersion() != 1 || !matrixHash.equals(plan.matrixSHA256())) {
                throw new IllegalStateException("PIP 清单与本次矩阵配置不同，拒绝对照");
            }
        }
        for (CaseDefinition sample : plan.cases()) {
            var weapon = weapons.get(sample.weapon());
            if (weapon == null || !weapon.sha256().equals(sample.weaponSHA256())) {
                throw new IllegalStateException("两进程间武器文件发生变化：" + sample.weapon());
            }
            sample.scene().validate();
            if (!measured.containsKey(sample.id())) measured.put(sample.id(), measure(sample, weapon));
            if (measured.size() % 100 == 0) System.out.printf("[GUIDANCE-MATRIX] %s %d/%d%n",
                    identity.mode(), measured.size(), plan.cases().size());
        }
        var snapshots = new LinkedHashMap<String, Object>();
        weapons.forEach((id, weapon) -> snapshots.put(id, Map.of("sha256", weapon.sha256(), "json", weapon.source())));
        String planHash = RVP_GuidanceSimulationIdentity.sha256(Files.readAllBytes(planFile));
        var results = new Results(identity, planHash, snapshots,
                plan.cases().stream().map(sample -> measured.get(sample.id())).toList());
        Files.writeString(output.resolve(identity.mode().toLowerCase(Locale.ROOT) + ".json"),
                RVP_ProjectileSimulationMain.JSON.toJson(results), StandardCharsets.UTF_8);
        System.out.printf("[GUIDANCE-MATRIX] %s completed=%d runtime=%s%n",
                identity.mode(), results.cases().size(), identity.runtimeSHA256());
    }

    /** 原 PIP 的同条件匀速场景同时用作真正的对照和机动时间标尺，不重复计数。 */
    private static List<CaseDefinition> generate(Matrix matrix,
            Map<String, RVP_ProjectileSimulationWeapon> weapons, Map<String, CaseResult> measured) {
        var result = new ArrayList<CaseDefinition>();
        for (WeaponProfile wp : matrix.weapons()) for (double distance : wp.distances())
            for (MotionProfile motion : matrix.profiles()) for (int mirror : matrix.mirrors()) {
                var weapon = weapons.get(wp.id());
                var straight = definition(matrix, weapon, distance, motion, mirror, Category.CROSSING, 0, -1);
                var calibration = measure(straight, weapon);
                result.add(straight); measured.put(straight.id(), calibration);
                int reference = Math.max(20, (int) Math.ceil(calibration.score().closestTimeTicks()));
                if (calibration.score().firstMissBlocks() == null) {
                    // 无完整匀速交会时使用明确的名义标尺，并在清单中保留该匀速截断结果。
                    double maxSpeed = Math.max(weapon.data().getProjectileData().getMaxSpeed(), 0.1);
                    reference = Math.min(matrix.ticks() - motion.durationTicks() - 1,
                            Math.max(40, (int) Math.ceil(distance / maxSpeed)));
                }
                for (Category category : Category.values()) {
                    if (category == Category.CROSSING) continue;
                    for (double fraction : matrix.timingFractions()) {
                        int start = Math.max(1, (int) Math.floor(reference * fraction));
                        result.add(definition(matrix, weapon, distance, motion, mirror, category, reference, start));
                    }
                }
            }
        return result;
    }

    /** 场景只指定平台初态、目标程序和测试时长；所有武器能力原样读取。 */
    private static CaseDefinition definition(Matrix matrix, RVP_ProjectileSimulationWeapon weapon, double distance,
            MotionProfile motion, int mirror, Category category, int reference, int start) {
        var scene = new RVP_ProjectileSimulationScenario();
        String id = weapon.id().substring(4) + "_r" + (int) distance + "_" + motion.id() + "_"
                + category.name().toLowerCase(Locale.ROOT) + "_t" + Math.max(0, start) + (mirror < 0 ? "_left" : "_right");
        scene.id = id; scene.name = id; scene.weapon = weapon.id(); scene.ticks = matrix.ticks();
        scene.description = "原参数 PIP/PPN 六类连续机动对照；目标观测相同，不代表真实引信命中";
        scene.seed = SEED; scene.vehicleVelocity = new double[]{0, 0, 80};
        scene.target = new double[]{0, 164, distance}; scene.targetVelocity = new double[]{mirror * motion.speed(), 0, 0};
        scene.targetRadius = 0.01;
        int duration = category == Category.CROSSING ? 0 : motion.durationTicks();
        if (duration > 0) {
            double speed = switch (category) {
                case CROSSING_ACCELERATING, TURNING_ACCELERATING -> motion.acceleratedSpeed();
                case CROSSING_DECELERATING, TURNING_DECELERATING -> motion.deceleratedSpeed();
                default -> motion.speed();
            };
            boolean turn = category == Category.TURNING || category == Category.TURNING_ACCELERATING
                    || category == Category.TURNING_DECELERATING;
            scene.targetManeuvers = new RVP_ProjectileSimulationScenario.TargetManeuver[]{
                    new RVP_ProjectileSimulationScenario.TargetManeuver(start, motion.speed(), 0),
                    new RVP_ProjectileSimulationScenario.TargetManeuver(duration, speed, turn ? mirror * motion.turnDeg() : 0)};
        }
        scene.validate();
        return new CaseDefinition(id, category, weapon.id(), weapon.sha256(),
                weapon.data().getGuidanceData().getLoftHeightAboveTarget() > 0,
                motion.id(), mirror, reference, start, duration, scene);
    }

    /** 只调用真实实体评分器；PIP 和 PPN 在独立类路径下执行同一方法。 */
    private static CaseResult measure(CaseDefinition sample, RVP_ProjectileSimulationWeapon weapon) {
        var score = RVP_GuidanceEncounterSimulation.run(sample.scene(), weapon, sample.maneuverStart(), sample.maneuverDuration());
        String sceneHash = RVP_GuidanceSimulationIdentity.sha256(
                RVP_ProjectileSimulationMain.JSON.toJson(sample.scene()).getBytes(StandardCharsets.UTF_8));
        return new CaseResult(sample.id(), sceneHash, score);
    }

    /** 明确拒绝缺失或重复的试验维度，不能悄悄减少场景或两边使用不同条件。 */
    private static void validate(Matrix matrix) {
        if (matrix == null || matrix.schemaVersion() != 1 || matrix.ticks() < 100
                || matrix.timingFractions() == null || matrix.timingFractions().length == 0
                || matrix.weapons() == null || matrix.weapons().length == 0
                || matrix.profiles() == null || matrix.profiles().length == 0
                || matrix.mirrors() == null || matrix.mirrors().length == 0) throw new IllegalArgumentException("机动矩阵配置不完整");
        for (double fraction : matrix.timingFractions()) if (!Double.isFinite(fraction) || fraction <= 0 || fraction >= 1)
            throw new IllegalArgumentException("机动时机比例必须位于 0～1 之间");
        for (int mirror : matrix.mirrors()) if (mirror != -1 && mirror != 1) throw new IllegalArgumentException("镜像只能为 -1 或 1");
        var ids = new java.util.HashSet<String>();
        for (WeaponProfile weapon : matrix.weapons()) {
            if (weapon.id() == null || !ids.add(weapon.id()) || weapon.distances() == null || weapon.distances().length == 0)
                throw new IllegalArgumentException("武器标识不能重复，且必须配置距离");
            for (double distance : weapon.distances()) if (!Double.isFinite(distance) || distance <= 0)
                throw new IllegalArgumentException("试验距离必须为有限正数");
        }
        ids.clear();
        for (MotionProfile motion : matrix.profiles()) {
            if (motion.id() == null || !motion.id().matches("[a-z0-9_]+") || !ids.add(motion.id())
                    || !Double.isFinite(motion.speed()) || motion.speed() <= 0
                    || !Double.isFinite(motion.acceleratedSpeed()) || motion.acceleratedSpeed() <= motion.speed()
                    || !Double.isFinite(motion.deceleratedSpeed()) || motion.deceleratedSpeed() < 0
                    || motion.deceleratedSpeed() >= motion.speed() || !Double.isFinite(motion.turnDeg())
                    || Math.abs(motion.turnDeg()) > 360 || motion.durationTicks() < 1
                    || motion.durationTicks() >= matrix.ticks() / 2) throw new IllegalArgumentException("目标档位配置无效");
        }
    }
}
