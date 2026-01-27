package game;
import generators.LakeGenerator;
import objects.BatchableFeature;
import objects.Feature;
import objects.Flower;
import objects.Grass;
import objects.Lake;
import spawners.FeatureSpawner;
import spawners.LakeSpawner;
import util.BoundingBox;
import util.FeatureUtil;
import util.VertexBatchBuilder;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import java.util.*;

public class Chunk {
    public static final int SIZE = 30;
    public final int cx, cz;
    private final OpenSimplexNoise terrainNoise;
    private final float scale;
    private final TerrainManager manager;
    private final Biome biome;
    private final int lod;

    private final float[][] heights = new float[SIZE + 1][SIZE + 1];
    private final List<Feature> features = new ArrayList<>();
    private final boolean[][] featureMask = new boolean[SIZE][SIZE];
    private final boolean[][] lakeMask = new boolean[SIZE][SIZE];
    private boolean featuresGenerated = false;
    private final List<TerrainBatch> terrainBatches = new ArrayList<>();
    private int waterDisplayList = -1;
    private int grassBatchVbo = -1;
    private int grassBatchVertexCount = 0;
    private int grassBatchTexture = 0;
    public static final float WATER_LEVEL = 4.0f;
    public static final float WATER_SURROUNDING_LEVEL = 5.5f;
    public static final float ABSOLUTE_WATER_BOTTOM_HEIGHT = 1.0f;

    private static final float DIRT_SLOPE_START = 0.65f;
    private static final float ROCK_SLOPE_START = 1.0f;


    private static final float SNOW_HEIGHT_START = 55f;
    private static final float SNOW_HEIGHT_FULL = 60f;

    private static final float FEATURE_MIN_HEIGHT = WATER_SURROUNDING_LEVEL;
    private static final float FEATURE_MAX_HEIGHT = SNOW_HEIGHT_START;
    public static final float FEATURE_SLOPE_SPAWN_THRESHOLD = DIRT_SLOPE_START;
    public static final float FEATURE_TREE_MAX_HEIGHT = 25f; // example, you can adjust






    public Chunk(int cx, int cz, OpenSimplexNoise terrainNoise, float scale, Biome biome, TerrainManager manager,
            boolean generateFeatures, int lod) {
        this.cx = cx;
        this.cz = cz;
        this.terrainNoise = terrainNoise;
        this.scale = scale;
        this.manager = manager;
        this.biome = biome;
        this.lod = lod;
        generate(generateFeatures);
    }

    private void generate(boolean generateFeatures) {
        final int OCTAVES = 4;
        final double PERSISTENCE = 0.35;
        final double macroFreq = 0.002;
        final double macroAmp = 2.0; // Lower to reduce elevation distortion

        for (int x = 0; x <= SIZE; x++) {
            for (int z = 0; z <= SIZE; z++) {
                double wx = (cx * SIZE + x) * scale;
                double wz = (cz * SIZE + z) * scale;

                Map<Biome, Float> weights = manager.getBiomeWeights(wx, wz);
                double blendedHeight = 0;

                for (Map.Entry<Biome, Float> entry : weights.entrySet()) {
                    Biome biome = entry.getKey();
                    float weight = entry.getValue();

                    double freq = biome.frequency;
                    double amp = biome.amplitude * 0.5; // Reduce noise contribution
                    double sum = 0;

                    for (int o = 0; o < OCTAVES; o++) {
                        double offset = o * 100.0;
                        double val = terrainNoise.eval((wx + offset) * freq, (wz - offset) * freq);
                        val = (val * val * val) * 1.2;
                        sum += val * amp;
                        freq *= 1.7;
                        amp *= PERSISTENCE;
                    }

                    double elevationOffset = terrainNoise.eval(wx * macroFreq, wz * macroFreq) * macroAmp;

                    // Biome shaping: baseHeight is now the dominant vertical shift
                    double biomeHeight = biome.baseHeight + sum + elevationOffset;
                    blendedHeight += biomeHeight * weight;
                }

                heights[x][z] = (float) blendedHeight;
            }
        }


        LakeGenerator.generateLakes(this, biome, heights, featureMask, features, manager);
        if (generateFeatures)
            generateFeatures();
        stitchEdges();
        buildTerrainBuffers();
        buildWaterDisplayList();
        buildGrassBatch();

    }

    public void drawWater() {
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDisable(GL_TEXTURE_2D);

        glDepthMask(false); // Disable depth writing for transparency

        glEnable(GL_FOG); // <<< ADD THIS
        glColor4f(0.2f, 0.5f, 0.8f, 0.6f); // Water color (already fine)

        if (waterDisplayList != -1) {
            glCallList(waterDisplayList);
        }

        glDepthMask(true); // Re-enable depth writing

        glDisable(GL_FOG); // <<< Disable again after drawing water
        glEnable(GL_TEXTURE_2D);
        glDisable(GL_BLEND);
    }




    public void generateFeaturesIfNeeded(int pcx, int pcz, int featureRenderDist) {
        if (featuresGenerated)
            return;

        if (Math.abs(cx - pcx) > featureRenderDist || Math.abs(cz - pcz) > featureRenderDist)
            return;

        generateFeatures();
        featuresGenerated = true;
    }

    public Biome getBiomeType() {
        return biome;
    }

    public void stitchEdges() {
        Chunk left = manager.getChunk(cx - 1, cz);
        if (left != null) {
            for (int z = 0; z <= SIZE; z++) {
                heights[0][z] = left.heights[SIZE][z];
            }
        }

        Chunk top = manager.getChunk(cx, cz - 1);
        if (top != null) {
            for (int x = 0; x <= SIZE; x++) {
                heights[x][0] = top.heights[x][SIZE];
            }
        }

        Chunk topLeft = manager.getChunk(cx - 1, cz - 1);
        if (topLeft != null) {
            heights[0][0] = topLeft.heights[SIZE][SIZE];
        }
    }

    public void drawTerrainAndFeatures(int chunkDistance, int featureDetailDistance, int grassDetailDistance) {
        glEnable(GL_TEXTURE_2D);
        glColor3f(1f, 1f, 1f);

        renderTerrainBuffers();
        if (chunkDistance <= grassDetailDistance) {
            renderGrassBatch();
        }

        glDisable(GL_TEXTURE_2D);

        boolean simplified = chunkDistance > featureDetailDistance;
        // Draw features if they are above water
        for (Feature f : features) {
            if (f instanceof Grass) {
                continue;
            }
            if (simplified && f instanceof Flower) {
                continue;
            }
            if (f.y >= WATER_LEVEL) {
                if (simplified) {
                    f.drawSimplified();
                } else {
                    f.draw();
                }
            }
        }
    }



    private void buildWaterDisplayList() {
        waterDisplayList = glGenLists(1);
        if (waterDisplayList != -1) {
            glNewList(waterDisplayList, GL_COMPILE);
            buildWaterGeometry();
            glEndList();
        }
    }

    private void buildTerrainBuffers() {
        disposeTerrainBuffers();
        Map<Integer, FloatBuilder> builders = new HashMap<>();
        float texScale = 0.2f;
        int step = (int) Math.pow(2, lod);
        for (int z = 0; z < SIZE; z += step) {
            for (int x = 0; x < SIZE; x += step) {
                if (x + step > SIZE || z + step > SIZE)
                    continue;

                float y00 = heights[x][z];
                float y10 = heights[x + step][z];
                float y01 = heights[x][z + step];
                float y11 = heights[x + step][z + step];

                addTriangle(builders, x, z, x + step, z, x, z + step, y00, y10, y01, texScale);
                addTriangle(builders, x + step, z, x + step, z + step, x, z + step, y10, y11, y01, texScale);
            }
        }

        for (Map.Entry<Integer, FloatBuilder> entry : builders.entrySet()) {
            FloatBuilder builder = entry.getValue();
            if (builder.size == 0) {
                continue;
            }
            int vboId = glGenBuffers();
            glBindBuffer(GL_ARRAY_BUFFER, vboId);
            glBufferData(GL_ARRAY_BUFFER, builder.toBuffer(), GL_STATIC_DRAW);
            glBindBuffer(GL_ARRAY_BUFFER, 0);
            terrainBatches.add(new TerrainBatch(entry.getKey(), vboId, builder.size / STRIDE_FLOATS));
        }
    }

    private void buildWaterGeometry() {
        int step = (int) Math.pow(2, lod);

        for (int z = 0; z < SIZE; z += step) {
            for (int x = 0; x < SIZE; x += step) {
                if (x + step > SIZE || z + step > SIZE)
                    continue;

                float y00 = heights[x][z];
                float y10 = heights[x + step][z];
                float y01 = heights[x][z + step];
                float y11 = heights[x + step][z + step];

                boolean needsWater =
                        y00 < WATER_LEVEL || y10 < WATER_LEVEL || y01 < WATER_LEVEL || y11 < WATER_LEVEL;

                if (needsWater) {
                    float wx1 = (cx * SIZE + x) * scale;
                    float wz1 = (cz * SIZE + z) * scale;
                    float wx2 = (cx * SIZE + x + step) * scale;
                    float wz2 = (cz * SIZE + z + step) * scale;

                    glBegin(GL_QUADS);
                    glNormal3f(0f, 1f, 0f);
                    glVertex3f(wx1, WATER_LEVEL, wz1);
                    glVertex3f(wx2, WATER_LEVEL, wz1);
                    glVertex3f(wx2, WATER_LEVEL, wz2);
                    glVertex3f(wx1, WATER_LEVEL, wz2);
                    glEnd();
                }
            }
        }
    }

    private void buildGrassBatch() {
        disposeGrassBatch();
        VertexBatchBuilder builder = new VertexBatchBuilder();
        int texture = 0;

        for (Feature feature : features) {
            if (feature instanceof BatchableFeature) {
                BatchableFeature batchable = (BatchableFeature) feature;
                batchable.appendToBatch(builder);
                texture = batchable.getBatchTextureId();
            }
        }

        int vertexCount = builder.getVertexCount();
        if (vertexCount == 0) {
            return;
        }

        int vboId = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vboId);
        glBufferData(GL_ARRAY_BUFFER, builder.toBuffer(), GL_STATIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        grassBatchVbo = vboId;
        grassBatchVertexCount = vertexCount;
        grassBatchTexture = texture;
    }

    private void renderGrassBatch() {
        if (grassBatchVbo == -1 || grassBatchVertexCount == 0) {
            return;
        }

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glEnable(GL_ALPHA_TEST);
        glAlphaFunc(GL_GREATER, 0.3f);
        glBindTexture(GL_TEXTURE_2D, grassBatchTexture);
        glBindBuffer(GL_ARRAY_BUFFER, grassBatchVbo);
        glEnableClientState(GL_VERTEX_ARRAY);
        glEnableClientState(GL_TEXTURE_COORD_ARRAY);
        glVertexPointer(3, GL_FLOAT, 5 * Float.BYTES, 0);
        glTexCoordPointer(2, GL_FLOAT, 5 * Float.BYTES, 3 * Float.BYTES);
        glDrawArrays(GL_QUADS, 0, grassBatchVertexCount);
        glDisableClientState(GL_TEXTURE_COORD_ARRAY);
        glDisableClientState(GL_VERTEX_ARRAY);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glDisable(GL_ALPHA_TEST);
        glDisable(GL_BLEND);
    }

    private void renderGrassBatchDepth() {
        if (grassBatchVbo == -1 || grassBatchVertexCount == 0) {
            return;
        }

        glBindBuffer(GL_ARRAY_BUFFER, grassBatchVbo);
        glEnableClientState(GL_VERTEX_ARRAY);
        glVertexPointer(3, GL_FLOAT, 5 * Float.BYTES, 0);
        glDrawArrays(GL_QUADS, 0, grassBatchVertexCount);
        glDisableClientState(GL_VERTEX_ARRAY);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    private void renderTerrainBuffers() {
        if (terrainBatches.isEmpty()) {
            return;
        }

        glEnableClientState(GL_VERTEX_ARRAY);
        glEnableClientState(GL_NORMAL_ARRAY);
        glEnableClientState(GL_TEXTURE_COORD_ARRAY);

        int strideBytes = STRIDE_FLOATS * Float.BYTES;
        for (TerrainBatch batch : terrainBatches) {
            glBindTexture(GL_TEXTURE_2D, batch.textureId);
            glBindBuffer(GL_ARRAY_BUFFER, batch.vboId);
            glVertexPointer(3, GL_FLOAT, strideBytes, 0);
            glNormalPointer(GL_FLOAT, strideBytes, 3 * Float.BYTES);
            glTexCoordPointer(2, GL_FLOAT, strideBytes, 6 * Float.BYTES);
            glDrawArrays(GL_TRIANGLES, 0, batch.vertexCount);
        }

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glDisableClientState(GL_TEXTURE_COORD_ARRAY);
        glDisableClientState(GL_NORMAL_ARRAY);
        glDisableClientState(GL_VERTEX_ARRAY);
    }

    public void renderDepth(int chunkDistance, int featureDetailDistance, int grassDetailDistance) {
        if (terrainBatches.isEmpty()) {
            return;
        }

        glEnableClientState(GL_VERTEX_ARRAY);
        int strideBytes = STRIDE_FLOATS * Float.BYTES;

        for (TerrainBatch batch : terrainBatches) {
            glBindBuffer(GL_ARRAY_BUFFER, batch.vboId);
            glVertexPointer(3, GL_FLOAT, strideBytes, 0);
            glDrawArrays(GL_TRIANGLES, 0, batch.vertexCount);
        }

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glDisableClientState(GL_VERTEX_ARRAY);

        if (chunkDistance <= grassDetailDistance) {
            renderGrassBatchDepth();
        }

        boolean simplified = chunkDistance > featureDetailDistance;
        for (Feature f : features) {
            if (f instanceof Grass) {
                continue;
            }
            if (simplified && f instanceof Flower) {
                continue;
            }
            if (f.y >= WATER_LEVEL) {
                if (simplified) {
                    f.drawSimplified();
                } else {
                    f.drawDepth();
                }
            }
        }
    }

    private void addTriangle(Map<Integer, FloatBuilder> builders, int x1, int z1, int x2, int z2, int x3, int z3,
            float y1, float y2, float y3, float texScale) {
        float slope = (computeSlope(x1, z1) + computeSlope(x2, z2) + computeSlope(x3, z3)) / 3f;
        float height = Math.max(y1, Math.max(y2, y3));
        int tex = pickTexture(height, slope);

        FloatBuilder builder = builders.computeIfAbsent(tex, key -> new FloatBuilder());
        float[] normal = computeNormal(x1, z1, x2, z2, x3, z3);

        float wx1 = (cx * SIZE + x1) * scale;
        float wz1 = (cz * SIZE + z1) * scale;
        float wx2 = (cx * SIZE + x2) * scale;
        float wz2 = (cz * SIZE + z2) * scale;
        float wx3 = (cx * SIZE + x3) * scale;
        float wz3 = (cz * SIZE + z3) * scale;

        builder.putVertex(wx1, y1, wz1, normal, x1 * texScale, z1 * texScale);
        builder.putVertex(wx2, y2, wz2, normal, x2 * texScale, z2 * texScale);
        builder.putVertex(wx3, y3, wz3, normal, x3 * texScale, z3 * texScale);
    }

    private void drawTriangle(int x1, int z1, int x2, int z2, int x3, int z3, float y1, float y2, float y3,
            float texScale) {
        float slope = (computeSlope(x1, z1) + computeSlope(x2, z2) + computeSlope(x3, z3)) / 3f;
        float height = Math.max(y1, Math.max(y2, y3));
        int tex = pickTexture(height, slope);

        float wx1 = (cx * SIZE + x1) * scale;
        float wz1 = (cz * SIZE + z1) * scale;
        float wx2 = (cx * SIZE + x2) * scale;
        float wz2 = (cz * SIZE + z2) * scale;
        float wx3 = (cx * SIZE + x3) * scale;
        float wz3 = (cz * SIZE + z3) * scale;

        glBindTexture(GL_TEXTURE_2D, tex);
        glBegin(GL_TRIANGLES);
        float[] normal = computeNormal(x1, z1, x2, z2, x3, z3);
        glNormal3f(normal[0], normal[1], normal[2]);
        glTexCoord2f(x1 * texScale, z1 * texScale);
        glVertex3f(wx1, y1, wz1);
        glTexCoord2f(x2 * texScale, z2 * texScale);
        glVertex3f(wx2, y2, wz2);
        glTexCoord2f(x3 * texScale, z3 * texScale);
        glVertex3f(wx3, y3, wz3);
        glEnd();
    }

    public List<Feature> getFeatures() {
        return features;
    }

    public void unloadFeaturesIfOutOfRange(int pcx, int pcz, int featureRenderDist) {
        if (!featuresGenerated)
            return;

        // Check if the chunk is out of feature render distance
        if (Math.abs(cx - pcx) > featureRenderDist || Math.abs(cz - pcz) > featureRenderDist) {
            // Remove only non-lake features
            features.removeIf(f -> !(f instanceof Lake));
            featuresGenerated = false;
            disposeGrassBatch();

            for (int x = 0; x < SIZE; x++) {
                Arrays.fill(featureMask[x], false);
                // lakeMask[x][z] is NOT cleared so lakes persist
            }
        }
    }

    private void generateFeatures() {
        if (biome.features == null || biome.features.isEmpty())
            return;

        long chunkSeed = FeatureUtil.hashSeed(cx, 0, cz, manager.getSeed());
        Random rand = new Random(chunkSeed);

        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                if (featureMask[x][z] || lakeMask[x][z])
                    continue;

                float height = heights[x][z];
                float slope = computeSlope(x, z);

                if (height < FEATURE_MIN_HEIGHT || height > FEATURE_MAX_HEIGHT)
                    continue;

                if (height > FEATURE_MAX_HEIGHT || slope > FEATURE_SLOPE_SPAWN_THRESHOLD)
                    continue;


                float wx = (cx * SIZE + x + 0.5f) * scale;
                float wz = (cz * SIZE + z + 0.5f) * scale;

                // --- Adjust feature height DOWNWARD based on slope ---
                float slopeAdjustment = slope * 2.0f; // You can tweak 2.0f to make it stronger/weaker
                float wy = height - slopeAdjustment;

                for (Map.Entry<FeatureSpawner, Float> entry : biome.features.entrySet()) {
                    if (entry.getKey() instanceof LakeSpawner)
                        continue;
                    if (entry.getKey().getClass().getSimpleName().contains("TreeSpawner")) {
                        if (height > FEATURE_TREE_MAX_HEIGHT)
                            continue;
                    }

                    if (rand.nextFloat() < entry.getValue()) {
                        int ix = cx * SIZE + x;
                        int iz = cz * SIZE + z;
                        int iy = Math.round(height * 1000);
                        long seed = FeatureUtil.hashSeed(ix, iy, iz, manager.getSeed());

                        Feature f = entry.getKey().spawn(wx, wy, wz, seed);
                        features.add(f);
                        featureMask[x][z] = true;
                        break;
                    }
                }
            }
        }
        buildGrassBatch();
    }

    private float averageSurroundingSlope(int centerX, int centerZ, int radius) {
        int count = 0;
        float totalSlope = 0;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = centerX + dx;
                int z = centerZ + dz;

                if (x >= 0 && z >= 0 && x < SIZE && z < SIZE) {
                    totalSlope += computeSlope(x, z);
                    count++;
                }
            }
        }

        return (count > 0) ? totalSlope / count : 1f; // 1f is max slope fallback
    }

    private float computeSlope(int x, int z) {
        int x0 = Math.max(0, x - 1);
        int x1 = Math.min(SIZE, x + 1);
        int z0 = Math.max(0, z - 1);
        int z1 = Math.min(SIZE, z + 1);

        float hL = heights[x0][z];
        float hR = heights[x1][z];
        float hD = heights[x][z0];
        float hU = heights[x][z1];

        float dx = (hR - hL) * 0.5f;
        float dz = (hU - hD) * 0.5f;
        return (float) Math.sqrt(dx * dx + dz * dz);
    }

    private int pickTexture(float height, float slope) {
        Random rand = new Random((int)(height * 1000 + slope * 1000));


        // --- Absolute bottom zone ---
        if (height <= ABSOLUTE_WATER_BOTTOM_HEIGHT) {
            return manager.getWaterBottomAbsTexture();
        }
        // --- Water surrounding zone ---
        else if (height <= WATER_SURROUNDING_LEVEL) {
            return manager.getWaterBottomTexture();
        }

        // --- Snow zone ---
        if (height >= SNOW_HEIGHT_START) {
            float snowChance = (height - SNOW_HEIGHT_START) / (SNOW_HEIGHT_FULL - SNOW_HEIGHT_START);
            snowChance = Math.min(Math.max(snowChance, 0f), 1f);

            if (rand.nextFloat() < snowChance) {
                return manager.getSnowTexture();
            }
        }

        // --- Slope-based textures ---
        if (slope > ROCK_SLOPE_START) {
            return manager.getTexture(biome.rockTex);
        }
        if (slope > DIRT_SLOPE_START) {
            return manager.getTexture(biome.dirtTex);
        }

        return manager.getTexture(biome.grassTex);
    }



    public float getHeight(float wx, float wz) {
        float lx = wx / scale - cx * SIZE;
        float lz = wz / scale - cz * SIZE;
        int ix = (int) Math.floor(lx), iz = (int) Math.floor(lz);
        if (ix < 0 || iz < 0 || ix >= SIZE || iz >= SIZE)
            return 0f;

        float fx = lx - ix, fz = lz - iz;
        float h00 = heights[ix][iz];
        float h10 = heights[ix + 1][iz];
        float h01 = heights[ix][iz + 1];
        float h11 = heights[ix + 1][iz + 1];

        float a = h00 + (h10 - h00) * fx;
        float b = h01 + (h11 - h01) * fx;
        return a + (b - a) * fz;
    }

    public BoundingBox getBoundingBox() {
        float minX = cx * SIZE * scale;
        float minZ = cz * SIZE * scale;
        float maxX = (cx + 1) * SIZE * scale;
        float maxZ = (cz + 1) * SIZE * scale;
        float minY = Float.MAX_VALUE;
        float maxY = Float.MIN_VALUE;

        for (int x = 0; x <= SIZE; x++) {
            for (int z = 0; z <= SIZE; z++) {
                float h = heights[x][z];
                minY = Math.min(minY, h);
                maxY = Math.max(maxY, h);
            }
        }

        return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public void dispose() {
        for (Feature f : features) {
            f.dispose(); // let each feature release OpenGL textures/resources
        }
        disposeTerrainBuffers();
        disposeWaterDisplayList();
        disposeGrassBatch();
    }

    public OpenSimplexNoise getTerrainNoise() {
        return terrainNoise;
    }

    public float getScale() {
        return scale;
    }

    public int getLOD() {
        return lod;
    }

    private void disposeTerrainBuffers() {
        for (TerrainBatch batch : terrainBatches) {
            glDeleteBuffers(batch.vboId);
        }
        terrainBatches.clear();
    }

    private void disposeWaterDisplayList() {
        if (waterDisplayList != -1) {
            glDeleteLists(waterDisplayList, 1);
            waterDisplayList = -1;
        }
    }

    private void disposeGrassBatch() {
        if (grassBatchVbo != -1) {
            glDeleteBuffers(grassBatchVbo);
            grassBatchVbo = -1;
        }
        grassBatchVertexCount = 0;
        grassBatchTexture = 0;
    }

    private float[] computeNormal(int x1, int z1, int x2, int z2, int x3, int z3) {
        float[] p1 = { (cx * SIZE + x1) * scale, heights[x1][z1], (cz * SIZE + z1) * scale };
        float[] p2 = { (cx * SIZE + x2) * scale, heights[x2][z2], (cz * SIZE + z2) * scale };
        float[] p3 = { (cx * SIZE + x3) * scale, heights[x3][z3], (cz * SIZE + z3) * scale };

        float[] u = { p2[0] - p1[0], p2[1] - p1[1], p2[2] - p1[2] };
        float[] v = { p3[0] - p1[0], p3[1] - p1[1], p3[2] - p1[2] };

        float nx = u[1] * v[2] - u[2] * v[1];
        float ny = u[2] * v[0] - u[0] * v[2];
        float nz = u[0] * v[1] - u[1] * v[0];

        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len == 0f) {
            return new float[] { 0f, 1f, 0f };
        }
        return new float[] { nx / len, ny / len, nz / len };
    }

    private static final int STRIDE_FLOATS = 8;

    private static final class TerrainBatch {
        private final int textureId;
        private final int vboId;
        private final int vertexCount;

        private TerrainBatch(int textureId, int vboId, int vertexCount) {
            this.textureId = textureId;
            this.vboId = vboId;
            this.vertexCount = vertexCount;
        }
    }

    private static final class FloatBuilder {
        private float[] data = new float[8192];
        private int size = 0;

        private void putVertex(float x, float y, float z, float[] normal, float u, float v) {
            ensureCapacity(STRIDE_FLOATS);
            data[size++] = x;
            data[size++] = y;
            data[size++] = z;
            data[size++] = normal[0];
            data[size++] = normal[1];
            data[size++] = normal[2];
            data[size++] = u;
            data[size++] = v;
        }

        private void ensureCapacity(int floats) {
            int needed = size + floats;
            if (needed > data.length) {
                int newSize = Math.max(needed, data.length * 2);
                data = Arrays.copyOf(data, newSize);
            }
        }

        private java.nio.FloatBuffer toBuffer() {
            java.nio.FloatBuffer buffer = org.lwjgl.BufferUtils.createFloatBuffer(size);
            buffer.put(data, 0, size).flip();
            return buffer;
        }
    }
}
