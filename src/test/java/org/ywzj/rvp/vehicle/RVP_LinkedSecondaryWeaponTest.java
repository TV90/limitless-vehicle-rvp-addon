package org.ywzj.rvp.vehicle;

import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.LivingEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.util.RVP_WeaponResolveHelper;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.custom.part.data.WeaponUnitPojo;
import org.ywzj.vehicle.custom.serialize.GsonUtil;
import org.ywzj.vehicle.custom.sync.PartUnitSyncData;
import org.ywzj.vehicle.custom.weapon.data.BaseVehicleWeaponData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.TrackedVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.AimContext;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleWeaponAgent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationBootstrap.*;

/** 使用本体的实际槽位读取路径，验证副槽和改装台读写同一个子站。 */
class RVP_LinkedSecondaryWeaponTest {
    @BeforeAll
    static void bootstrap() {
        // 注册基础物品，使本体武器构造器可在测试进程中运行。
        initialize();
    }

    @Test
    void configuredSecondaryTracksChildSelectionAndAmmoWithoutGroupedPrimary() {
        TrackedVehicle vehicle = environment(TrackedVehicle.class);
        write(AbstractVehicle.class, vehicle, "vehicleId", ResourceLocation.tryParse("test_pack:vehicle"));
        WeaponUnit root = unit(vehicle, "operator",
                "[{\"part_unit_id\":\"main_mount\"},{\"secondary\":true,\"part_unit_id\":\"mount\",\"full_salvo\":true}]");
        WeaponUnit mainMount = unit(vehicle, "main_mount", "[]");
        WeaponUnit mount = unit(vehicle, "mount", "[]");

        // 使用任意命名空间和武器站名称，通过真实装配器创建副槽。
        assertTrue(RVP_GroupedWeaponSlotAssembler.shouldHandle(root));
        RVP_GroupedWeaponSlotAssembler.assemble(root, Map.of("operator", root, "main_mount", mainMount, "mount", mount), vehicle);
        assertEquals(VehicleWeaponAgent.class, root.weapons.get(0).getClass());
        AbstractVehicleWeapon<?> secondary = root.getCurrentSecondaryWeapon().orElseThrow();
        assertInstanceOf(RVP_LinkedSecondaryWeapon.class, secondary);
        assertSame(secondary, root.indexedWeapons.get(1));
        assertSame(secondary, root.fullSalvoWeapons.get(0));
        assertFalse(secondary.doClientShoot());
        assertEquals(0, secondary.getRemainAmmo());

        ProbeWeapon first = new ProbeWeapon(vehicle, mount, 0, "first", 4);
        ProbeWeapon second = new ProbeWeapon(vehicle, mount, 1, "second", 9);
        mount.weapons.addAll(List.of(first, second));
        for (int i = 0; i < mount.weapons.size(); i++) {
            // 仅设置改装台同步的子站索引，避开本体切换时需要游戏世界的锁定清理。
            write(WeaponUnit.class, mount, "currentWeaponIndex", i);
            ProbeWeapon selected = i == 0 ? first : second;
            assertSame(selected, RVP_WeaponResolveHelper.currentSecondary(root));
            assertSame(selected.getData(), secondary.getData());
            assertSame(selected.getFireSound(), secondary.getFireSound());
            assertEquals(selected.getRemainAmmo(), secondary.getRemainAmmo());
            assertTrue(secondary.doClientShoot());
            assertTrue(secondary.shoot(List.of(), null));
            secondary.onClientFire();
            assertEquals(1, selected.requests);
            assertEquals(1, selected.feedback);
            assertEquals(i == 0 ? 3 : 8, secondary.getRemainAmmo());
        }
        secondary.startReload();
        assertTrue(secondary.isReloading());
        assertFalse(first.isReloading());
        assertNull(secondary.getSerializeId());
        assertEquals("first", first.getSerializeId());
        assertEquals("second", second.getSerializeId());
    }

    @Test
    void directWeaponsAndBayControlledLinksRetainExistingAssembly() {
        TrackedVehicle vehicle = environment(TrackedVehicle.class);
        write(AbstractVehicle.class, vehicle, "vehicleId", ResourceLocation.tryParse("test_pack:separate_vehicle"));
        WeaponUnit direct = unit(vehicle, "operator",
                "[{\"secondary\":true,\"id\":\"test_pack:fixed_weapon\",\"part_unit_id\":\"mount\"}]");
        WeaponUnit bayControlled = unit(vehicle, "operator",
                "[{\"secondary\":true,\"part_unit_id\":\"mount\",\"weapon_bay_unit_id\":\"bay\"}]");
        // 固定副武器和带弹舱配置保留本体原有发射行为。
        assertFalse(RVP_GroupedWeaponSlotAssembler.shouldHandle(direct));
        assertFalse(RVP_GroupedWeaponSlotAssembler.shouldHandle(bayControlled));
    }

    private static WeaponUnit unit(AbstractVehicle vehicle, String id, String weapons) {
        // 使用本体的 JSON 类型，补齐测试需要的真实槽列表和同步数据容器。
        WeaponUnit unit = environment(WeaponUnit.class);
        var json = JsonParser.parseString("{\"id\":\"" + id + "\",\"weapons\":" + weapons + "}");
        write(PartUnit.class, unit, "id", id);
        write(PartUnit.class, unit, "vehicle", vehicle);
        write(PartUnit.class, unit, "data", new WeaponUnitData(GsonUtil.GSON.fromJson(json, WeaponUnitPojo.class)));
        for (String name : List.of("weapons", "secondaryWeapons", "independentWeapons", "fullSalvoWeapons", "indexedWeapons", "subWeaponUnits")) {
            write(WeaponUnit.class, unit, name, new ArrayList<>());
        }
        write(WeaponUnit.class, unit, "weaponBayUnits", new HashMap<>());
        write(PartUnit.class, unit, "syncData", new PartUnitSyncData(unit));
        return unit;
    }

    /** 用计数记录实际武器收到的发射、反馈和装填调用，无需连接游戏。 */
    private static final class ProbeWeapon extends AbstractVehicleWeapon<BaseVehicleWeaponData> {
        /** 此武器独立剩余弹药。 */
        private int ammo;
        /** 本武器入口接收的客户端开火请求。 */
        private int requests;
        /** 本武器收到的客户端开火反馈。 */
        private int feedback;
        /** 避免在测试中执行世界对象上的实体装填。 */
        private boolean reloading;
        /** 记录所选武器自己的开火声音事件。 */
        private final SoundEvent fireSound;

        ProbeWeapon(AbstractVehicle vehicle, WeaponUnit unit, int index, String saveId, int ammo) {
            super(vehicle, unit, index, new BaseVehicleWeaponData(), saveId);
            this.ammo = ammo;
            // 用本体的声音事件工厂生成每个改件的音效标识。
            this.fireSound = SoundEvent.createVariableRangeEvent(ResourceLocation.tryParse("test_pack:" + saveId));
        }
        @Override public int getRemainAmmo() { return ammo; }
        @Override public boolean hasAmmo() { return ammo > 0; }
        @Override public boolean doClientShoot() { requests++; return hasAmmo(); }
        @Override public boolean shoot(List<AimContext> aimContexts, LivingEntity operator) { ammo--; return true; }
        @Override public void onClientFire() { feedback++; }
        @Override public SoundEvent getFireSound() { return fireSound; }
        @Override public boolean isReloading() { return reloading; }
        @Override public void startReload() { reloading = true; }
    }
}
