package org.ywzj.rvp.guidance;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.network.S2COpticalConfidenceFormula;

import static org.junit.jupiter.api.Assertions.*;

class RVP_OpticalConfidenceFormulaTest {
    @Test
    void defaultFormulaKeepsTheTwoFactorAndMinimumBoundaries() {
        var formula = RVP_OpticalConfidenceFormula.DEFAULT;
        assertEquals(0.048f, formula.score(0.20f, 0.20f, 1f), 0.0001f);
        assertEquals(0.48f, formula.score(0.60f, 0.30f, 0.60f), 0.0001f);
        assertEquals(0.36f, formula.score(1f, 0.20f, 1f), 0.0001f);
        assertEquals(0.36f, formula.score(1f, 1f, 0.20f), 0.0001f);
        assertEquals(0.19f, formula.score(1f, 0.10f, 1f), 0.0001f);
        assertEquals(0.41f, formula.score(0.50f, 0.25f, 0.50f), 0.0001f);
        assertEquals(0.40f, formula.score(0.50f, 0.24f, 1f), 0.0001f);
        assertFalse(RVP_OpticalTargeting.canDisplay(formula.score(0.49f, 0.49f, 1f)));
        assertEquals(0f, formula.score(0f, 1f, 1f));
    }

    @Test
    void expressionsSupportArithmeticAliasesAndLazyConditions() {
        var arithmetic = RVP_OpticalConfidenceFormula.compile("1-0.2*2+1e-1");
        assertEquals(0.7f, arithmetic.score(1, 1, 1), 0.0001f);
        var custom = RVP_OpticalConfidenceFormula.compile("if(q>=.5,clamp(max(V,S,L)*.8,0,1),sqrt(-1))");
        assertEquals(.72f, custom.score(.6f, .9f, .3f), .0001f);
        assertEquals(0f, custom.score(.3f, .9f, .3f));
        assertEquals(.50f, RVP_OpticalConfidenceFormula.compile("median(V,S,L)")
                .score(.5f, .9f, .3f), .0001f);
        assertEquals(1f, RVP_OpticalConfidenceFormula.compile("2^3^2/512").score(1, 1, 1));
        assertEquals(0f, RVP_OpticalConfidenceFormula.compile("1/(V-V)").score(1, 1, 1));
    }

    @Test
    void invalidInputsCannotBecomeExecutableFormulas() {
        for (String text : new String[]{"", "V+?", "if(V,.1)", "V S", "V; S", "new(V)", "2..3", "(V",
                "(".repeat(25) + "1" + ")".repeat(25), "1".repeat(221)}) {
            assertThrows(IllegalArgumentException.class, () -> RVP_OpticalConfidenceFormula.compile(text), text);
        }
    }

    @Test
    void formulaSyncCarriesTheExactExpressionAndKeepsLogicalSidesIndependent() {
        var message = new S2COpticalConfidenceFormula("V * S * L");
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            S2COpticalConfidenceFormula.encode(message, buffer);
            var decoded = S2COpticalConfidenceFormula.decode(buffer);
            assertEquals(message, decoded);
            var runtime = RVP_OpticalConfidenceFormula.compile(decoded.expression());
            assertEquals(.125f, runtime.score(.5f, .5f, .5f), .0001f);
            RVP_OpticalConfidenceRuntime.setServer(runtime);
            assertEquals(.125f, RVP_OpticalConfidenceRuntime.server().score(.5f, .5f, .5f), .0001f);
            assertEquals(.50f, RVP_OpticalConfidenceRuntime.client().score(.5f, .5f, .5f), .0001f);
        } finally {
            RVP_OpticalConfidenceRuntime.setServer(RVP_OpticalConfidenceFormula.DEFAULT);
            buffer.release();
        }
    }
}
