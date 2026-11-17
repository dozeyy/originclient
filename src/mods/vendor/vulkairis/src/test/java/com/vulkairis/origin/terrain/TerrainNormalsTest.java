package com.vulkairis.origin.terrain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainNormalsTest {
    private static int pack(float x, float y, float z) {
        return ((int) (x * 127f) & 255) | (((int) (y * 127f) & 255) << 8) | (((int) (z * 127f) & 255) << 16);
    }

    @Test
    void blockFacesRoundTripExactly() {
        float[][] faces = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (float[] f : faces) {
            int b = TerrainNormals.encode(pack(f[0], f[1], f[2]));
            assertNotEquals(TerrainNormals.NONE, b);
            float[] d = TerrainNormals.decode(b);
            for (int i = 0; i < 3; i++) {
                assertEquals(f[i], d[i], 1e-6, "face " + f[0] + "," + f[1] + "," + f[2]);
            }
        }
    }

    @Test
    void diagonalPlantQuadsStayClose() {
        float s = (float) Math.sqrt(0.5);
        float[] n = {s, 0, -s};
        float[] d = TerrainNormals.decode(TerrainNormals.encode(pack(n[0], n[1], n[2])));
        assertTrue(n[0] * d[0] + n[1] * d[1] + n[2] * d[2] > 0.98f); // within ~11 degrees at 15 levels per axis
    }

    @Test
    void zeroNormalAndVanillaAlphaMeanUp() {
        assertEquals(TerrainNormals.NONE, TerrainNormals.encode(0));
        float[] d = TerrainNormals.decode(0xFF);
        assertEquals(1f, d[1], 0f);
    }
}
