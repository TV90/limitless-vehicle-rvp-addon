package org.ywzj.rvp.client.state;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.network.S2CHbmMissileSnapshot;
import org.ywzj.rvp.radar.RVP_HbmRadarContact;

public class RVP_RemoteHbmMissileEntity extends Entity implements RVP_HbmRadarContact {

    private static final EntityDimensions DIMENSIONS = EntityDimensions.scalable(1.5f, 5.0f);

    private final int remoteEntityId;
    @Nullable
    private S2CHbmMissileSnapshot.Affiliation affiliation;
    @Nullable
    private Vec3 radarTargetPos;

    public RVP_RemoteHbmMissileEntity(Level level, int remoteEntityId) {
        super(EntityType.ARMOR_STAND, level);
        this.remoteEntityId = remoteEntityId;
        this.noPhysics = true;
    }

    public void apply(S2CHbmMissileSnapshot.Entry entry) {
        affiliation = entry.affiliation();
        radarTargetPos = entry.targetPos();
        setPos(entry.x(), entry.y(), entry.z());
        setDeltaMovement(entry.vx(), entry.vy(), entry.vz());
        setYRot(entry.yaw());
        setXRot(0.0f);
        setBoundingBox(buildBox(position()));
        xo = getX();
        yo = getY();
        zo = getZ();
        xOld = getX();
        yOld = getY();
        zOld = getZ();
    }

    @Override
    public int getId() {
        return remoteEntityId;
    }

    @Override
    public boolean isAlive() {
        return !isRemoved();
    }

    @Override
    public EntityDimensions getDimensions(net.minecraft.world.entity.Pose pose) {
        return DIMENSIONS;
    }

    @Override
    protected void defineSynchedData() {}

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {}

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {}

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        throw new UnsupportedOperationException("Remote proxy entity should never be spawned over the network");
    }

    @Nullable
    @Override
    public Vec3 ywzj_rvp$getRadarTargetPos() {
        return radarTargetPos;
    }

    @Nullable
    @Override
    public S2CHbmMissileSnapshot.Affiliation ywzj_rvp$getRadarAffiliation() {
        return affiliation;
    }

    private AABB buildBox(Vec3 pos) {
        float width = DIMENSIONS.width;
        float height = DIMENSIONS.height;
        double halfWidth = width / 2.0;
        return new AABB(
                pos.x - halfWidth, pos.y - height / 2.0, pos.z - halfWidth,
                pos.x + halfWidth, pos.y + height / 2.0, pos.z + halfWidth
        );
    }
}
