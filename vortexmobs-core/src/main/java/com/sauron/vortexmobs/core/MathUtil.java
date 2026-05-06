package com.sauron.vortexmobs.core;

final class MathUtil {

    private MathUtil() {
    }

    static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    static double clamp01(double value) {
        return clamp(value, 0.0D, 1.0D);
    }

    static double lerp(double start, double end, double factor) {
        return start + ((end - start) * factor);
    }
}