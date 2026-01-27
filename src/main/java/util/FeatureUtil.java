package util;
public class FeatureUtil {

    public static long hashSeed(float x, float y, float z, long globalSeed) {
        int ix = (int) (x * 1000);
        int iy = (int) (y * 1000);
        int iz = (int) (z * 1000);

        long hash = 1125899906842597L; // Large prime
        hash = 31 * hash + ix;
        hash = 31 * hash + iy;
        hash = 31 * hash + iz;

        return hash ^ globalSeed;
    }
}
