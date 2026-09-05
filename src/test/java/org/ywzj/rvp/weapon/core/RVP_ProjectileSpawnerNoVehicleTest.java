package org.ywzj.rvp.weapon.core;

import org.junit.jupiter.api.Test;
import org.ywzj.rvp.entity.projectile.RVP_BombEntity;
import org.ywzj.rvp.entity.projectile.RVP_BulletEntity;
import org.ywzj.rvp.entity.projectile.RVP_DispensedEntity;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.entity.projectile.RVP_RocketEntity;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RVP_ProjectileSpawnerNoVehicleTest {
    @Test
    void allFiveEntityProjectileKindsUseTypedRvpClasses() {
        Map<RVP_EnumWeaponKind, Class<?>> expected = Map.of(
                RVP_EnumWeaponKind.MISSILE, RVP_MissileEntity.class,
                RVP_EnumWeaponKind.ROCKET, RVP_RocketEntity.class,
                RVP_EnumWeaponKind.MACHINEGUN, RVP_BulletEntity.class,
                RVP_EnumWeaponKind.BOMB, RVP_BombEntity.class,
                RVP_EnumWeaponKind.DISPENSER, RVP_DispensedEntity.class);
        expected.forEach((kind, type) -> {
            assertTrue(RVP_ProjectileEntityFactory.supports(kind));
            assertEquals(type, RVP_ProjectileEntityFactory.projectileClassFor(kind));
        });
        assertFalse(RVP_ProjectileEntityFactory.supports(RVP_EnumWeaponKind.LASER));
        assertFalse(RVP_ProjectileEntityFactory.supports(RVP_EnumWeaponKind.TARGETING_POD));
    }

    @Test
    void noVehicleCorePreservesOwnerDetonationSubmunitionAndPrimeContracts() throws IOException {
        String spawner = Files.readString(Path.of("src/main/java/org/ywzj/rvp/weapon/core/RVP_ProjectileSpawner.java"));
        String vertical = Files.readString(Path.of(
                "src/main/java/org/ywzj/rvp/firesupport/server/RVP_VerticalProjectileDelivery.java"));
        String base = Files.readString(Path.of(
                "src/main/java/org/ywzj/rvp/entity/projectile/RVP_BaseBullet.java"));
        String submunition = Files.readString(Path.of(
                "src/main/java/org/ywzj/rvp/weapon/submunition/RVP_SubmunitionSpawner.java"));

        assertAll(
                () -> assertTrue(vertical.contains("level(), weapon, kind, null, null, null, null, context.owner()"),
                        "垂直投送必须显式传 owner 且三个载具/武器单元引用为空"),
                () -> assertTrue(spawner.contains("context.owner(), context.spawnPosition()"),
                        "核心生成器必须把 owner 交给 initFromWeapon"),
                () -> assertTrue(spawner.indexOf("addFreshEntity(projectile)")
                                < spawner.indexOf("projectile.primeDynamicChunkPath()"),
                        "动态路径只能在成功入世后预热"),
                () -> assertTrue(base.contains("shooterVehicle == null && getOwner() == null"),
                        "射手有效性必须允许无载具但有 owner"),
                () -> assertTrue(base.contains("getOwner(), shooterVehicle, blockImpact"),
                        "自定义落点效果必须保留 owner 并接受空载具"),
                () -> assertTrue(submunition.contains("parent.getOwner() instanceof LivingEntity"),
                        "子弹药必须继承母弹 owner"),
                () -> assertTrue(submunition.contains("RVP_ProjectileEntityFactory.create"),
                        "子弹药必须复用统一类型工厂"));
    }
}
