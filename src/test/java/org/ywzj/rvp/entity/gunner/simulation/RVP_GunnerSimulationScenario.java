package org.ywzj.rvp.entity.gunner.simulation;

import org.ywzj.rvp.entity.gunner.ai.RVP_GunnerGroundPolicy.Settings;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 仿真专用场景，不属于载具包 schema；所有位置为格，速度为格/秒，时间为 20 Hz 刻。 */
public final class RVP_GunnerSimulationScenario {
    /** 场景格式版本，仅支持当前版本。 */ public int schemaVersion = 1;
    /** 输出文件稳定标识。 */ public String id;
    /** 回放显示名称。 */ public String name;
    /** 本场景的测试目的。 */ public String description = "";
    /** 随机种子；同场景同种子可重复得到同一记录。 */ public long seed = 721;
    /** 要积分的刻数；另保存第 0 刻。 */ public int ticks = 1200;
    /** 平地世界高度。 */ public double groundY = 64;
    /** 静态方盒障碍。 */ public List<Obstacle> obstacles = List.of();
    /** 受控载具与脚本目标。 */ public List<Actor> actors = List.of();

    /** 世界轴对齐实心盒；仿真按载具水平半径膨胀检查整段位移。 */
    public record Obstacle(
            /** 障碍名称。 */ String name,
            /** 盒子最小 X/Y/Z。 */ double[] min,
            /** 盒子最大 X/Y/Z。 */ double[] max) {}

    /** 单个模拟载具或目标；字段默认值是测试夹具参数，不替代游戏 Profile。 */
    public static final class Actor {
        /** 唯一标识。 */ public String id;
        /** 显示名称。 */ public String name;
        /** 阵营；同阵营不作为模拟敌人。 */ public String team = "blue";
        /** ground、launcher、fixed_wing 或 scripted。 */ public String mode = "ground";
        /** 出场位置 X/Y/Z。 */ public double[] position = {0, 64, 0};
        /** 初始 Minecraft 航向。 */ public float yaw;
        /** 初始前向速度，格/秒。 */ public double speed;
        /** 脚本目标匀速向量，格/秒。 */ public double[] velocity = {0, 0, 0};
        /** 出场刻，包含端点。 */ public int spawnTick;
        /** 离场刻，不包含端点。 */ public int despawnTick = Integer.MAX_VALUE;
        /** 模拟地面碰撞水平半径，格。 */ public double radius = 1.5;
        /** 模拟车高，格。 */ public double height = 3;
        /** 地面最高前进速度，格/秒。 */ public double maxSpeed = 8;
        /** 地面加减速幅度，格/秒²。 */ public double acceleration = 5;
        /** 地面每刻转向角，度。 */ public double turnDegPerTick = 2;
        /** 模拟感知半径，格；不代表游戏雷达或隐身识别。 */ public double searchRadius = 200;
        /** 模拟扫描间隔，刻。 */ public int scanInterval = 10;
        /** 是否要求模拟方盒视线畅通。 */ public boolean requireLineOfSight;
        /** 模拟装填开始刻。 */ public int reloadFrom = -1;
        /** 模拟装填结束刻。 */ public int reloadUntil = -1;
        /** 模拟射程，仅记录开火尝试，不生成弹体。 */ public double fireRange = 100;
        /** 模拟开火角窗口，度。 */ public double fireWindowDeg = 6;
        /** 模拟开火间隔，刻。 */ public int fireInterval = 20;
        /** 固定翼巡航最低离地高度，格。 */ public double altitudeMin = 150;
        /** 固定翼巡航最高离地高度，格。 */ public double altitudeMax = 350;
        /** 固定翼任务半径，格。 */ public double combatRadius = 550;
        /** 当前 ground_engagement_move 等行为参数的测试数值。 */
        public Settings ground = new Settings(12, 100, 140, 280, 55, 20, 1, 20, 300, 600, 120, 180, 40,
                0.2, 1.35, 0.35, 300, 12);
    }

    /** 在创建运行态前拒绝无意义或非有限参数，避免导出不可回放的数据。 */
    public void validate() {
        require(schemaVersion == 1, "仅支持 schemaVersion=1");
        require(id != null && id.matches("[a-z0-9_-]+"), "id 只能包含小写字母、数字、下划线和短横线");
        require(name != null && !name.isBlank(), "场景缺少 name");
        require(ticks > 0 && ticks <= 72000, "ticks 必须在 1..72000 内");
        require(Double.isFinite(groundY), "groundY 必须有限");
        require(actors != null && !actors.isEmpty() && actors.size() <= 32, "actors 必须包含 1..32 个对象");
        require(obstacles != null, "obstacles 不得为 null");
        Set<String> ids = new HashSet<>();
        for (Actor a : actors) {
            require(a != null && a.id != null && !a.id.isBlank() && ids.add(a.id), "actor.id 缺失或重复");
            require(a.name != null && a.team != null, "actor 缺少 name/team");
            require(Set.of("ground", "launcher", "fixed_wing", "scripted").contains(a.mode), "未知 mode: " + a.mode);
            vector(a.position); vector(a.velocity);
            positive(a.radius, a.height, a.maxSpeed, a.acceleration, a.turnDegPerTick, a.searchRadius, a.fireRange);
            require(Double.isFinite(a.yaw) && Double.isFinite(a.speed), "初始航向/速度必须有限");
            require(a.scanInterval > 0 && a.fireInterval > 0, "扫描/开火间隔必须大于零");
            require(a.fireWindowDeg > 0 && a.fireWindowDeg <= 180, "开火角必须在 (0,180] 内");
            require(a.spawnTick >= 0 && a.despawnTick > a.spawnTick, "出场/离场时间无效");
            require(a.reloadFrom == -1 && a.reloadUntil == -1 || a.reloadFrom >= 0 && a.reloadUntil > a.reloadFrom, "装填时间无效");
            require(Double.isFinite(a.altitudeMin) && Double.isFinite(a.altitudeMax)
                    && a.altitudeMin > 0 && a.altitudeMax >= a.altitudeMin, "巡航高度范围无效");
            require(Double.isFinite(a.combatRadius) && a.combatRadius >= 0, "作战半径无效");
            Settings g = a.ground;
            require(g != null, "缺少 ground 参数");
            require(Double.isFinite(g.stopDistance()) && g.stopDistance() >= 0, "停车距离无效");
            positive(g.stuckDistance());
            require(g.stuckCheckTick() > 0 && g.recoveryTick() > 0 && g.holdTick() >= 0
                    && g.evadeMin() >= 0 && g.evadeMax() >= g.evadeMin(), "战术计时无效");
            require(g.turnIntervalMin() >= 0 && g.turnIntervalMax() >= g.turnIntervalMin() && g.turnDuration() > 0, "巡逻计时无效");
            require(Float.isFinite(g.evadeYaw()) && Float.isFinite(g.turnAngleMin()) && Float.isFinite(g.turnAngleMax())
                    && g.turnAngleMax() >= g.turnAngleMin(), "战术转角无效");
        }
        for (Obstacle box : obstacles) {
            require(box != null && box.name() != null, "障碍缺少 name");
            vector(box.min()); vector(box.max());
            for (int i = 0; i < 3; i++) require(box.min()[i] < box.max()[i], "障碍 min 必须小于 max");
        }
    }

    /** 检查三维有限向量。 */
    private static void vector(double[] value) {
        require(value != null && value.length == 3, "位置/速度/盒边界必须是三个数");
        for (double n : value) require(Double.isFinite(n), "向量必须为有限数值");
    }
    /** 检查正的有限参数。 */
    private static void positive(double... values) {
        for (double value : values) require(Double.isFinite(value) && value > 0, "物理/感知参数必须为有限正数");
    }
    /** 给出直接可读的场景错误。 */
    private static void require(boolean ok, String message) { if (!ok) throw new IllegalArgumentException(message); }
}
