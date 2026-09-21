package org.ywzj.rvp.client.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_HmdChannelStateTest {

    @Test
    void radarAndIrChannelsCanCoexist() {
        RVP_HmdChannelState state = new RVP_HmdChannelState();

        state.setIrActive(true);
        state.enableRadar();

        assertTrue(state.isRadarActive());
        assertTrue(state.isIrActive());
        assertTrue(state.isAnyActive());
    }

    @Test
    void disablingRadarDoesNotDisableIr() {
        RVP_HmdChannelState state = new RVP_HmdChannelState();
        state.setIrActive(true);
        state.enableRadar();

        state.disableRadar();

        assertFalse(state.isRadarActive());
        assertTrue(state.isIrActive());
        assertTrue(state.isAnyActive());
    }

    @Test
    void disablingIrDoesNotDisableRadar() {
        RVP_HmdChannelState state = new RVP_HmdChannelState();
        state.setIrActive(true);
        state.enableRadar();

        state.setIrActive(false);

        assertTrue(state.isRadarActive());
        assertFalse(state.isIrActive());
        assertTrue(state.isAnyActive());
    }

    @Test
    void disablingAllClearsBothChannels() {
        RVP_HmdChannelState state = new RVP_HmdChannelState();
        state.setIrActive(true);
        state.enableRadar();

        state.disableAll();

        assertFalse(state.isRadarActive());
        assertFalse(state.isIrActive());
        assertFalse(state.isAnyActive());
    }
}
