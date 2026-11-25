package com.jarvis.common.trace;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PrivateInferenceContextTest {
    @Test void scopedSuppressionRestoresEvenOnFailure(){assertFalse(PrivateInferenceContext.active());assertThrows(IllegalStateException.class,()->PrivateInferenceContext.call(()->{assertTrue(PrivateInferenceContext.active());throw new IllegalStateException("test");}));assertFalse(PrivateInferenceContext.active());}
}
