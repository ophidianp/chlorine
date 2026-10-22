package com.chlorine.client;

public final class ClientEffectBudgets {
    private static int soundsThisTick;
    private static int particlesThisTick;
    private static int soundLimit = 1;
    private static int particleLimit = 1;

    private ClientEffectBudgets() {
    }

    public static void update(int fps, int configuredSoundLimit, int configuredParticleLimit,
                              int lowFpsThreshold, double lowFpsScale) {
        double scale = fps > 0 && fps < Math.max(10, lowFpsThreshold)
                ? clamp(lowFpsScale, 0.1, 1.0)
                : 1.0;
        soundLimit = Math.max(1, (int) Math.ceil(Math.max(1, configuredSoundLimit) * scale));
        particleLimit = Math.max(1, (int) Math.ceil(Math.max(1, configuredParticleLimit) * scale));
    }

    public static void reset() {
        soundsThisTick = 0;
        particlesThisTick = 0;
    }

    public static boolean tryConsumeSound(int limit) {
        if (soundsThisTick >= Math.min(Math.max(1, limit), soundLimit)) {
            return false;
        }
        soundsThisTick++;
        return true;
    }

    public static boolean tryConsumeParticle(int limit) {
        if (particlesThisTick >= Math.min(Math.max(1, limit), particleLimit)) {
            return false;
        }
        particlesThisTick++;
        return true;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
