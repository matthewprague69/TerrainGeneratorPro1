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
    private final float[][] foam;
    private final float[][] tmpDepth;
    private final float[][] tmpDepthSpread;
    private final float[][] tmpVelX;
    private final float[][] tmpVelZ;
    private final float[][] tmpFoam;
    private final float[][] prevEta;
    private final float[][] waveMemory;
    private final float[][] tmpWaveMemory;

    private static final float SHORE_RUNUP_MAX = 1.35f;
    private static final float SHORE_RUNUP_SLOPE = 1.50f;
    private static final float SHORE_RETENTION_MAX = 0.60f;
    private static final float SHORE_RUNUP_RISE_LIMIT = 2.4f;

    public WaterSimChunk(int gridSize) {
        this.gridSize = gridSize;
        this.bedHeight = new float[gridSize + 1][gridSize + 1];
        this.waterDepth = new float[gridSize + 1][gridSize + 1];
        this.maxSurface = new float[gridSize + 1][gridSize + 1];
        this.velX = new float[gridSize + 1][gridSize + 1];
        this.velZ = new float[gridSize + 1][gridSize + 1];
        this.foam = new float[gridSize + 1][gridSize + 1];
        this.tmpDepth = new float[gridSize + 1][gridSize + 1];
        this.tmpDepthSpread = new float[gridSize + 1][gridSize + 1];
        this.tmpVelX = new float[gridSize + 1][gridSize + 1];
        this.tmpVelZ = new float[gridSize + 1][gridSize + 1];
        this.tmpFoam = new float[gridSize + 1][gridSize + 1];
        this.prevEta = new float[gridSize + 1][gridSize + 1];
        this.waveMemory = new float[gridSize + 1][gridSize + 1];
        this.tmpWaveMemory = new float[gridSize + 1][gridSize + 1];
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
                foam[x][z] = 0f;
                prevEta[x][z] = bed + waterDepth[x][z];
                waveMemory[x][z] = 0f;
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

    public float sampleFoam(float localX, float localZ) {
        float sx = clamp(localX, 0f, gridSize);
        float sz = clamp(localZ, 0f, gridSize);

        int x0 = (int) Math.floor(sx);
        int z0 = (int) Math.floor(sz);
        int x1 = Math.min(gridSize, x0 + 1);
        int z1 = Math.min(gridSize, z0 + 1);

        float tx = sx - x0;
        float tz = sz - z0;

        float f00 = foam[x0][z0];
        float f10 = foam[x1][z0];
        float f01 = foam[x0][z1];
        float f11 = foam[x1][z1];

        float fx0 = lerp(f00, f10, tx);
        float fx1 = lerp(f01, f11, tx);
        return lerp(fx0, fx1, tz);
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

    public void blendLeftEdgeFrom(WaterSimChunk neighbor) {
        if (neighbor == null || neighbor.gridSize != gridSize) {
            return;
        }
        for (int z = 0; z <= gridSize; z++) {
            float nDepth = neighbor.waterDepth[gridSize][z];
            float nUx = neighbor.velX[gridSize][z];
            float nUz = neighbor.velZ[gridSize][z];
            waterDepth[0][z] = nDepth;
            velX[0][z] = nUx;
            velZ[0][z] = nUz;
            foam[0][z] = neighbor.foam[gridSize][z];
        }
    }

    public void blendRightEdgeFrom(WaterSimChunk neighbor) {
        if (neighbor == null || neighbor.gridSize != gridSize) {
            return;
        }
        for (int z = 0; z <= gridSize; z++) {
            float nDepth = neighbor.waterDepth[0][z];
            float nUx = neighbor.velX[0][z];
            float nUz = neighbor.velZ[0][z];
            waterDepth[gridSize][z] = nDepth;
            velX[gridSize][z] = nUx;
            velZ[gridSize][z] = nUz;
            foam[gridSize][z] = neighbor.foam[0][z];
        }
    }

    public void blendTopEdgeFrom(WaterSimChunk neighbor) {
        if (neighbor == null || neighbor.gridSize != gridSize) {
            return;
        }
        for (int x = 0; x <= gridSize; x++) {
            float nDepth = neighbor.waterDepth[x][gridSize];
            float nUx = neighbor.velX[x][gridSize];
            float nUz = neighbor.velZ[x][gridSize];
            waterDepth[x][0] = nDepth;
            velX[x][0] = nUx;
            velZ[x][0] = nUz;
            foam[x][0] = neighbor.foam[x][gridSize];
        }
    }

    public void blendBottomEdgeFrom(WaterSimChunk neighbor) {
        if (neighbor == null || neighbor.gridSize != gridSize) {
            return;
        }
        for (int x = 0; x <= gridSize; x++) {
            float nDepth = neighbor.waterDepth[x][0];
            float nUx = neighbor.velX[x][0];
            float nUz = neighbor.velZ[x][0];
            waterDepth[x][gridSize] = nDepth;
            velX[x][gridSize] = nUx;
            velZ[x][gridSize] = nUz;
            foam[x][gridSize] = neighbor.foam[x][0];
        }
    }

    public void stepSimulation(float dt) {
        float clampedDt = clamp(dt, 1.0f / 240.0f, 1.0f / 20.0f);
        float frameScale = clampedDt * 60.0f;
        float gravity = 0.020f * frameScale;
        float damping = (float) Math.pow(0.92f, frameScale);
        float pressureBlend = 0.14f * frameScale;
        float diffusion = 0.12f * frameScale;

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

                float staticMaxDepth = Math.max(0f, maxSurface[x][z] - bedHeight[x][z]);
                boolean terrainFilm = staticMaxDepth < 0.01f && waterDepth[x][z] > 0.01f;
                if (terrainFilm) {
                    float bedGradX = (bedHeight[xr][z] - bedHeight[xl][z]) * 0.5f;
                    float bedGradZ = (bedHeight[x][zf] - bedHeight[x][zb]) * 0.5f;
                    // Backwash: when surge climbs onto dry terrain, push it downhill to rejoin water mass.
                    nextUx -= bedGradX * gravity * 1.9f;
                    nextUz -= bedGradZ * gravity * 1.9f;
                    nextUx *= 0.90f;
                    nextUz *= 0.90f;
                }

                boolean shorelineBackwash = staticMaxDepth < 0.06f && waterDepth[x][z] > 0.02f;
                if (shorelineBackwash) {
                    float bedGradX = (bedHeight[xr][z] - bedHeight[xl][z]) * 0.5f;
                    float bedGradZ = (bedHeight[x][zf] - bedHeight[x][zb]) * 0.5f;
                    float slide = clamp(waterDepth[x][z] / 0.35f, 0f, 1f);
                    nextUx -= bedGradX * gravity * (2.2f + slide * 0.8f);
                    nextUz -= bedGradZ * gravity * (2.2f + slide * 0.8f);
                }

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
                float staticMaxDepth = Math.max(0f, maxSurface[x][z] - bed);
                float inlandRise = Math.max(0f, bed - maxSurface[x][z]);
                float inlandAttenuation = 1f - clamp(inlandRise / SHORE_RUNUP_RISE_LIMIT, 0f, 1f);

                float etaL = bedHeight[xl][z] + waterDepth[xl][z];
                float etaR = bedHeight[xr][z] + waterDepth[xr][z];
                float etaB = bedHeight[x][zb] + waterDepth[x][zb];
                float etaF = bedHeight[x][zf] + waterDepth[x][zf];
                float neighborEta = Math.max(Math.max(etaL, etaR), Math.max(etaB, etaF));
                float etaNow = bed + depth;
                float arrival = Math.max(0f, etaNow - prevEta[x][z]);
                float directionalArrival = Math.max(0f, neighborEta - etaNow);
                float wavePulse = clamp(arrival * 2.8f + directionalArrival * 1.2f, 0f, 1f);
                float waveCarry = Math.max(waveMemory[x][z] * (float) Math.pow(0.90f, frameScale), wavePulse);
                tmpWaveMemory[x][z] = waveCarry;

                // Allow controlled shoreline run-up so waves can reach terrain edges,
                // while still preventing bulk flooding into higher dry land.
                float runupAllowance = SHORE_RUNUP_MAX * clamp((neighborEta - bed) / SHORE_RUNUP_SLOPE, 0f, 1f);
                float velocityMagPre = (float) Math.sqrt(tmpVelX[x][z] * tmpVelX[x][z] + tmpVelZ[x][z] * tmpVelZ[x][z]);
                float momentumRunup = clamp((velocityMagPre - 0.08f) / 0.30f, 0f, 1f)
                        * (0.75f + centerDepth * 0.65f)
                        * (0.72f + waveCarry * 0.95f);
                runupAllowance *= inlandAttenuation;
                momentumRunup *= inlandAttenuation;
                float dynamicSurfaceCap = Math.max(maxSurface[x][z], neighborEta - 0.02f) + runupAllowance + momentumRunup;
                float dynamicMaxDepth = Math.max(staticMaxDepth, dynamicSurfaceCap - bed);

                // Don't yank shoreline water back to static depth too aggressively; let momentum carry it inland.
                float pressureDepth = Math.max(staticMaxDepth, centerDepth * 0.92f);
                boolean shorelineFilm = staticMaxDepth < 0.04f;
                float shorelinePressureBlend = shorelineFilm ? pressureBlend * 0.14f : pressureBlend * 0.55f;
                depth += (pressureDepth - depth) * shorelinePressureBlend;

                // Very soft overflow region: only treat as collision when far beyond the dynamic cap.
                float softCap = dynamicMaxDepth + 0.55f + centerDepth * 0.45f + waveCarry * 0.40f;
                float collisionOverflow = Math.max(0f, depth - softCap);
                if (collisionOverflow > 0f) {
                    float bedGradX = (bedHeight[xr][z] - bedHeight[xl][z]) * 0.5f;
                    float bedGradZ = (bedHeight[x][zf] - bedHeight[x][zb]) * 0.5f;
                    float normalX = -bedGradX;
                    float normalZ = -bedGradZ;
                    float nLen = (float) Math.sqrt(normalX * normalX + normalZ * normalZ);
                    if (nLen > 0.0001f) {
                        normalX /= nLen;
                        normalZ /= nLen;
                        float inDot = tmpVelX[x][z] * normalX + tmpVelZ[x][z] * normalZ;
                        if (inDot > 0f) {
                            // Convert impact into a softer bounce + tangential run so wave mass spreads along shore.
                            float tangentX = -normalZ;
                            float tangentZ = normalX;
                            float tangentDot = tmpVelX[x][z] * tangentX + tmpVelZ[x][z] * tangentZ;
                            tmpVelX[x][z] -= 0.72f * inDot * normalX;
                            tmpVelZ[x][z] -= 0.72f * inDot * normalZ;
                            tmpVelX[x][z] += tangentX * Math.abs(tangentDot) * 0.26f;
                            tmpVelZ[x][z] += tangentZ * Math.abs(tangentDot) * 0.26f;
                        }
                    }
                }

                // Keep a thin moving shoreline film instead of hard-clipping all overflow away.
                float retainedOverflow = shorelineFilm
                        ? Math.min(SHORE_RETENTION_MAX, collisionOverflow * 0.90f)
                        : Math.min(0.40f, collisionOverflow * 0.55f);
                retainedOverflow += waveCarry * (shorelineFilm ? 0.06f : 0.03f);
                retainedOverflow *= (0.42f + 0.58f * inlandAttenuation);
                float postCollisionDepth = depth - collisionOverflow + retainedOverflow;

                // Shore fade-out: run-up should die off inland after a few meters instead of flowing forever.
                float inlandFade = clamp((inlandRise - 0.35f) / (SHORE_RUNUP_RISE_LIMIT - 0.35f), 0f, 1f);
                postCollisionDepth *= 1f - inlandFade * (0.35f + 0.55f * frameScale);

                // Inland drainage: thin run-up water should naturally drain back unless another wave reinforces it.
                float inlandDrain = inlandFade * (0.035f + postCollisionDepth * 0.24f) * frameScale;
                inlandDrain *= (1.10f - 0.55f * waveCarry);
                postCollisionDepth = Math.max(0f, postCollisionDepth - inlandDrain);

                float maxRise = (0.24f + centerDepth * 0.30f) * frameScale;
                float maxDrop = (0.26f + centerDepth * 0.38f) * frameScale;
                float smoothedDepth = clamp(postCollisionDepth, centerDepth - maxDrop, centerDepth + maxRise);

                float velocityMag = (float) Math.sqrt(tmpVelX[x][z] * tmpVelX[x][z] + tmpVelZ[x][z] * tmpVelZ[x][z]);
                float impactFoam = clamp(collisionOverflow / 0.32f, 0f, 1f) * (0.65f + waveCarry * 0.55f);
                float speedFoam = clamp((velocityMag - 0.10f) / 0.35f, 0f, 1f);
                float retainedFoam = foam[x][z] * (float) Math.pow(0.962f, frameScale);
                float advectionFoam = (foam[xl][z] + foam[xr][z] + foam[x][zb] + foam[x][zf]) * 0.25f;
                tmpFoam[x][z] = clamp(Math.max(retainedFoam, advectionFoam * 0.70f) + impactFoam * 0.85f + speedFoam * 0.08f, 0f, 1f);

                tmpDepth[x][z] = Math.max(0f, smoothedDepth);
            }
        }

        // Pass 2.5: spread thin shoreline sheets in flow direction so run-up looks liquid, not vertical spikes.
        for (int x = 0; x <= gridSize; x++) {
            for (int z = 0; z <= gridSize; z++) {
                int xl = Math.max(0, x - 1);
                int xr = Math.min(gridSize, x + 1);
                int zb = Math.max(0, z - 1);
                int zf = Math.min(gridSize, z + 1);

                float center = tmpDepth[x][z];
                float neighbors = (tmpDepth[xl][z] + tmpDepth[xr][z] + tmpDepth[x][zb] + tmpDepth[x][zf]) * 0.25f;
                float flow = (float) Math.sqrt(tmpVelX[x][z] * tmpVelX[x][z] + tmpVelZ[x][z] * tmpVelZ[x][z]);
                float shoreline = clamp((0.20f - Math.max(0f, maxSurface[x][z] - bedHeight[x][z])) / 0.20f, 0f, 1f);
                float spread = clamp((flow - 0.06f) / 0.45f, 0f, 1f) * shoreline;
                spread *= 0.70f + tmpWaveMemory[x][z] * 0.90f;
                float inlandRise = Math.max(0f, bedHeight[x][z] - maxSurface[x][z]);
                float inlandAttenuation = 1f - clamp(inlandRise / SHORE_RUNUP_RISE_LIMIT, 0f, 1f);
                spread *= inlandAttenuation;
                tmpDepthSpread[x][z] = center + (neighbors - center) * (0.10f + spread * 0.34f);
            }
        }

        // Pass 3: copy back and damp velocity where blocked by terrain ceilings.
        for (int x = 0; x <= gridSize; x++) {
            for (int z = 0; z <= gridSize; z++) {
                waterDepth[x][z] = Math.max(0f, tmpDepthSpread[x][z]);

                float bed = bedHeight[x][z];
                float maxDepth = Math.max(0f, maxSurface[x][z] - bed);
                float pressure = maxDepth > 0.0001f ? (tmpDepth[x][z] / maxDepth) : clamp(tmpDepth[x][z] / 0.18f, 0f, 1f);
                float terrainCollisionDamp = 0.55f + 0.45f * clamp(pressure, 0f, 1f);

                float inlandRise = Math.max(0f, bedHeight[x][z] - maxSurface[x][z]);
                float inlandFade = clamp((inlandRise - 0.25f) / (SHORE_RUNUP_RISE_LIMIT - 0.25f), 0f, 1f);
                if (inlandFade > 0f) {
                    int xl = Math.max(0, x - 1);
                    int xr = Math.min(gridSize, x + 1);
                    int zb = Math.max(0, z - 1);
                    int zf = Math.min(gridSize, z + 1);
                    float bedGradX = (bedHeight[xr][z] - bedHeight[xl][z]) * 0.5f;
                    float bedGradZ = (bedHeight[x][zf] - bedHeight[x][zb]) * 0.5f;
                    // Backwash acceleration so run-up returns toward shoreline instead of lingering inland.
                    tmpVelX[x][z] -= bedGradX * inlandFade * 0.75f;
                    tmpVelZ[x][z] -= bedGradZ * inlandFade * 0.75f;
                    terrainCollisionDamp *= (1f - inlandFade * 0.35f);
                }

                velX[x][z] = tmpVelX[x][z] * terrainCollisionDamp;
                velZ[x][z] = tmpVelZ[x][z] * terrainCollisionDamp;
                foam[x][z] = tmpFoam[x][z];
                waveMemory[x][z] = tmpWaveMemory[x][z];
                prevEta[x][z] = bedHeight[x][z] + waterDepth[x][z];
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
