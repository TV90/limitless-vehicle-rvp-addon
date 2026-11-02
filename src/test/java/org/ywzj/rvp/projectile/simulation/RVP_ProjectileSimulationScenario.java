package org.ywzj.rvp.projectile.simulation;

/** 仿真初态，不覆盖武器 JSON 中的物理参数；距离用格、速度用格/秒、角度用度。 */
public final class RVP_ProjectileSimulationScenario {
    /** 当前场景格式，只接受 1。 */ public int schemaVersion = 1;
    /** 稳定输出文件名。 */ public String id;
    /** 页面名称。 */ public String name;
    /** 测试目的。 */ public String description = "RVP 武器配置数值仿真";
    /** 相对于 weapons 根目录的资源 ID。 */ public String weapon;
    /** 最多运行刻数，20 刻为一秒。 */ public int ticks = 600;
    /** 发射点世界 X/Y/Z，格。 */ public double[] position = {0, 164, 0};
    /** Minecraft 航向；0 朝 +Z，90 朝 -X。 */ public float yaw = 0;
    /** 正数抬头；导出时同工作台角度约定。 */ public float pitch = 0;
    /** 发射平台速度；仅武器 inherit_vehicle_velocity=true 时叠加，格/秒。 */ public double[] vehicleVelocity = {0, 0, 0};
    /** 可选 SpawnContext 世界初速度，格/秒；用于复现子弹药等外部出膛初态，仍交给真实 initFromWeapon 处理。 */ public double[] initialVelocity;
    /** 可选母弹释放瞬间姿态：航向、正数抬头的俯仰、滚转，度；滚转不改变父弹视轴。 */ public double[] parentRotation;
    /** 母弹释放瞬间世界速度，格/秒；仅作为生产风向解析的退化方向输入。 */ public double[] parentVelocity = {0, 0, 0};
    /** 发射平台姿态：航向、俯仰、滚转，度；用于冷发射本地轴转换。 */ public double[] carrierRotation = {0, 0, 0};
    /** 挂架冷发射时长，刻；0 表示没有冷发射。 */ public int coldLaunchTicks;
    /** 挂架局部冷发射速度，格/秒；与本体局部 X/Y/Z 轴约定相同。 */ public double[] coldLaunchVelocity = {0, -20, 0};
    /** 可选布撒初始分量，格/秒；使用真实部署状态初始化，半衰期仍读武器 JSON。 */ public double[] deploymentVelocity;
    /** 可选智能引信接管点，世界坐标；只用于测试接管后的真实运动，触发探测不在场景中伪造。 */ public double[] smartFuseTarget;
    /** 固定随机种子，控制真实实体出膛状态和 GPS 随机误差，默认 721。 */ public long seed = 721;
    /** 可选固定目标世界坐标；无目标时填 null。 */ public double[] target;
    /** 目标脚本初速度，格/秒；给生产拦截制导提供真实 Entity 运动观测。 */ public double[] targetVelocity = {0, 0, 0};
    /** 目标脚本加速度，格/秒²；默认不加速。 */ public double[] targetAcceleration = {0, 0, 0};
    /** 测试停止球半径，格；不是武器引信半径。 */ public double targetRadius = 2;
    /** 平面地面世界 Y，格。 */ public double groundY = 64;
    /** 可选水平水面世界 Y，格；null 表示干燥，须高于 groundY。 */ public Double waterY;
    /** true 把场景目标观测交给实体制导执行器；false 只计算实体自由运动。 */ public boolean guidance = true;

    /** 输入错误必须在积分前给出直接可读的提示。 */
    public void validate() {
        if (schemaVersion != 1) throw new IllegalArgumentException("场景仅接受 schemaVersion=1");
        if (id == null || !id.matches("[a-z0-9][a-z0-9_-]*") || name == null || name.isBlank()
                || description == null || weapon == null) throw new IllegalArgumentException("场景需要有效 id、name、description、weapon");
        if (ticks < 1 || ticks > 20000) throw new IllegalArgumentException("ticks 必须在 1..20000 内");
        vector(position, "position"); vector(vehicleVelocity, "vehicleVelocity");
        vector(carrierRotation, "carrierRotation"); vector(coldLaunchVelocity, "coldLaunchVelocity");
        vector(targetVelocity, "targetVelocity"); vector(targetAcceleration, "targetAcceleration");
        vector(parentVelocity, "parentVelocity");
        if (initialVelocity != null) vector(initialVelocity, "initialVelocity");
        if (parentRotation != null) vector(parentRotation, "parentRotation");
        if (target != null) vector(target, "target");
        if (deploymentVelocity != null) vector(deploymentVelocity, "deploymentVelocity");
        if (smartFuseTarget != null) vector(smartFuseTarget, "smartFuseTarget");
        if (coldLaunchTicks < 0 || coldLaunchTicks > 20000) throw new IllegalArgumentException("coldLaunchTicks 必须在 0..20000 内");
        if (waterY != null && (!Double.isFinite(waterY) || waterY <= groundY)) throw new IllegalArgumentException("waterY 必须是高于地面的有限高度");
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch) || Math.abs(pitch) > 90
                || !Double.isFinite(groundY) || !Double.isFinite(targetRadius) || targetRadius <= 0) {
            throw new IllegalArgumentException("角度、groundY、targetRadius 必须有效，俯仰限于 ±90 度");
        }
        if (position[1] <= groundY) throw new IllegalArgumentException("发射点必须高于地面");
    }

    /** 坐标和速度都要求有限三元组，不能靠数值容错掩盖坏输入。 */
    private static void vector(double[] value, String name) {
        if (value == null || value.length != 3 || java.util.Arrays.stream(value).anyMatch(v -> !Double.isFinite(v))) {
            throw new IllegalArgumentException(name + " 必须是三个有限数值");
        }
    }
}
