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
    private final float[][] maxSurface;
    private final float[][] velX;
    private final float[][] velZ;
    private final float[][] tmpDepth;
    private final float[][] tmpVelX;
    private final float[][] tmpVelZ;
    private final float[][] disturbance;
    private final float[][] tmpDisturbance;

    public WaterSimChunk(int gridSize) {
        this.gridSize = gridSize;
        this.bedHeight = new float[gridSize + 1][gridSize + 1];
        this.waterDepth = new float[gridSize + 1][gridSize + 1];
        this.maxSurface = new float[gridSize + 1][gridSize + 1];
        this.velX = new float[gridSize + 1][gridSize + 1];
        this.velZ = new float[gridSize + 1][gridSize + 1];
        this.tmpDepth = new float[gridSize + 1][gridSize + 1];
        this.tmpVelX = new float[gridSize + 1][gridSize + 1];
        this.tmpVelZ = new float[gridSize + 1][gridSize + 1];
        this.disturbance = new float[gridSize + 1][gridSize + 1];
        this.tmpDisturbance = new float[gridSize + 1][gridSize + 1];
    }

    public void initializeFromTerrainAndSurface(float[][] terrainHeights, float waterLevel) {
        for (int x = 0; x <= gridSize; x++) {
            for (int z = 0; z <= gridSize; z++) {
                float bed = terrainHeights[x][z];
                bedHeight[x][z] = bed;

                float targetSurface = bed < waterLevel ? waterLevel : bed;

                waterDepth[x][z] = Math.max(0f, targetSurface - bed);
                maxSurface[x][z] = targetSurface;
                velX[x][z] = 0f;
                velZ[x][z] = 0f;
                disturbance[x][z] = 0f;
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

    public float sampleDisturbance(float localX, float localZ) {
        float sx = clamp(localX, 0f, gridSize);
        float sz = clamp(localZ, 0f, gridSize);

        int x0 = (int) Math.floor(sx);
        int z0 = (int) Math.floor(sz);
        int x1 = Math.min(gridSize, x0 + 1);
        int z1 = Math.min(gridSize, z0 + 1);

        float tx = sx - x0;
        float tz = sz - z0;

        float d00 = disturbance[x0][z0];
        float d10 = disturbance[x1][z0];
        float d01 = disturbance[x0][z1];
        float d11 = disturbance[x1][z1];

        float dx0 = lerp(d00, d10, tx);
        float dx1 = lerp(d01, d11, tx);
        return clamp(lerp(dx0, dx1, tz), 0f, 1f);
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

    public float getDepthAtGrid(int x, int z) {
        int gx = Math.min(gridSize, Math.max(0, x));
        int gz = Math.min(gridSize, Math.max(0, z));
        return waterDepth[gx][gz];
    }



    public float sampleDepth(float localX, float localZ) {
        float sx = clamp(localX, 0f, gridSize);
        float sz = clamp(localZ, 0f, gridSize);

        int x0 = (int) Math.floor(sx);
        int z0 = (int) Math.floor(sz);
        int x1 = Math.min(gridSize, x0 + 1);
        int z1 = Math.min(gridSize, z0 + 1);

        float tx = sx - x0;
        float tz = sz - z0;

        float d00 = waterDepth[x0][z0];
        float d10 = waterDepth[x1][z0];
        float d01 = waterDepth[x0][z1];
        float d11 = waterDepth[x1][z1];

        float dx0 = lerp(d00, d10, tx);
        float dx1 = lerp(d01, d11, tx);
        return lerp(dx0, dx1, tz);
    }

    public int getGridSize() {
        return gridSize;
    }

    private static float blendValue(float current, float incoming, float blendFactor) {
        float t = clamp(blendFactor, 0f, 1f);
        return current + (incoming - current) * t;
    }

    public void blendLeftEdgeFrom(WaterSimChunk neighbor, float blendFactor) {
        if (neighbor == null || neighbor.gridSize != gridSize) {
            return;
        }
        for (int z = 0; z <= gridSize; z++) {
            float nDepth = neighbor.waterDepth[gridSize][z];
            float nUx = neighbor.velX[gridSize][z];
            float nUz = neighbor.velZ[gridSize][z];
            waterDepth[0][z] = blendValue(waterDepth[0][z], nDepth, blendFactor);
            velX[0][z] = blendValue(velX[0][z], nUx, blendFactor);
            velZ[0][z] = blendValue(velZ[0][z], nUz, blendFactor);
            disturbance[0][z] = blendValue(disturbance[0][z], neighbor.disturbance[gridSize][z], blendFactor);
        }
    }

    public void blendLeftEdgeFrom(WaterSimChunk neighbor) {
        blendLeftEdgeFrom(neighbor, 1f);
    }

    public void blendRightEdgeFrom(WaterSimChunk neighbor, float blendFactor) {
        if (neighbor == null || neighbor.gridSize != gridSize) {
            return;
        }
        for (int z = 0; z <= gridSize; z++) {
            float nDepth = neighbor.waterDepth[0][z];
            float nUx = neighbor.velX[0][z];
            float nUz = neighbor.velZ[0][z];
            waterDepth[gridSize][z] = blendValue(waterDepth[gridSize][z], nDepth, blendFactor);
            velX[gridSize][z] = blendValue(velX[gridSize][z], nUx, blendFactor);
            velZ[gridSize][z] = blendValue(velZ[gridSize][z], nUz, blendFactor);
            disturbance[gridSize][z] = blendValue(disturbance[gridSize][z], neighbor.disturbance[0][z], blendFactor);
        }
    }

    public void blendRightEdgeFrom(WaterSimChunk neighbor) {
        blendRightEdgeFrom(neighbor, 1f);
    }

    public void blendTopEdgeFrom(WaterSimChunk neighbor, float blendFactor) {
        if (neighbor == null || neighbor.gridSize != gridSize) {
            return;
        }
        for (int x = 0; x <= gridSize; x++) {
            float nDepth = neighbor.waterDepth[x][gridSize];
            float nUx = neighbor.velX[x][gridSize];
            float nUz = neighbor.velZ[x][gridSize];
            waterDepth[x][0] = blendValue(waterDepth[x][0], nDepth, blendFactor);
            velX[x][0] = blendValue(velX[x][0], nUx, blendFactor);
            velZ[x][0] = blendValue(velZ[x][0], nUz, blendFactor);
            disturbance[x][0] = blendValue(disturbance[x][0], neighbor.disturbance[x][gridSize], blendFactor);
        }
    }

    public void blendTopEdgeFrom(WaterSimChunk neighbor) {
        blendTopEdgeFrom(neighbor, 1f);
    }

    public void blendBottomEdgeFrom(WaterSimChunk neighbor, float blendFactor) {
        if (neighbor == null || neighbor.gridSize != gridSize) {
            return;
        }
        for (int x = 0; x <= gridSize; x++) {
            float nDepth = neighbor.waterDepth[x][0];
            float nUx = neighbor.velX[x][0];
            float nUz = neighbor.velZ[x][0];
            waterDepth[x][gridSize] = blendValue(waterDepth[x][gridSize], nDepth, blendFactor);
            velX[x][gridSize] = blendValue(velX[x][gridSize], nUx, blendFactor);
            velZ[x][gridSize] = blendValue(velZ[x][gridSize], nUz, blendFactor);
            disturbance[x][gridSize] = blendValue(disturbance[x][gridSize], neighbor.disturbance[x][0], blendFactor);
        }
    }

    public void blendBottomEdgeFrom(WaterSimChunk neighbor) {
        blendBottomEdgeFrom(neighbor, 1f);
    }


    public void disturb(float localX, float localZ, float radius, float impulse) {
        float cx = clamp(localX, 0f, gridSize);
        float cz = clamp(localZ, 0f, gridSize);
        float r = Math.max(0.25f, radius);
        float strength = impulse;

        int minX = Math.max(0, (int) Math.floor(cx - r - 1f));
        int maxX = Math.min(gridSize, (int) Math.ceil(cx + r + 1f));
        int minZ = Math.max(0, (int) Math.floor(cz - r - 1f));
        int maxZ = Math.min(gridSize, (int) Math.ceil(cz + r + 1f));

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                float dx = x - cx;
                float dz = z - cz;
                float dist = (float) Math.sqrt(dx * dx + dz * dz);
                if (dist > r) {
                    continue;
                }

                float falloff = 1f - (dist / r);
                float pulse = falloff * falloff * (3f - 2f * falloff);

                float bed = bedHeight[x][z];
                float maxDepth = Math.max(0f, maxSurface[x][z] - bed);
                if (maxDepth <= 0.0001f) {
                    continue;
                }

                float depth = waterDepth[x][z];
                float pressure = clamp(depth / maxDepth, 0f, 1f);
                float response = 0.35f + pressure * 0.65f;

                float radialX = dist > 0.0001f ? dx / dist : 0f;
                float radialZ = dist > 0.0001f ? dz / dist : 0f;

                float velKick = strength * pulse * response;
                velX[x][z] += radialX * velKick;
                velZ[x][z] += radialZ * velKick;

                float depthKick = Math.abs(strength) * pulse * 0.02f * response;
                waterDepth[x][z] = clamp(depth + depthKick, 0f, maxDepth);
                disturbance[x][z] = clamp(disturbance[x][z] + Math.abs(strength) * pulse * 0.45f, 0f, 1f);
            }
        }
    }

    public void stepSimulation(float dt) {
        float clampedDt = clamp(dt, 1.0f / 240.0f, 1.0f / 20.0f);
        float frameScale = clampedDt * 60.0f;
        float gravity = 0.020f * frameScale;
        float damping = (float) Math.pow(0.92f, frameScale);
        float pressureBlend = 0.24f * frameScale;
        float diffusion = 0.12f * frameScale;
        float disturbanceDecay = (float) Math.pow(0.84f, frameScale);
        float disturbanceDiffusion = 0.10f * frameScale;

        // Pass 1: terrain-aware velocity update.
        for (int x = 0; x <= gridSize; x++) {
            for (int z = 0; z <= gridSize; z++) {
                int xl = Math.max(0, x - 1);
                int xr = Math.min(gridSize, x + 1);
                int zb = Math.max(0, z - 1);
                int zf = Math.min(gridSize, z + 1);

                float eta = bedHeight[x][z] + waterDepth[x][z];
                float etaL = bedHeight[xl][z] + waterDepth[xl][z];
                float etaR = bedHeight[xr][z] + waterDepth[xr][z];
                float etaB = bedHeight[x][zb] + waterDepth[x][zb];
                float etaF = bedHeight[x][zf] + waterDepth[x][zf];

                float gradX = (etaR - etaL) * 0.5f;
                float gradZ = (etaF - etaB) * 0.5f;

                float nextUx = (velX[x][z] - gradX * gravity) * damping;
                float nextUz = (velZ[x][z] - gradZ * gravity) * damping;

                // Keep tiny puddles calm and avoid jitter when near dry terrain.
                float wetness = clamp(waterDepth[x][z] / 0.22f, 0f, 1f);
                nextUx *= wetness;
                nextUz *= wetness;

                tmpVelX[x][z] = nextUx;
                tmpVelZ[x][z] = nextUz;
            }
        }

        // Pass 2: conservative depth transfer following velocity direction.
        for (int x = 0; x <= gridSize; x++) {
            for (int z = 0; z <= gridSize; z++) {
                int xl = Math.max(0, x - 1);
                int xr = Math.min(gridSize, x + 1);
                int zb = Math.max(0, z - 1);
                int zf = Math.min(gridSize, z + 1);

                float centerDepth = waterDepth[x][z];
                float avgDepth = (waterDepth[xl][z] + waterDepth[xr][z] + waterDepth[x][zb] + waterDepth[x][zf]) * 0.25f;

                float outflowX = tmpVelX[x][z] * clampedDt;
                float outflowZ = tmpVelZ[x][z] * clampedDt;
                float moveX = Math.min(centerDepth * 0.35f, Math.abs(outflowX) * centerDepth * 0.85f);
                float moveZ = Math.min(centerDepth * 0.35f, Math.abs(outflowZ) * centerDepth * 0.85f);

                float incomingX = outflowX >= 0f ? Math.max(0f, tmpVelX[xl][z]) : Math.max(0f, -tmpVelX[xr][z]);
                float incomingZ = outflowZ >= 0f ? Math.max(0f, tmpVelZ[x][zb]) : Math.max(0f, -tmpVelZ[x][zf]);
                float inflow = (incomingX + incomingZ) * clampedDt * 0.35f;

                float depth = centerDepth - moveX - moveZ + inflow;
                depth += (avgDepth - depth) * diffusion;

                float bed = bedHeight[x][z];
                float maxDepth = Math.max(0f, maxSurface[x][z] - bed);
                float pressureDepth = maxDepth;
                depth += (pressureDepth - depth) * pressureBlend;

                tmpDepth[x][z] = clamp(depth, 0f, maxDepth);

                float centerDisturbance = disturbance[x][z];
                float avgDisturbance = (disturbance[xl][z] + disturbance[xr][z] + disturbance[x][zb] + disturbance[x][zf]) * 0.25f;
                float nextDisturbance = centerDisturbance * disturbanceDecay;
                nextDisturbance += (avgDisturbance - centerDisturbance) * disturbanceDiffusion;
                tmpDisturbance[x][z] = clamp(nextDisturbance, 0f, 1f);
            }
        }

        // Pass 3: copy back and damp velocity where blocked by terrain ceilings.
        for (int x = 0; x <= gridSize; x++) {
            for (int z = 0; z <= gridSize; z++) {
                waterDepth[x][z] = tmpDepth[x][z];

                float bed = bedHeight[x][z];
                float maxDepth = Math.max(0f, maxSurface[x][z] - bed);
                float pressure = maxDepth > 0.0001f ? (tmpDepth[x][z] / maxDepth) : 0f;
                float terrainCollisionDamp = 0.40f + 0.60f * clamp(pressure, 0f, 1f);

                velX[x][z] = tmpVelX[x][z] * terrainCollisionDamp;
                velZ[x][z] = tmpVelZ[x][z] * terrainCollisionDamp;
                disturbance[x][z] = tmpDisturbance[x][z];
            }
        }
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
