package org.ywzj.rvp.radar;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationBootstrap;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationBootstrap.environment;
import static org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationBootstrap.write;

/** 使用真实实体 ID 与本体探测表，复现“本地排除自身后，广播又补回自身克隆”的回归。 */
class RVP_RadarSelfFilterTest {
    @BeforeAll
    static void bootstrap() {
        RVP_ProjectileSimulationBootstrap.initialize();
    }

    @Test
    void mergedContactsRejectOwnCloneWithoutHidingAnotherVehicleOfSameType() {
        Entity owner = entity(71);
        Entity ownClone = entity(71);
        Entity sameTypeOther = entity(72);
        assertNotSame(owner, ownClone);
        List<Entity> merged = new ArrayList<>(List.of(owner, ownClone, sameTypeOther));
        RVP_RadarSelfFilter.removeSelfTargets(owner, merged);
        assertEquals(List.of(sameTypeOther), merged);
    }

    @Test
    void pollutedRadarClearsOwnContactAndLockButPreservesRealThreat() {
        FixedWingVehicle owner = environment(FixedWingVehicle.class);
        owner.setId(71);
        // 为本体解锁方法提供服务端边界；不构造游戏世界，也不替换待测探测表算法。
        write(Entity.class, owner, "level", environment(ServerLevel.class));
        RadarUnit radar = environment(RadarUnit.class);
        write(PartUnit.class, radar, "vehicle", owner);
        Entity ownClone = entity(71);
        Entity threat = entity(72);
        HashMap<Integer, RadarUnit.DetectedObject> contacts = new HashMap<>();
        contacts.put(71, contact(ownClone));
        contacts.put(72, contact(threat));
        write(RadarUnit.class, radar, "detectedObjects", contacts);
        write(RadarUnit.class, radar, "lockedEntity", ownClone);
        RVP_RadarSelfFilter.purgeRadar(radar);
        assertEquals(1, contacts.size());
        assertSame(threat, contacts.get(72).entity);
        assertNull(radar.getLockedEntity());
        write(RadarUnit.class, radar, "lockedEntity", threat);
        RVP_RadarSelfFilter.purgeRadar(radar);
        assertSame(threat, radar.getLockedEntity());
    }

    /** 只用实体身份字段，不用类型名或 UUID 模拟真实网络包的 ID 语义。 */
    private static Entity entity(int id) {
        Entity entity = environment(ArmorStand.class);
        entity.setId(id);
        return entity;
    }

    /** 使用本体航迹记录，确保清理操作实际作用于生产数据结构。 */
    private static RadarUnit.DetectedObject contact(Entity entity) {
        RadarUnit.DetectedObject contact = new RadarUnit.DetectedObject();
        contact.entity = entity;
        return contact;
    }
}
