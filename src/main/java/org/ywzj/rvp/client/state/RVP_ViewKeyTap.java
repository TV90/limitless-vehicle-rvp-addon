package org.ywzj.rvp.client.state;

/** A physical press and release on the same camera action; holding never repeats it. */
final class RVP_ViewKeyTap {
    static final int NONE = -1;
    static final int OPEN = -2;
    private static final long MAX_TAP_NANOS = 300_000_000L;
    private int context = NONE;
    private long started;

    void press(int context, long time) {
        if (isPressed()) return;
        this.context = context;
        started = time;
    }

    boolean isPressed() {
        return context != NONE;
    }

    boolean release(int current, long time) {
        boolean accepted = isPressed() && context == current && time - started <= MAX_TAP_NANOS;
        cancel();
        return accepted;
    }

    void cancel() {
        context = NONE;
    }
}
