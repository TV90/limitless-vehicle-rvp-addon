package org.ywzj.rvp.compat;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;

/**
 * SBW（SuperbWarfare）联动兼容层。
 *
 * <p>与本体 {@code SuperbWarfareCompat} 一致：仅编译期引用 SBW（build.gradle 以
 * {@code compileOnly fg.deobf} 接入），运行时由用户另行安装 SBW，不打包、不强制依赖。</p>
 *
 * <p><b>不崩溃保证（关键）</b>：本类<b>不直接引用任何 SBW 类型</b>，只持有 SBW 导弹类名的
 * 字符串，并通过反射（{@code Class.forName} + {@code Method.invoke}）访问 SBW API；
 * 对弹体的通用操作（读取/改写速度、朝向）全部使用 MC 原生的 {@code Entity} 方法。
 * 因此本类字节码常量池中不存在 SBW 类符号，无论 JVM/Forge 采用 eager 还是 lazy 的常量池
 * 解析，卸载 SBW 时都不会触发 SBW 类加载 → RVP 照常运行、绝不崩溃。</p>
 */
public final class RVP_SuperbWarfareCompat {

    private static final String MOD_ID = "superbwarfare";
    /** SBW 导弹基类全限定名（仅作字符串，不触发类加载）。 */
    private static final String MISSILE_CLASS_NAME = "com.atsuishio.superbwarfare.entity.projectile.MissileProjectile";

    private static boolean IS_LOADED = false;
    /** 惰性解析的 SBW 导弹类（仅 SBW 加载时非空），避免在类初始化期触碰 SBW。 */
    private static Class<?> missileClass;

    /** 每 tick 水平偏转角度（度），对齐 RVP jammingHeadingRate。 */
    private static final double DEFLECT_DEG_PER_TICK = 2.5;
    /** 每 tick 向下偏转分量（格/tick），使被甩离的导弹下坠。 */
    private static final double DEFLECT_DOWN_PER_TICK = 0.15;

    /** 在 RVP 公共初始化阶段调用，探测 SBW 是否加载。 */
    public static void init() {
        IS_LOADED = ModList.get().isLoaded(MOD_ID);
    }

    public static boolean isLoaded() {
        return IS_LOADED;
    }

    /** 惰性解析 SBW 导弹类；失败（SBW 未安装）返回 null，绝不在类初始化期加载。 */
    private static Class<?> missileClass() {
        if (missileClass == null && IS_LOADED) {
            try {
                // initialize=false：仅链接不初始化，且本方法只在 SBW 已加载（isLoaded）后才走
                missileClass = Class.forName(MISSILE_CLASS_NAME, false, RVP_SuperbWarfareCompat.class.getClassLoader());
            } catch (Throwable t) {
                missileClass = null;
            }
        }
        return missileClass;
    }

    /** 该实体是否为 SBW 制导导弹（继承 {@code MissileProjectile}）。 */
    public static boolean isSbwMissile(Entity entity) {
        Class<?> c = missileClass();
        return c != null && entity != null && c.isInstance(entity);
    }

    /** 获取 SBW 导弹制导类型：0=跟踪实体（红外热寻的），1=跟踪坐标点（激光/指令制导）。 */
    public static int getGuideType(Entity entity) {
        Class<?> c = missileClass();
        if (c == null || entity == null || !c.isInstance(entity)) {
            return -1;
        }
        try {
            return (Integer) c.getMethod("getGuideType").invoke(entity);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 获取 SBW 导弹当前锁定的目标 UUID（字符串，"none" 表示无目标）。 */
    public static String getTargetUuid(Entity entity) {
        Class<?> c = missileClass();
        if (c == null || entity == null || !c.isInstance(entity)) {
            return "none";
        }
        try {
            return (String) c.getMethod("getTargetUUID").invoke(entity);
        } catch (Throwable t) {
            return "none";
        }
    }

    /** 改写 SBW 导弹的锁定目标 UUID（指向诱饵实体）。 */
    public static void setTargetUuid(Entity entity, String uuid) {
        Class<?> c = missileClass();
        if (c == null || entity == null) {
            return;
        }
        try {
            c.getMethod("setTargetUUID", String.class).invoke(entity, uuid);
        } catch (Throwable ignored) {
        }
    }

    /** 标记 SBW 导弹被干扰（使其偏航 / 丢失锁定）。 */
    public static void setDistracted(Entity entity, boolean distracted) {
        Class<?> c = missileClass();
        if (c == null || entity == null) {
            return;
        }
        try {
            c.getMethod("setDistracted", Boolean.TYPE).invoke(entity, distracted);
        } catch (Throwable ignored) {
        }
    }

    /**
     * DIRCM 照射 SBW 红外导弹：占用 DIRCM 火力通道期间每 tick 调用，使其航线偏转（非直线飞离）。
     *
     * <p><b>不用 SBW 的 {@code turn()}（它只转朝向 + 等比减速，不改速度方向）</b>，而是：
     * 清空原锁定（反射，禁用 SBW 自身制导修正）后，<b>直接改写 {@code deltaMovement}</b>——
     * 保持速度大小、水平朝远离载具一侧每 tick 转 2.5° + 叠加上坠，再用 Entity 方法把弹头朝向
     * 设成新速度方向。因 SBW 导弹位置由速度驱动，航线即被真正推离（弹头跟随、不乱转、不减速）。</p>
     */
    public static void deflectSbwMissile(Entity entity, Entity vehicle) {
        if (entity == null) {
            return;
        }
        // 清空原锁定：SBW 导弹的速度重建/制导修正依赖目标，清空后不再把弹体拉回载具（反射，安全）
        setTargetUuid(entity, "none");

        Vec3 vel = entity.getDeltaMovement();
        double hSpeed = Math.hypot(vel.x, vel.z);
        if (hSpeed <= 1.0E-4) {
            return;
        }
        Vec3 hDir = new Vec3(vel.x, 0.0, vel.z).normalize();
        // 水平垂直方向（hDir 逆时针 90°）
        Vec3 perp = new Vec3(-hDir.z, 0.0, hDir.x);
        // 选指向「远离载具」一侧：用导弹->远离载具方向做符号判定
        Vec3 away = entity.position().subtract(vehicle.position());
        Vec3 hAway = new Vec3(away.x, 0.0, away.z);
        if (hAway.lengthSqr() > 1.0E-6 && perp.dot(hAway) < 0.0) {
            perp = perp.scale(-1.0);
        }
        // 每 tick 把水平速度方向朝该侧旋转 DEFLECT_DEG_PER_TICK 度（保持速度大小，避免减速）
        double theta = Math.toRadians(DEFLECT_DEG_PER_TICK);
        Vec3 hNew = hDir.scale(Math.cos(theta)).add(perp.scale(Math.sin(theta)));
        hNew = hNew.normalize().scale(hSpeed);
        Vec3 newVel = new Vec3(hNew.x, vel.y - DEFLECT_DOWN_PER_TICK, hNew.z);
        // 以下均为 MC Entity 原生方法，不触碰 SBW 类
        entity.setDeltaMovement(newVel);
        setHeadingFromVector(entity, newVel);
    }

    /** 把弹体朝向（yRot/xRot）设为速度方向，yaw/pitch 对齐 SBW ITrackableProjectile.turn 的约定。 */
    private static void setHeadingFromVector(Entity missile, Vec3 vel) {
        double lenXZ = Math.hypot(vel.x, vel.z);
        if (lenXZ < 1.0E-6) {
            return;
        }
        float yaw = (float) (-Math.atan2(vel.x, vel.z) * (180.0 / Math.PI));
        float pitch = (float) (-Math.atan2(vel.y, lenXZ) * (180.0 / Math.PI));
        missile.setYRot(yaw);
        missile.setXRot(pitch);
        missile.yRotO = yaw;
        missile.xRotO = pitch;
    }
}