package game;

/**
 * Volumetric terrain representation used as a foundation for future cave/tunnel topology.
 * Stores signed density values on a 3D grid (positive = solid, negative = empty).
 */
public class TerrainVolume {
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final float minX;
    private final float maxX;
    private final float minY;
    private final float maxY;
    private final float minZ;
    private final float maxZ;
    private final float[] density;

    public TerrainVolume(int sizeX, int sizeY, int sizeZ,
                         float minX, float maxX,
                         float minY, float maxY,
                         float minZ, float maxZ) {
        this.sizeX = Math.max(2, sizeX);
        this.sizeY = Math.max(2, sizeY);
        this.sizeZ = Math.max(2, sizeZ);
        this.minX = minX;
        this.maxX = Math.max(minX + 0.001f, maxX);
        this.minY = minY;
        this.maxY = Math.max(minY + 0.001f, maxY);
        this.minZ = minZ;
        this.maxZ = Math.max(minZ + 0.001f, maxZ);
        this.density = new float[this.sizeX * this.sizeY * this.sizeZ];
    }

    public void fillFromHeightField(float[][] heights, int chunkCx, int chunkCz, int chunkSize) {
        for (int z = 0; z < sizeZ; z++) {
            float wz = lerp(minZ, maxZ, z / (float) (sizeZ - 1));
            float hx = 0f;
            for (int x = 0; x < sizeX; x++) {
                float wx = lerp(minX, maxX, x / (float) (sizeX - 1));
                float groundY = sampleHeight(heights, chunkCx, chunkCz, chunkSize, wx, wz);
                for (int y = 0; y < sizeY; y++) {
                    float wy = lerp(minY, maxY, y / (float) (sizeY - 1));
                    float d = groundY - wy;
                    density[idx(x, y, z)] = d;
                }
            }
        }
    }

    public void carveDirectionalTunnel(float startX, float startY, float startZ,
                                       float halfWidth, float halfLength,
                                       float dirX, float dirZ, float slopeY,
                                       boolean rectangular) {
        float dirLen = (float) Math.sqrt(dirX * dirX + dirZ * dirZ);
        float nx = dirLen > 0.0001f ? dirX / dirLen : 1f;
        float nz = dirLen > 0.0001f ? dirZ / dirLen : 0f;
        float tx = -nz;
        float tz = nx;

        for (int z = 0; z < sizeZ; z++) {
            float wz = lerp(minZ, maxZ, z / (float) (sizeZ - 1));
            for (int x = 0; x < sizeX; x++) {
                float wx = lerp(minX, maxX, x / (float) (sizeX - 1));
                float dx = wx - startX;
                float dz = wz - startZ;

                float forward = dx * nx + dz * nz;
                float side = dx * tx + dz * tz;
                if (forward < 0f || forward > halfLength) {
                    continue;
                }

                float shape;
                if (rectangular) {
                    float fx = forward / Math.max(0.0001f, halfLength);
                    float fz = Math.abs(side) / Math.max(0.0001f, halfWidth);
                    float edge = Math.max(fx, fz);
                    if (edge > 1f) {
                        continue;
                    }
                    shape = 1f - edge;
                } else {
                    float radial = (float) Math.sqrt(forward * forward + side * side);
                    if (radial > halfWidth) {
                        continue;
                    }
                    shape = 1f - radial / Math.max(0.0001f, halfWidth);
                }

                shape = Math.max(0f, Math.min(1f, shape));
                if (shape <= 0.0001f) {
                    continue;
                }

                float tunnelFloor = startY + forward * slopeY;
                for (int y = 0; y < sizeY; y++) {
                    float wy = lerp(minY, maxY, y / (float) (sizeY - 1));
                    if (wy >= tunnelFloor) {
                        continue;
                    }
                    int i = idx(x, y, z);
                    density[i] = Math.min(density[i], -shape);
                }
            }
        }
    }

    private float sampleHeight(float[][] heights, int chunkCx, int chunkCz, int chunkSize, float wx, float wz) {
        float lx = wx - chunkCx * chunkSize;
        float lz = wz - chunkCz * chunkSize;

        int x0 = clamp((int) Math.floor(lx), 0, chunkSize - 1);
        int z0 = clamp((int) Math.floor(lz), 0, chunkSize - 1);
        int x1 = clamp(x0 + 1, 0, chunkSize);
        int z1 = clamp(z0 + 1, 0, chunkSize);

        float fx = lx - x0;
        float fz = lz - z0;
        float h00 = heights[x0][z0];
        float h10 = heights[x1][z0];
        float h01 = heights[x0][z1];
        float h11 = heights[x1][z1];
        float h0 = h00 + (h10 - h00) * fx;
        float h1 = h01 + (h11 - h01) * fx;
        return h0 + (h1 - h0) * fz;
    }

    private int idx(int x, int y, int z) {
        return (z * sizeY + y) * sizeX + x;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
