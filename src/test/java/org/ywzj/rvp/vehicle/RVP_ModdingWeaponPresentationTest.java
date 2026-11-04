package org.ywzj.rvp.vehicle;

import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.*;
import org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager;
import org.ywzj.rvp.weapon.core.RVP_ProjectileWeapon;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.entity.vehicle.*;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationBootstrap.*;

/** 使用本体真实Multi与RVP真实配置解析，覆盖宿主槽位和物理发射子站分离。 */
class RVP_ModdingWeaponPresentationTest {
    /** 暂存配置，测试结束完整恢复。 */ private Object previousConfigs,previousRaw;
    /** 测试载具。 */ private Carrier vehicle;
    /** 根操作站。 */ private Station root;
    /** 物理导弹发射子站。 */ private Station mount;
    /** 测试用配置标识。 */ private static final ResourceLocation ID=ResourceLocation.tryParse("rvp:hud_host_probe");
    @BeforeAll static void bootstrap() { initialize(); }
    @BeforeEach void prepare() {
        var mgr=RVP_VehicleExtendedConfigManager.INSTANCE;
        previousConfigs=read(mgr.getClass(),mgr,"configs"); previousRaw=read(mgr.getClass(),mgr,"rawVehicleJson");
        vehicle=environment(Carrier.class); root=station("turret"); mount=station("missile"); mount.root=root;
        configure(true);
    }
    @AfterEach void restore() {
        var mgr=RVP_VehicleExtendedConfigManager.INSTANCE;
        write(mgr.getClass(),mgr,"configs",previousConfigs); write(mgr.getClass(),mgr,"rawVehicleJson",previousRaw);
    }
    @Test void physicalChildMountUsesTheRootConfigurationSlotAndSelectedAmmo() {
        var group=group(mount); root.weapons.add(leaf(root)); root.weapons.add(group); group.setSelectedIndex(1);
        assertSame(group.getSelectedWeapon(),RVP_ModdingWeaponPresentation.collapse(root,group));
        assertSame(group.getSelectedWeapon(),RVP_ModdingWeaponPresentation.collapse(mount,group));
    }
    @Test void directRootMountStillCollapsesLikeExistingModdingOnlyAmmo() {
        var group=group(root); root.weapons.add(leaf(root)); root.weapons.add(group);
        assertSame(group.getSelectedWeapon(),RVP_ModdingWeaponPresentation.collapse(root,group));
    }
    @Test void ordinaryFKeyGroupsAndNestedGroupsKeepTheirLists() {
        var group=group(mount); root.weapons.add(leaf(root)); root.weapons.add(group);
        configure(false); assertSame(group,RVP_ModdingWeaponPresentation.collapse(root,group));
        configure(true);
        var nested=new VehicleMultiWeapons(vehicle,mount,1,List.of(group,leaf(mount)),"nested");
        root.weapons.set(1,nested);
        assertSame(nested,RVP_ModdingWeaponPresentation.collapse(root,nested));
    }
    /** 与CSSA5相同：配置写在turret槽1，part_unit_id指向missile。 */
    private void configure(boolean modding) {
        RVP_VehicleExtendedConfigManager.INSTANCE.applyFromJsonMap(Map.of(ID,JsonParser.parseString("""
            {"parts":[{"id":"turret","type":"weapon","weapons":[{"id":"rvp:gun"},
            {"ids":["rvp:a","rvp:b"],"part_unit_id":"missile","modding_only_multi":%s}]}]}
            """.formatted(modding))));
    }
    private Station station(String id) {
        Station s=environment(Station.class); s.id=id; s.vehicle=vehicle; s.root=s;
        for (String name : List.of("weapons","secondaryWeapons","independentWeapons")) write(WeaponUnit.class,s,name,new ArrayList<>());
        return s;
    }
    private AbstractVehicleWeapon<?> leaf(Station s) { return new RVP_ProjectileWeapon(vehicle,s,1,new RVP_WeaponData(),"leaf",null); }
    private VehicleMultiWeapons group(Station s) { return new VehicleMultiWeapons(vehicle,s,1,List.of(leaf(s),leaf(s)),"group"); }
    private static final class Carrier extends WheeledVehicle {
        private Carrier() { super(null,null); }
        @Override public ResourceLocation getVehicleId() { return ID; }
    }
    private static final class Station extends WeaponUnit {
        /** 站位配置标识。 */ String id;
        /** 载具。 */ AbstractVehicle vehicle;
        /** 操作根站。 */ Station root;
        private Station() { super(0,null,null); }
        @Override public String getId() { return id; }
        @Override public AbstractVehicle getVehicle() { return vehicle; }
        @Override public WeaponUnit getRootParentWeaponUnit() { return root; }
    }
}
