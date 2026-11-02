package org.ywzj.rvp.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.weapon.data.RVP_FireSoundData;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 可配置事件和近远参数完整通过网络，不再把固定类型枚举当作声音配置。 */
class S2CVehicleSoundTest {
    @Test void explicitSingleAndTwoBandSoundConfigsRoundTrip() {
        var near=new ResourceLocation("example","launch"); var far=new ResourceLocation("example","far");
        for(var distant:new ResourceLocation[]{null,far}) {
            var c=new RVP_FireSoundData(near,distant,distant==null?0:137.5F,950,2.5F,.85F);
            var original=new S2CVehicleSound(new ResourceLocation("minecraft","overworld"),UUID.randomUUID(),37,new Vec3(1,2,3),c);
            var b=new FriendlyByteBuf(Unpooled.buffer());
            try {
                S2CVehicleSound.encode(original,b); var decoded=S2CVehicleSound.decode(b);
                assertEquals(original.dimension(),decoded.dimension()); assertEquals(original.vehicleId(),decoded.vehicleId());
                assertEquals(original.entityId(),decoded.entityId()); assertEquals(original.position(),decoded.position());
                assertEquals(c.getSound(),decoded.config().getSound()); assertEquals(c.getFarSound(),decoded.config().getFarSound());
                assertEquals(c.getFarDistance(),decoded.config().getFarDistance()); assertEquals(c.getMaxDistance(),decoded.config().getMaxDistance());
                assertEquals(c.getVolume(),decoded.config().getVolume()); assertEquals(c.getPitch(),decoded.config().getPitch());
                assertEquals(0,b.readableBytes());
            } finally { b.release(); }
        }
    }
    @Test void invalidPositionsAndMissingOrInvalidSoundConfigsAreRejected() {
        var dimension=new ResourceLocation("minecraft","overworld"); var uuid=UUID.randomUUID();
        var c=new RVP_FireSoundData(new ResourceLocation("example","sound"),null,0,100,1,1);
        assertThrows(IllegalArgumentException.class,()->new S2CVehicleSound(dimension,uuid,1,new Vec3(Double.NaN,0,0),c));
        assertThrows(NullPointerException.class,()->new S2CVehicleSound(dimension,uuid,1,Vec3.ZERO,null));
        assertThrows(IllegalArgumentException.class,()->new S2CVehicleSound(dimension,uuid,1,Vec3.ZERO,new RVP_FireSoundData()));
    }
}
