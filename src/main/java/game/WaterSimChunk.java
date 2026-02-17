package game;

/**
 * Phase-1 liquid migration container.
 *
 * Stores a simulation-friendly water state (bed, depth, velocity) on the chunk grid,
 * and exposes bilinear surface sampling for rendering.
 */
public class WaterSimChunk {
    private final int gridSize;
    private final float[][] bedHeight;
    private final float[][] waterDepth;
    private final float[][] velX;
    private final float[][] velZ;

    public WaterSimChunk(int gridSize) {
        this.gridSize = gridSize;
        this.bedHeight = new float[gridSize + 1][gridSize + 1];
        this.waterDepth = new float[gridSize + 1][gridSize + 1];
        this.velX = new float[gridSize + 1][gridSize + 1];
        this.velZ = new float[gridSize + 1][gridSize + 1];
    }

    public void initializeFromTerrainAndSurface(float[][] terrainHeights, float[][] riverSurface, float waterLevel) {
        for (int x = 0; x <= gridSize; x++) {
            for (int z = 0; z <= gridSize; z++) {
                float bed = terrainHeights[x][z];
                bedHeight[x][z] = bed;

                float river = riverSurface[x][z];
                boolean hasRiverWater = river > Float.NEGATIVE_INFINITY / 2f;
                float targetSurface = hasRiverWater ? river : waterLevel;
                if (!hasRiverWater && bed >= waterLevel) {
                    targetSurface = bed;
                }

                waterDepth[x][z] = Math.max(0f, targetSurface - bed);
                velX[x][z] = 0f;
                velZ[x][z] = 0f;
            }
        }
    }

    public float sampleSurface(float localX, float localZ) {
        float sx = clamp(localX, 0f, gridSize);
        float sz = clamp(localZ, 0f, gridSize);

        int x0 = (int) Math.floor(sx);
        int z0 = (int) Math.floor(sz);
        int x1 = Math.min(gridSize, x0 + 1);
        int z1 = Math.min(gridSize, z0 + 1);

        float tx = sx - x0;
        float tz = sz - z0;

        float s00 = bedHeight[x0][z0] + waterDepth[x0][z0];
        float s10 = bedHeight[x1][z0] + waterDepth[x1][z0];
        float s01 = bedHeight[x0][z1] + waterDepth[x0][z1];
        float s11 = bedHeight[x1][z1] + waterDepth[x1][z1];

        float sx0 = lerp(s00, s10, tx);
        float sx1 = lerp(s01, s11, tx);
        return lerp(sx0, sx1, tz);
    }

    public float sampleSpeed(float localX, float localZ) {
        float sx = clamp(localX, 0f, gridSize);
        float sz = clamp(localZ, 0f, gridSize);

        int xi = Math.min(gridSize, Math.max(0, Math.round(sx)));
        int zi = Math.min(gridSize, Math.max(0, Math.round(sz)));
        float ux = velX[xi][zi];
        float uz = velZ[xi][zi];
        return (float) Math.sqrt(ux * ux + uz * uz);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
