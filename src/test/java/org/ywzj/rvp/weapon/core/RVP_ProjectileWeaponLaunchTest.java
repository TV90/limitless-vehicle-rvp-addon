package org.ywzj.rvp.weapon.core;

import com.google.gson.Gson;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.fml.loading.FMLPaths;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_FireData;
import org.ywzj.rvp.weapon.data.RVP_GuidanceData;
import org.ywzj.rvp.weapon.data.RVP_GuidanceDataGPS;
import org.ywzj.rvp.weapon.data.RVP_GuidanceDataIR;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.AimContext;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 执行真实发射调度与本体扣弹逻辑，只替换需要游戏世界的弹体生成/后坐力边界。 */
class RVP_ProjectileWeaponLaunchTest {
    @BeforeAll
    static void initializeGameDirectory() {
        // 为真实诊断类提供 Forge 游戏目录；监测保持关闭，测试不创建游戏世界或日志。
        FMLPaths.loadAbsolutePaths(Path.of("build", "launch-test-game"));
    }

    @Test
    void unarmedInfraredBombIsRejectedBeforeAmmoOrChargeConsumption() throws Exception {
        TestWeapon weapon = weapon();
        weapon.useRealGate = true;
        // 使用实际 IR 数据与空服务端授权表复现 GB-3 的无锁投弹，不以武器 ID 特判。
        set(RVP_WeaponData.class, weapon.getData(), "guidanceData",
                new Gson().fromJson("{\"guidance_type\":\"IR\"}", RVP_GuidanceDataIR.class));
        weapon.getData().setWeaponKind(RVP_EnumWeaponKind.BOMB);

        assertTrue(weapon.requiresEntityLock(weapon.getData()), "制导炸弹必须进入客户端锁定检查");
        assertFalse(dispatch(weapon, List.of(new AimContext())));
        assertEquals(6, weapon.getRemainAmmo());
        assertEquals(20, weapon.getChargeTickValue());
        assertEquals(0, weapon.spawnCalls);
        assertEquals(0, weapon.chargeConsumes);
    }

    @Test
    void gpsWeaponWithTerminalRadarSeekerStillLaunchesWithoutEntityLock() throws Exception {
        // 复现 YJ-20 的数据形状：GPS 主段 + 末端 ARH。末端阶段令 isRadarHoming() 为真，
        // 但发射段是 GPS 装订点，发射前不需要实体锁；服务端与客户端必须同口径。
        Gson gson = new Gson();
        RVP_WeaponData gpsData = allocate(RVP_WeaponData.class);
        set(RVP_WeaponData.class, gpsData, "guidanceData",
                gson.fromJson("{\"guidance_type\":\"GPS\",\"terminal_guidance\":{\"guidance_type\":\"ARH\"}}",
                        RVP_GuidanceDataGPS.class));
        set(RVP_WeaponData.class, gpsData, "fireData",
                gson.fromJson("{\"require_lock\":true}", RVP_FireData.class));
        assertTrue(gpsData.isGpsMissile());
        assertTrue(gpsData.isRadarHoming(), "末端 ARH 会让 GPS 弹也被判为雷达制导");
        assertFalse(gpsData.launchPhaseRequiresSeekerLock(), "只按发射段制导判定，末端 ARH 不改变发射门");
        assertFalse(RVP_ProjectileWeapon.requiresServerSeekerLock(gpsData, null,
                        WeaponUnitData.FireControlSensorType.RF),
                "GPS 发射段使用装订点，不依赖实体锁，服务端必须放行");

        // 对比用例：主段 ARH 且 require_lock 的弹无锁仍拒射，本次修复不能放宽真雷达制导。
        RVP_WeaponData radarData = allocate(RVP_WeaponData.class);
        set(RVP_WeaponData.class, radarData, "guidanceData",
                gson.fromJson("{\"guidance_type\":\"ARH\"}", RVP_GuidanceData.class));
        set(RVP_WeaponData.class, radarData, "fireData",
                gson.fromJson("{\"require_lock\":true}", RVP_FireData.class));
        assertTrue(radarData.launchPhaseRequiresSeekerLock());
        assertTrue(RVP_ProjectileWeapon.requiresServerSeekerLock(radarData, null,
                WeaponUnitData.FireControlSensorType.RF));
        assertFalse(RVP_ProjectileWeapon.requiresServerSeekerLock(radarData, null,
                WeaponUnitData.FireControlSensorType.EO), "EO 直瞄豁免保持不变");

        // 反辐射（ARM）走预选通道，不属于实体锁定门；激光/指令发射段同样不要求实体锁。
        RVP_WeaponData armData = allocate(RVP_WeaponData.class);
        set(RVP_WeaponData.class, armData, "guidanceData",
                gson.fromJson("{\"guidance_type\":\"ARM\"}", RVP_GuidanceData.class));
        set(RVP_WeaponData.class, armData, "fireData",
                gson.fromJson("{\"require_lock\":true}", RVP_FireData.class));
        assertFalse(armData.launchPhaseRequiresSeekerLock());
    }

    @Test
    void rejectedFollowupPreservesAmmoAfterSuccessfulFirstShot() throws Exception {
        TestWeapon weapon = weapon();
        List<AimContext> aim = List.of(new AimContext());
        assertTrue(dispatch(weapon, aim));
        weapon.reject = true;
        assertFalse(dispatch(weapon, aim));
        assertEquals(5, weapon.getRemainAmmo(), "下一发脱锁后不能继续扣弹");
        assertEquals(1, weapon.spawnCalls);
        assertEquals(1, weapon.recoilCalls);
        assertEquals(1, weapon.chargeConsumes);
    }

    @Test
    void failedWorldInsertionRefundsAmmoAndCharge() throws Exception {
        TestWeapon weapon = weapon();
        weapon.failSpawn = true;
        assertFalse(dispatch(weapon, List.of(new AimContext())));
        assertEquals(6, weapon.getRemainAmmo());
        assertEquals(20, weapon.getChargeTickValue());
        assertEquals(0, weapon.recoilCalls);
    }

    @Test
    void partialSalvoOnlyConsumesSuccessfulLaunches() throws Exception {
        TestWeapon weapon = weapon();
        weapon.failFirstSpawn = true;
        assertTrue(dispatch(weapon, List.of(new AimContext(), new AimContext())));
        assertEquals(5, weapon.getRemainAmmo());
        assertEquals(2, weapon.spawnCalls);
        assertEquals(1, weapon.recoilCalls);
    }

    @Test
    void insufficientAmmoDoesNotMutateCallerAimList() throws Exception {
        TestWeapon weapon = weapon();
        weapon.setRemainAmmo(1);
        List<AimContext> aims = List.of(new AimContext(), new AimContext());
        assertTrue(dispatch(weapon, aims));
        assertEquals(2, aims.size());
        assertEquals(0, weapon.getRemainAmmo());
        assertEquals(1, weapon.spawnCalls);
    }

    @Test
    void emptyVolleyAndEmptyMagazineCannotReportSuccess() throws Exception {
        TestWeapon weapon = weapon();
        assertFalse(dispatch(weapon, List.of()));
        weapon.setRemainAmmo(0);
        assertFalse(dispatch(weapon, List.of(new AimContext())));
        assertEquals(0, weapon.spawnCalls);
        assertEquals(0, weapon.chargeConsumes);
    }

    @Test
    void unguidedBombAndOptionalLockKeepTheirExistingExemptions() throws Exception {
        TestWeapon weapon = weapon();
        weapon.getData().setWeaponKind(RVP_EnumWeaponKind.BOMB);
        assertFalse(weapon.requiresEntityLock(weapon.getData()));
        set(RVP_WeaponData.class, weapon.getData(), "guidanceData",
                new Gson().fromJson("{\"guidance_type\":\"IR\"}", RVP_GuidanceDataIR.class));
        set(RVP_WeaponData.class, weapon.getData(), "fireData",
                new Gson().fromJson("{\"require_lock\":false}", RVP_FireData.class));
        assertFalse(weapon.requiresEntityLock(weapon.getData()));
    }

    /** 调用真实私有调度入口，绕开外部冷却/网络输入，直接验证扣弹与拒射的先后关系。 */
    private static boolean dispatch(TestWeapon weapon, List<AimContext> aims) throws Exception {
        Method method = RVP_ProjectileWeapon.class.getDeclaredMethod("dispatchShots", List.class, LivingEntity.class);
        method.setAccessible(true);
        return (boolean) method.invoke(weapon, aims, null);
    }

    /** 沿用项目现有测试的无注册表夹具；仅绕过构造器，不替换待验证的本体扣弹代码。 */
    private static TestWeapon weapon() throws Exception {
        TestWeapon weapon = allocate(TestWeapon.class);
        RVP_WeaponData data = allocate(RVP_WeaponData.class);
        set(AbstractVehicleWeapon.class, weapon, "data", data);
        set(AbstractVehicleWeapon.class, weapon, "weaponUnit", allocate(WeaponUnit.class));
        weapon.setRemainAmmo(6);
        weapon.setChargeTick(20);
        return weapon;
    }

    /** 按声明类写入测试夹具字段，不触发 Forge 物品注册表初始化。 */
    private static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** 仅供测试分配无游戏世界的对象；生产代码始终走正常构造器。 */
    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
    }

    /** 世界边界替身，其余发射流程仍执行生产方法。 */
    private static final class TestWeapon extends RVP_ProjectileWeapon {
        /** 是否执行真正的 IR 发射前检查。 */
        boolean useRealGate;
        /** 模拟下一发锁定条件不满足。 */
        boolean reject;
        /** 模拟世界拒绝所有弹体。 */
        boolean failSpawn;
        /** 模拟齐射中第一个出弹点失败。 */
        boolean failFirstSpawn;
        /** 实际尝试生成次数。 */
        int spawnCalls;
        /** 实际后坐力次数。 */
        int recoilCalls;
        /** 实际蓄力消费次数。 */
        int chargeConsumes;

        private TestWeapon() { super(null, null, 0, null, "", null); }

        @Override
        protected ApprovedShot prepareShot(List<AimContext> aims, LivingEntity shooter) {
            // GB-3 复现使用生产检查，其他用例只替换目标授权这一外部状态。
            return useRealGate ? super.prepareShot(aims, shooter)
                    : reject ? null : new ApprovedShot(null, null, null);
        }

        @Override
        protected boolean spawnShot(RVP_WeaponData data, LivingEntity shooter, AimContext aim,
                                    ApprovedShot approved, float chargeScale) {
            spawnCalls++;
            return !failSpawn && !(failFirstSpawn && spawnCalls == 1);
        }

        @Override
        protected void applyShotRecoil(RVP_WeaponData data) { recoilCalls++; }

        @Override
        protected float consumeChargeScale() {
            chargeConsumes++;
            // 模拟一次真实蓄力消费，验证全部失败时生产调度会恢复它。
            setChargeTick(0);
            return 2f;
        }
    }

}
