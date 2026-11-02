package org.ywzj.rvp.network;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 网络只传 display 对应武器标识，不再复制/持有音效配置。 */
class S2CVehicleSoundTest {
    @Test void weaponIdentityAndSourceRoundTripWithoutEntityBeingLoaded() {
        var original=new S2CVehicleSound(new ResourceLocation("minecraft","overworld"),UUID.randomUUID(),37,
                new Vec3(1,2,3),new ResourceLocation("example","weapon"));
        var b=new FriendlyByteBuf(Unpooled.buffer());
        try { S2CVehicleSound.encode(original,b); assertEquals(original,S2CVehicleSound.decode(b));
            assertEquals(0,b.readableBytes()); } finally { b.release(); }
    }
    @Test void invalidPositionsAndMissingWeaponAreRejected() {
        var dimension=new ResourceLocation("minecraft","overworld"); var uuid=UUID.randomUUID();
        var id=new ResourceLocation("example","weapon");
        assertThrows(IllegalArgumentException.class,()->new S2CVehicleSound(dimension,uuid,1,new Vec3(Double.NaN,0,0),id));
        assertThrows(NullPointerException.class,()->new S2CVehicleSound(dimension,uuid,1,Vec3.ZERO,null));
    }
}
