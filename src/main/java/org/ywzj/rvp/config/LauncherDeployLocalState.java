package org.ywzj.rvp.config;

public class LauncherDeployLocalState {
    public LauncherDeployRuntimeManager.State state;
    public int progressTick;

    public LauncherDeployLocalState(LauncherDeployRuntimeManager.State state, int progressTick) {
        this.state = state;
        this.progressTick = progressTick;
    }
}
