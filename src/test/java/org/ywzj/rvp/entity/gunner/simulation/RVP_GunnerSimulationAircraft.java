package org.ywzj.rvp.entity.gunner.simulation;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.ywzj.rvp.flight.RVP_FlightExecutor;
import org.ywzj.rvp.flight.RVP_FlightMath;
import org.ywzj.rvp.flight.RVP_FlightTypes.*;

import java.util.UUID;

/**
 * 测试飞机：沿用已有固定翼回归测试的气动积分顺序和试验机参数。
 * 这是数值夹具，不创建本体实体；不含部件受损、风、地面滑跑和真实方块碰撞。
 */
final class RVP_GunnerSimulationAircraft {
    /** 试验机质量，kg。 */ private static final double MASS = 4074;
    /** 试验机推力，N。 */ private static final double THRUST = 24444;
    /** 最低阻力系数。 */ private static final double DRAG_MIN = 16.296;
    /** 最高阻力系数。 */ private static final double DRAG_MAX = 24.444;
    /** 固定步长，秒。 */ private static final double DT = 0.05;
    /** 当前世界位置。 */ private double[] position;
    /** 当前速度，格/刻；与本体积分单位一致。 */ private Vector3f velocity;
    /** Minecraft 航向。 */ private double yaw;
    /** 本体俯仰，低头正值。 */ private double pitch;
    /** 本体右倾角。 */ private double bank;
    /** 上一刻右倾角，用于观察角速度。 */ private double previousBank;
    /** 本刻执行器输出。 */ private RVP_FlightExecutor.Frame frame;
    /** 生产执行器；每场每机各自持有。 */ private final RVP_FlightExecutor executor = new RVP_FlightExecutor();

    RVP_GunnerSimulationAircraft(double[] position, double yaw, double speed) {
        this.position=position.clone(); this.yaw=yaw;
        velocity=new Vector3f((float)(-Math.sin(Math.toRadians(yaw))*speed/20), 0,
                (float)(Math.cos(Math.toRadians(yaw))*speed/20));
    }

    /** 复制控制器需要的本刻观察，地形夹具为已知平地。 */
    Observation observation(UUID id, long tick, double groundY) {
        double bankRate=RVP_FlightMath.wrap(bank-previousBank)*20;
        previousBank=bank;
        return new Observation(id, "simulation", tick, 1, new Vector(position[0],position[1],position[2]),
                new Vector(velocity.x*20,velocity.y*20,velocity.z*20), yaw,-pitch,bank,bankRate,
                frame==null ? 100 : frame.throttle(),false,new Terrain(groundY,groundY,true,true));
    }

    /** 按当前速度与高度构造试验机能力，与既有回归测试参数一致。 */
    Airframe airframe() {
        double response=airScale()*velocity.length()*0.4;
        return new Airframe(MASS,THRUST,DRAG_MIN,DRAG_MAX,3,airScale(),-10,25,
                Math.min(2,response*2),Math.min(3,response*3),Math.min(4,response*4),130);
    }

    /** 通过生产执行器实际翻译输入，再由测试模型消费这些输出。 */
    void apply(UUID id, long tick, Command command) {
        var port = new RVP_FlightExecutor.Port() {
            @Override public UUID vehicleId() { return id; }
            @Override public String dimension() { return "simulation"; }
            @Override public long tick() { return tick; }
            @Override public boolean available() { return true; }
            @Override public boolean owns(long generation) { return generation==1; }
            @Override public boolean onGround() { return false; }
            @Override public double pitchStep() { return 0.05; }
            @Override public double yawStep() { return 0.05; }
            @Override public double rollStep() { return 0.05; }
            @Override public double maximumThrottle() { return 130; }
            @Override public boolean hasLandingGear() { return true; }
            @Override public boolean hasAirbrake() { return true; }
            @Override public void write(RVP_FlightExecutor.Frame value) { frame=value; }
            @Override public void clear() { frame=null; }
        };
        // 调用生产执行器，校验会话与刻号，并获得真实补偿后的输入字段。
        var result=executor.execute(port,command);
        if (result!=RVP_FlightExecutor.Result.APPLIED && result!=RVP_FlightExecutor.Result.APPLIED_ADJUSTED)
            throw new IllegalStateException("测试飞机拒绝输入："+result);
    }

    /** 单刻气动积分，公式依据已有 RVP_FixedWingFlightRegressionTest。 */
    void step() {
        double pitchInput=frame.pitchSetter()-0.05, yawInput=frame.yawSetter()+0.05, rollInput=frame.rollSetter()-0.05;
        Quaternionf rotation=rotation();
        Vector3f forward=rotation.transform(new Vector3f(0,0,1)), up=rotation.transform(new Vector3f(0,1,0));
        Vector3f left=rotation.transform(new Vector3f(1,0,0)), air=new Vector3f(velocity);
        air.add(new Vector3f(forward).mul((float)(THRUST*(frame.throttle()/100)/MASS/400)));
        double angleX=angle(air,up)-Math.PI/2;
        double drag=(DRAG_MAX-DRAG_MIN)*Math.abs(Math.sin(angleX))+DRAG_MIN;
        double initialSpeed=air.length(), speedSquared=initialSpeed*initialSpeed*400;
        air.normalize().mul((float)Math.max(0.001,initialSpeed-speedSquared*drag/MASS/400));
        double rawSpeed=air.length(), aoa=Math.toDegrees(angleX), scale=airScale();
        double liftFactor=aoa>=-10 && aoa<=25 ? 3*scale+2*scale*aoa/25
                : 3*scale*Math.exp(-(aoa>25 ? aoa-25 : -10-aoa));
        air.add(new Vector3f(up).mul((float)(speedSquared*drag*liftFactor/MASS/400)));
        double angleY=angle(air,left)-Math.PI/2, forwardSpeed=air.dot(forward);
        air.add(new Vector3f(left).mul((float)(forwardSpeed*forwardSpeed*400*8*drag*Math.sin(angleY)/MASS/400)));
        double controlDrag=(Math.abs(pitchInput)+Math.abs(yawInput)/4+Math.abs(rollInput)/8)*DRAG_MIN;
        air.normalize().mul((float)Math.max(0.001,rawSpeed-speedSquared*controlDrag/MASS/400));
        double response=scale*air.length()*0.4;
        Quaternionf next=rotation();
        next.rotateZ((float)Math.toRadians(rollInput*Math.min(4,response*4)));
        next.rotateX((float)Math.toRadians(pitchInput*Math.min(2,response*2)));
        double finalAngleY=angle(air,left)-Math.PI/2;
        double yawDamp=Math.min(1,2/Math.max(1.0E-6,Math.abs(Math.toDegrees(finalAngleY))));
        if (Math.abs(yawInput)>0) next.rotateY((float)Math.toRadians(yawInput*Math.min(3,response*3)*yawDamp));
        else next.rotateY((float)(response*-finalAngleY/5));
        Vector3f down=new Vector3f(up).negate();
        if (angle(air,down)<Math.PI/4) next.rotateY((float)(Math.PI/72*Math.min(1.5,air.dot(down))));
        Vector3f euler=next.getEulerAnglesYXZ(new Vector3f());
        pitch=Math.toDegrees(euler.x); yaw=Math.toDegrees(-euler.y); bank=Math.toDegrees(euler.z);
        // 测试夹具采用本体的每刻重力 0.0245 格/刻²；不是直接修改真实实体速度。
        air.y-=(float)(RVP_FlightMath.GRAVITY*DT*DT);
        velocity=air;
        position=new double[]{position[0]+air.x,position[1]+air.y,position[2]+air.z};
        for (double value:position) if (!Double.isFinite(value)) throw new IllegalStateException("气动夹具产生非有限坐标");
    }

    /** 碰撞由外部世界夹具阻止；只重置模拟位置与速度。 */
    void stopAt(double[] previous) { position=previous.clone(); velocity.set(0,0,0.001f); }
    /** 空气高度缩放，试验机升限为世界 Y=512。 */
    private double airScale() { return Math.max(0.001,position[1]<64 ? 1 : Math.sqrt(Math.max(0,512-position[1])/448)); }
    /** 本体 YXZ 姿态约定。 */
    private Quaternionf rotation() { return new Quaternionf().rotateY((float)Math.toRadians(-yaw)).rotateX((float)Math.toRadians(pitch)).rotateZ((float)Math.toRadians(bank)); }
    /** 有限向量夹角。 */
    private static double angle(Vector3f a,Vector3f b) { return Math.acos(Math.max(-1,Math.min(1,a.dot(b)/Math.max(1.0E-12,a.length()*b.length())))); }
    /** 位置副本，格。 */ double[] position() { return position.clone(); }
    /** 当前航向，度。 */ double yaw() { return yaw; }
    /** 抬头为正的俯仰，度。 */ double pitchUp() { return -pitch; }
    /** 右倾角，度。 */ double bank() { return bank; }
    /** 当前空速，格/秒。 */ double speed() { return velocity.length()*20; }
}
