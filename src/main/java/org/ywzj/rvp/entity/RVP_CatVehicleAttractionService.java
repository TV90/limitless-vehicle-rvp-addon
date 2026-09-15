package org.ywzj.rvp.entity;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;

import java.util.List;

/**
 * [RVP] 原版猫被地面载具吸引的行为彩蛋：猫在 100 格内探测到地面载具（含停放的）时，
 * 会主动跑向车辆——车辆行驶中沿车头前方追跑，车辆停着则凑到车头正前方站立。
 *
 * <p>实现为无状态反向查询：载具数量远少于生物，故每台地面载具每 {@code #SCAN_INTERVAL_TICK}
 * 查一次周围 {@code #RANGE} 格内的 {@link Cat}，直接刷新它们的导航目标
 * （{@code getNavigation().moveTo}）——不注入 AI Goal、不保存任何状态，猫跑出范围或
 * 车辆消失后原版 AI（游荡/怕人）自动接管，零清理逻辑。仅服务端运行。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_CatVehicleAttractionService {

    /** 扫描节流（tick）：载具查猫为 AABB 查询，10 tick 一次足够流畅。 */
    private static final int SCAN_INTERVAL_TICK = 10;
    /** 猫感知地面载具的距离（格）。 */
    private static final double RANGE = 100.0D;
    /** 车辆"行驶中"的最低水平速度（km/h）——停着的车也吸引猫，只是猫改为站在车头前。 */
    private static final double SPEED_KPH_MIN = 3.0D;
    /** 猫跑向目标的导航速度倍率。 */
    private static final double CAT_SPEED = 1.25D;
    /** 追车时猫与车头的最小前方距离（格）。 */
    private static final double AHEAD_MIN = 10.0D;
    /** 追车时猫与车头的最大前方距离（格）。 */
    private static final double AHEAD_MAX = 25.0D;
    /** 静止车：猫站立点距车头包围盒的间隙（格）。 */
    private static final double PARKED_FRONT_GAP = 1.2D;

    private RVP_CatVehicleAttractionService() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || server.getTickCount() % SCAN_INTERVAL_TICK != 0) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getEntities().getAll()) {
                if (!(entity instanceof AbstractVehicle vehicle) || isAirVehicle(vehicle)) {
                    continue;
                }
                List<Cat> cats = level.getEntitiesOfClass(Cat.class,
                        vehicle.getBoundingBox().inflate(RANGE),
                        cat -> cat.isAlive() && !cat.isPassenger() && !vehicle.hasPassenger(cat));
                if (cats.isEmpty()) {
                    continue;
                }
                Vec3 motion = vehicle.getDeltaMovement();
                double speedKph = Math.hypot(motion.x, motion.z) * 20.0D * 3.6D;
                for (Cat cat : cats) {
                    Vec3 target = speedKph >= SPEED_KPH_MIN
                            ? movingFrontPoint(vehicle, cat, motion)
                            : parkedFrontPoint(vehicle, cat);
                    cat.getNavigation().moveTo(target.x, target.y, target.z, CAT_SPEED);
                }
            }
        }
    }

    /**
     * 行驶中：猫跑向车头前方——目标点 = 车辆位置 + 行进方向 × lead，
     * lead = 猫在行进方向上的投影（车头到猫的距离）+ {@code AHEAD_MIN}，钳制到 {@code AHEAD_MAX}。
     */
    private static Vec3 movingFrontPoint(AbstractVehicle vehicle, Cat cat, Vec3 motion) {
        Vec3 dir = new Vec3(motion.x, 0.0D, motion.z);
        dir = dir.lengthSqr() < 1.0E-6 ? horizontalForward(vehicle) : dir.normalize();
        double ahead = cat.position().subtract(vehicle.position()).dot(dir);
        double lead = Mth.clamp(Math.max(ahead, 0.0D) + AHEAD_MIN, AHEAD_MIN, AHEAD_MAX);
        return new Vec3(vehicle.getX() + dir.x() * lead, cat.getY(), vehicle.getZ() + dir.z() * lead);
    }

    /**
     * 静止：猫站到车头正前方——目标点 = 车辆包围盒中心 + 车体朝向水平前向
     * × (包围盒沿前向半长 + {@code PARKED_FRONT_GAP})。
     */
    private static Vec3 parkedFrontPoint(AbstractVehicle vehicle, Cat cat) {
        Vec3 forward = horizontalForward(vehicle);
        AABB box = vehicle.getBoundingBox();
        double halfLength = Math.max(Math.abs(forward.x()) * box.getXsize() * 0.5D,
                Math.abs(forward.z()) * box.getZsize() * 0.5D);
        Vec3 center = box.getCenter();
        return new Vec3(center.x() + forward.x() * (halfLength + PARKED_FRONT_GAP),
                cat.getY(),
                center.z() + forward.z() * (halfLength + PARKED_FRONT_GAP));
    }

    /** 车体朝向的水平前向（实体 yRot：0 朝 +Z）。 */
    private static Vec3 horizontalForward(AbstractVehicle vehicle) {
        float yaw = vehicle.getYRot() * Mth.DEG_TO_RAD;
        return new Vec3(-Mth.sin(yaw), 0.0D, Mth.cos(yaw));
    }

    /** 固定翼/旋翼不属于"地面载具"。 */
    private static boolean isAirVehicle(AbstractVehicle vehicle) {
        return vehicle instanceof FixedWingVehicle || vehicle instanceof RotaryWingVehicle;
    }
}
