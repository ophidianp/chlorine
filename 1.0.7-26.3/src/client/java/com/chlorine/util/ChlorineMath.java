package com.chlorine.util;

/**
 * Fast client-side trigonometry for animation paths where a small bounded
 * approximation error is preferable to repeated libm calls.
 */
public final class ChlorineMath {
    private static final int TABLE_BITS = 14;
    private static final int TABLE_SIZE = 1 << TABLE_BITS;
    private static final int TABLE_MASK = TABLE_SIZE - 1;
    private static final float[] SIN = new float[TABLE_SIZE];
    private static final float INV_TAU = TABLE_SIZE / (float) (Math.PI * 2.0);

    static {
        for (int i = 0; i < TABLE_SIZE; i++) {
            SIN[i] = (float) Math.sin(i * (Math.PI * 2.0 / TABLE_SIZE));
        }
    }

    private ChlorineMath() {
    }

    public static float sin(float radians) {
        return SIN[((int) (radians * INV_TAU)) & TABLE_MASK];
    }

    public static float sin(double radians) {
        return sin((float) radians);
    }

    public static float cos(float radians) {
        return SIN[((int) (radians * INV_TAU + TABLE_SIZE * 0.25f)) & TABLE_MASK];
    }

    public static float cos(double radians) {
        return cos((float) radians);
    }
}
