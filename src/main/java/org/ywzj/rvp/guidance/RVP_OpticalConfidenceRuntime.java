package org.ywzj.rvp.guidance;

/** 集成服务端与客户端各有一份公式，热切换后下发给当前连接中的客户端。 */
public final class RVP_OpticalConfidenceRuntime {
    /** 当前开服会话的权威规则。 */
    private static volatile RVP_OpticalConfidenceFormula server = RVP_OpticalConfidenceFormula.DEFAULT;
    /** 当前连接收到的显示规则。 */
    private static volatile RVP_OpticalConfidenceFormula client = RVP_OpticalConfidenceFormula.DEFAULT;

    private RVP_OpticalConfidenceRuntime() {}

    public static RVP_OpticalConfidenceFormula server() {
        return server;
    }

    public static void setServer(RVP_OpticalConfidenceFormula formula) {
        server = formula;
    }

    public static RVP_OpticalConfidenceFormula client() {
        return client;
    }

    public static void setClient(RVP_OpticalConfidenceFormula formula) {
        client = formula;
    }
}
