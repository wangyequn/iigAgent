package com.sinosoft.amp.openapi.knowledge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VectorCodecTest {

    @Test
    void roundTripLongVector() {
        double[] v = new double[1536];
        v[0] = 0.5;
        v[1535] = -0.25;
        double[] back = VectorCodec.fromJson(VectorCodec.toJson(v));
        assertEquals(1536, back.length);
        assertEquals(0.5, back[0], 1e-6);
        assertEquals(-0.25, back[1535], 1e-6);
    }

    @Test
    void cosineSelfIsOne() {
        double[] a = new LocalHashTextEmbedder().embed("dangerous_write 写入");
        assertEquals(1.0, VectorCodec.cosine(a, a), 1e-6);
    }

    @Test
    void cosineDifferentLengthsUsesMin() {
        double[] a = {1, 0, 0};
        double[] b = {1, 0};
        assertTrue(VectorCodec.cosine(a, b) > 0.9);
    }

    @Test
    void httpEmbedderFallsBackOnFailure() {
        TextEmbedder e = new HttpTextEmbedder("http://127.0.0.1:1", "k", "m", null, new LocalHashTextEmbedder());
        double[] v = e.embed("hello");
        assertEquals(LocalHashEmbedder.DIM, v.length);
        assertTrue(e.id().startsWith("http:"));
    }
}
