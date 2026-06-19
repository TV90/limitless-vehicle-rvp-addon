package org.ywzj.rvp.util;

import org.ywzj.vehicle.custom.part.data.PartUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.SwitchableUnit;

/**
 * 将 RadarUnit 适配为 SwitchableUnit，使雷达开关动画能够通过 SwitchableRunner 播放。
 * <p>
 * {@code isOn()} 直接委托给 RadarUnit，不缓存，保证 SwitchableRunner 能检测到状态变化。
 * </p>
 */
public class RadarUnitSwitchableAdapter extends SwitchableUnit<PartUnitData> {

    private final RadarUnit radar;

    /** 哑数据，仅提供 id/name 给 PartUnit 构造函数使用。 */
    private static class DummyData extends PartUnitData {
        public DummyData(String id) {
            super(id);
            this.name = id;
        }
    }

    public RadarUnitSwitchableAdapter(RadarUnit radar) {
        super(radar.getIndex(), radar.getVehicle(), new DummyData(radar.getId() + "_switch_adapter"));
        this.radar = radar;
    }

    @Override
    public boolean isOn() {
        return radar.isOn();
    }

    @Override
    public AbstractVehicle getVehicle() {
        return radar.getVehicle();
    }

    @Override
    public boolean defaultOpen() {
        return true;
    }
}
