package game;
import generators.LakeGenerator;
import objects.BatchableFeature;
import objects.Feature;
import objects.ColorBatchableFeature;
import objects.Grass;
import objects.Lake;
import objects.Tree;
import spawners.FeatureSpawner;
import spawners.LakeSpawner;
import util.BoundingBox;
import util.FeatureUtil;
import util.VertexBatchBuilder;
import org.lwjgl.BufferUtils;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL14.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL30.*;
import java.util.*;

public class Chunk {
    public static final int SIZE = 30;
    private static final float SKIRT_DEPTH = 24f;
    private static final float SKIRT_TOP_OFFSET = 0.02f;
    private static final float DEFAULT_TEXTURE_LOD_BIAS = 1f;
    private static final int IMPOSTOR_TEXTURE_SIZE = 128;
    private static final float IMPOSTOR_PADDING = 1.05f;
    private static final class ImpostorSize {
        private final float width;
        private final float height;

        private ImpostorSize(float width, float height) {
            this.width = width;
            this.height = height;
        }
    }
    private static final class ImpostorEntry {
        private final int textureId;
        private final ImpostorSize size;

        private ImpostorEntry(int textureId, ImpostorSize size) {
            this.textureId = textureId;
            this.size = size;
        }
    }
    private enum FeatureLod {
        FULL,
        IMPOSTOR,
        CULLED
    }
    public final int cx, cz;
    private final OpenSimplexNoise terrainNoise;
    private final float scale;
    private final TerrainManager manager;
    private final Biome biome;
    private final int lod;

    private final float[][] heights = new float[SIZE + 1][SIZE + 1];
    private final float[][] riverSurface = new float[SIZE + 1][SIZE + 1];
    private final List<Feature> features = new ArrayList<>();
    private final boolean[][] featureMask = new boolean[SIZE][SIZE];
    private final boolean[][] lakeMask = new boolean[SIZE][SIZE];
    private boolean featuresGenerated = false;
    private final List<TerrainBatch> terrainBatches = new ArrayList<>();
    private int waterDisplayList = -1;
    private int grassBatchVbo = -1;
    private int grassBatchVertexCount = 0;
    private int grassBatchTexture = 0;
    private int flowerBatchVbo = -1;
    private int flowerBatchVertexCount = 0;
    private boolean renderResourcesBuilt = false;
    public static final float WATER_LEVEL = 4.0f;
    public static final float WATER_SURROUNDING_LEVEL = 5.5f;
    public static final float ABSOLUTE_WATER_BOTTOM_HEIGHT = 1.0f;

    private static final float DIRT_SLOPE_START = 0.65f;
    private static final float ROCK_SLOPE_START = 1.0f;
    private static final int BLEND_STEPS = 4;
    private static final float BLEND_EDGE_START = 0.4f;
    private static final float BLEND_EDGE_END = 0.6f;


    private static final float SNOW_HEIGHT_START = 55f;
    private static final float SNOW_HEIGHT_FULL = 60f;

    private static final float FEATURE_MIN_HEIGHT = WATER_SURROUNDING_LEVEL;
    private static final float FEATURE_MAX_HEIGHT = SNOW_HEIGHT_START;
    public static final float FEATURE_SLOPE_SPAWN_THRESHOLD = DIRT_SLOPE_START;
    public static final float FEATURE_TREE_MAX_HEIGHT = 25f; // example, you can adjust

    private static final Map<String, ImpostorEntry> IMPOSTOR_TEXTURES = new HashMap<>();

    public static class ChunkBuildData {
        public final int cx;
        public final int cz;
        public final int lod;
        public final Biome biome;
        public final float[][] heights;
        public final float[][] riverSurface;
        public final boolean[][] featureMask;
        public final boolean[][] lakeMask;
        public final List<Feature> lakes;

        public ChunkBuildData(int cx, int cz, int lod, Biome biome, float[][] heights, boolean[][] featureMask,
                              boolean[][] lakeMask, List<Feature> lakes, float[][] riverSurface) {
            this.cx = cx;
            this.cz = cz;
            this.lod = lod;
            this.biome = biome;
            this.heights = heights;
            this.featureMask = featureMask;
            this.lakeMask = lakeMask;
            this.lakes = lakes;
            this.riverSurface = riverSurface;
        }
    }

    public static class FeatureSpawn {
        public final FeatureSpawner spawner;
        public final float x;
        public final float y;
        public final float z;
        public final long seed;

        public FeatureSpawn(FeatureSpawner spawner, float x, float y, float z, long seed) {
            this.spawner = spawner;
            this.x = x;
            this.y = y;
            this.z = z;
            this.seed = seed;
        }
    }

    public static class FeatureGenerationInput {
        public final int cx;
        public final int cz;
        public final float scale;
        public final Biome biome;
        public final float[][] heights;
        public final float[][] riverSurface;
        public final boolean[][] featureMask;
        public final boolean[][] lakeMask;
        public final long seed;

        public FeatureGenerationInput(int cx, int cz, float scale, Biome biome, float[][] heights,
                                      float[][] riverSurface, boolean[][] featureMask, boolean[][] lakeMask, long seed) {
            this.cx = cx;
            this.cz = cz;
            this.scale = scale;
            this.biome = biome;
            this.heights = heights;
            this.riverSurface = riverSurface;
            this.featureMask = featureMask;
            this.lakeMask = lakeMask;
            this.seed = seed;
        }
    }

    public static class FeatureGenerationResult {
        public final int cx;
        public final int cz;
        public final List<FeatureSpawn> spawns;
        public final boolean[][] featureMask;

        public FeatureGenerationResult(int cx, int cz, List<FeatureSpawn> spawns, boolean[][] featureMask) {
            this.cx = cx;
            this.cz = cz;
            this.spawns = spawns;
            this.featureMask = featureMask;
        }
    }






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
        renderResourcesBuilt = true;
    }

    public Chunk(int cx, int cz, OpenSimplexNoise terrainNoise, float scale, Biome biome, TerrainManager manager,
                 int lod, ChunkBuildData data) {
        this.cx = cx;
        this.cz = cz;
        this.terrainNoise = terrainNoise;
        this.scale = scale;
        this.manager = manager;
        this.biome = biome;
        this.lod = lod;
        if (data != null) {
            applyPrecomputedData(data);
            renderResourcesBuilt = false;
        } else {
            generate(false);
            renderResourcesBuilt = true;
        }
    }

    public static ChunkBuildData generateChunkData(int cx, int cz, OpenSimplexNoise terrainNoise, float scale,
                                                   Biome biome, TerrainManager manager, int lod) {
        final int OCTAVES = 4;
        final double PERSISTENCE = 0.35;
        final double macroFreq = 0.002;
        final double macroAmp = 2.0;
        final int lodClamped = Math.max(0, lod);
        final int baseOctaves = 2;
        final int lodPenalty = Math.max(0, lodClamped - 1);
        final int lodOctaves = Math.max(baseOctaves, OCTAVES - lodPenalty);

        float[][] heights = new float[SIZE + 1][SIZE + 1];
        float[][] riverSurface = new float[SIZE + 1][SIZE + 1];
        boolean[][] featureMask = new boolean[SIZE][SIZE];
        boolean[][] lakeMask = new boolean[SIZE][SIZE];
        List<Feature> lakes = new ArrayList<>();

        final int weightStep = 4;
        List<Integer> weightXs = new ArrayList<>();
        List<Integer> weightZs = new ArrayList<>();
        for (int x = 0; x <= SIZE; x += weightStep) {
            weightXs.add(x);
        }
        if (weightXs.get(weightXs.size() - 1) != SIZE) {
            weightXs.add(SIZE);
        }
        for (int z = 0; z <= SIZE; z += weightStep) {
            weightZs.add(z);
        }
        if (weightZs.get(weightZs.size() - 1) != SIZE) {
            weightZs.add(SIZE);
        }

        @SuppressWarnings("unchecked")
        Map<Biome, Float>[][] weightGrid = new Map[weightXs.size()][weightZs.size()];
        for (int gx = 0; gx < weightXs.size(); gx++) {
            int lx = weightXs.get(gx);
            double wx = (cx * SIZE + lx) * scale;
            for (int gz = 0; gz < weightZs.size(); gz++) {
                int lz = weightZs.get(gz);
                double wz = (cz * SIZE + lz) * scale;
                weightGrid[gx][gz] = manager.getBiomeWeights(wx, wz);
            }
        }

        for (int x = 0; x <= SIZE; x++) {
            for (int z = 0; z <= SIZE; z++) {
                double wx = (cx * SIZE + x) * scale;
                double wz = (cz * SIZE + z) * scale;

                int gxIndex = Math.min(x / weightStep, weightXs.size() - 2);
                int gzIndex = Math.min(z / weightStep, weightZs.size() - 2);
                int x0 = weightXs.get(gxIndex);
                int x1 = weightXs.get(gxIndex + 1);
                int z0 = weightZs.get(gzIndex);
                int z1 = weightZs.get(gzIndex + 1);

                float tx = x1 == x0 ? 0f : (float) (x - x0) / (float) (x1 - x0);
                float tz = z1 == z0 ? 0f : (float) (z - z0) / (float) (z1 - z0);
                float sx = 1f - tx;
                float sz = 1f - tz;

                float w00 = sx * sz;
                float w10 = tx * sz;
                float w01 = sx * tz;
                float w11 = tx * tz;

                Map<Biome, Double> weights = new HashMap<>();
                for (Map.Entry<Biome, Float> entry : weightGrid[gxIndex][gzIndex].entrySet()) {
                    weights.merge(entry.getKey(), entry.getValue() * (double) w00, Double::sum);
                }
                for (Map.Entry<Biome, Float> entry : weightGrid[gxIndex + 1][gzIndex].entrySet()) {
                    weights.merge(entry.getKey(), entry.getValue() * (double) w10, Double::sum);
                }
                for (Map.Entry<Biome, Float> entry : weightGrid[gxIndex][gzIndex + 1].entrySet()) {
                    weights.merge(entry.getKey(), entry.getValue() * (double) w01, Double::sum);
                }
                for (Map.Entry<Biome, Float> entry : weightGrid[gxIndex + 1][gzIndex + 1].entrySet()) {
                    weights.merge(entry.getKey(), entry.getValue() * (double) w11, Double::sum);
                }
                double blendedHeight = 0;

                for (Map.Entry<Biome, Double> entry : weights.entrySet()) {
                    Biome entryBiome = entry.getKey();
                    double weight = entry.getValue();

                    double freq = entryBiome.frequency;
                    double amp = entryBiome.amplitude * 0.5;
                    double sum = 0;

                    for (int o = 0; o < lodOctaves; o++) {
                        double offset = o * 100.0;
                        double val = terrainNoise.eval((wx + offset) * freq, (wz - offset) * freq);
                        val = (val * val * val) * 1.2;
                        sum += val * amp;
                        freq *= 1.7;
                        amp *= PERSISTENCE;
                    }

                    double elevationOffset = terrainNoise.eval(wx * macroFreq, wz * macroFreq) * macroAmp;
                    double biomeHeight = entryBiome.baseHeight + sum + elevationOffset;
                    blendedHeight += biomeHeight * weight;
                }

                heights[x][z] = (float) blendedHeight;
            }
        }

        resetRiverSurface(riverSurface);
        applyCavesAndRavines(heights, riverSurface, cx, cz, scale, terrainNoise, manager.getSeed());

        LakeGenerator.generateLakes(cx, cz, scale, biome, heights, featureMask, lakeMask, lakes, manager);
        return new ChunkBuildData(cx, cz, lod, biome, heights, featureMask, lakeMask, lakes, riverSurface);
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

        resetRiverSurface(riverSurface);
        applyCavesAndRavines(heights, riverSurface, cx, cz, scale, terrainNoise, manager.getSeed());


        LakeGenerator.generateLakes(cx, cz, scale, biome, heights, featureMask, lakeMask, features, manager);
        if (generateFeatures)
            generateFeatures();
        stitchEdges();
        buildTerrainBuffers();
        buildWaterDisplayList();
        buildFeatureBatches();

    }

    public void drawWater() {
        glPushAttrib(GL_ENABLE_BIT | GL_COLOR_BUFFER_BIT);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDisable(GL_TEXTURE_2D);

        glDepthMask(false); // Disable depth writing for transparency
        glDepthFunc(GL_LEQUAL);

        glEnable(GL_FOG); // <<< ADD THIS
        glColor4f(0.2f, 0.5f, 0.8f, 0.55f); // base water color

        if (waterDisplayList != -1) {
            glCallList(waterDisplayList);
        }

        glBlendFunc(GL_SRC_ALPHA, GL_ONE);
        glColor4f(0.75f, 0.85f, 0.95f, 0.12f); // subtle reflection tint
        if (waterDisplayList != -1) {
            glCallList(waterDisplayList);
        }

        glDepthMask(true); // Re-enable depth writing

        glDisable(GL_FOG); // <<< Disable again after drawing water
        glDepthFunc(GL_LESS);
        glPopAttrib();
    }




    public void generateFeaturesIfNeeded(int pcx, int pcz, int featureRenderDist) {
        if (featuresGenerated)
            return;

        if (Math.abs(cx - pcx) > featureRenderDist || Math.abs(cz - pcz) > featureRenderDist)
            return;

        generateFeatures();
        featuresGenerated = true;
    }

    public boolean needsFeatureGeneration(int pcx, int pcz, int featureRenderDist) {
        if (featuresGenerated) {
            return false;
        }
        return Math.abs(cx - pcx) <= featureRenderDist && Math.abs(cz - pcz) <= featureRenderDist;
    }

    public FeatureGenerationInput createFeatureGenerationInput() {
        return new FeatureGenerationInput(cx, cz, scale, biome, heights, riverSurface,
                copyMask(featureMask), lakeMask, manager.getSeed());
    }

    public void applyFeatureGenerationResult(FeatureGenerationResult result) {
        if (featuresGenerated || result == null) {
            return;
        }
        copyMaskInto(result.featureMask, featureMask);
        for (FeatureSpawn spawn : result.spawns) {
            Feature f = spawn.spawner.spawn(spawn.x, spawn.y, spawn.z, spawn.seed);
            features.add(f);
        }
        featuresGenerated = true;
        buildFeatureBatches();
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

    public void drawTerrainAndFeatures(int chunkDistance, int featureImpostorDistance,
                                       int grassDetailDistance, int featureRenderDist) {
        glEnable(GL_TEXTURE_2D);
        glColor3f(1f, 1f, 1f);

        renderTerrainBuffers();
        if (chunkDistance <= grassDetailDistance && chunkDistance <= featureRenderDist) {
            renderGrassBatch();
        }
        if (chunkDistance <= featureRenderDist) {
            renderFlowerBatch();
        }

        glDisable(GL_TEXTURE_2D);

        if (chunkDistance > featureRenderDist) {
            return;
        }

        FeatureLod lod = getFeatureLod(chunkDistance, featureImpostorDistance, featureRenderDist);
        // Draw features if they are above water
        for (Feature f : features) {
            if (f instanceof Grass || f instanceof ColorBatchableFeature) {
                continue;
            }
            if (!shouldDrawFeatureForLod(f, lod)) {
                continue;
            }
            if (f.y >= WATER_LEVEL) {
                drawFeatureForLod(f, lod);
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
        Map<BatchKey, FloatBuilder> builders = new HashMap<>();
        float texScale = 0.2f / (1f + lod * 0.5f);
        int step = (int) Math.pow(2, lod);
        for (int z = 0; z < SIZE; z += step) {
            int z2 = Math.min(z + step, SIZE);
            for (int x = 0; x < SIZE; x += step) {
                int x2 = Math.min(x + step, SIZE);

                float y00 = heights[x][z];
                float y10 = heights[x2][z];
                float y01 = heights[x][z2];
                float y11 = heights[x2][z2];

                addTriangle(builders, x, z, x2, z, x, z2, y00, y10, y01, texScale);
                addTriangle(builders, x2, z, x2, z2, x, z2, y10, y11, y01, texScale);
            }
        }

        addSkirts(builders, step, texScale);

        for (Map.Entry<BatchKey, FloatBuilder> entry : builders.entrySet()) {
            FloatBuilder builder = entry.getValue();
            if (builder.size == 0) {
                continue;
            }
            int vboId = glGenBuffers();
            glBindBuffer(GL_ARRAY_BUFFER, vboId);
            glBufferData(GL_ARRAY_BUFFER, builder.toBuffer(), GL_STATIC_DRAW);
            glBindBuffer(GL_ARRAY_BUFFER, 0);
            BatchKey key = entry.getKey();
            terrainBatches.add(new TerrainBatch(key.textureId, vboId, builder.size / STRIDE_FLOATS,
                    alphaFromBucket(key.alphaBucket)));
        }
    }

    private void buildWaterGeometry() {
        int step = (int) Math.pow(2, lod);

        for (int z = 0; z < SIZE; z += step) {
            int z2 = Math.min(z + step, SIZE);
            for (int x = 0; x < SIZE; x += step) {
                int x2 = Math.min(x + step, SIZE);

                float y00 = heights[x][z];
                float y10 = heights[x2][z];
                float y01 = heights[x][z2];
                float y11 = heights[x2][z2];

                float r00 = riverSurface[x][z];
                float r10 = riverSurface[x2][z];
                float r01 = riverSurface[x][z2];
                float r11 = riverSurface[x2][z2];
                boolean hasRiverWater = r00 > Float.NEGATIVE_INFINITY / 2
                        || r10 > Float.NEGATIVE_INFINITY / 2
                        || r01 > Float.NEGATIVE_INFINITY / 2
                        || r11 > Float.NEGATIVE_INFINITY / 2;

                boolean needsWater =
                        y00 < WATER_LEVEL || y10 < WATER_LEVEL || y01 < WATER_LEVEL || y11 < WATER_LEVEL
                                || hasRiverWater;

                if (needsWater) {
                    float wx1 = (cx * SIZE + x) * scale;
                    float wz1 = (cz * SIZE + z) * scale;
                    float wx2 = (cx * SIZE + x2) * scale;
                    float wz2 = (cz * SIZE + z2) * scale;

                    float wy1 = r00 > Float.NEGATIVE_INFINITY / 2 ? r00 : WATER_LEVEL;
                    float wy2 = r10 > Float.NEGATIVE_INFINITY / 2 ? r10 : WATER_LEVEL;
                    float wy3 = r11 > Float.NEGATIVE_INFINITY / 2 ? r11 : WATER_LEVEL;
                    float wy4 = r01 > Float.NEGATIVE_INFINITY / 2 ? r01 : WATER_LEVEL;

                    glBegin(GL_QUADS);
                    glNormal3f(0f, 1f, 0f);
                    glVertex3f(wx1, wy1, wz1);
                    glVertex3f(wx2, wy2, wz1);
                    glVertex3f(wx2, wy3, wz2);
                    glVertex3f(wx1, wy4, wz2);
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

    private void buildFlowerBatch() {
        disposeFlowerBatch();
        util.VertexColorBatchBuilder builder = new util.VertexColorBatchBuilder();

        for (Feature feature : features) {
            if (feature instanceof ColorBatchableFeature) {
                ((ColorBatchableFeature) feature).appendToColorBatch(builder);
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

        flowerBatchVbo = vboId;
        flowerBatchVertexCount = vertexCount;
    }

    private void buildFeatureBatches() {
        buildGrassBatch();
        buildFlowerBatch();
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
        glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_LOD_BIAS, getTextureLodBias());
        glBindBuffer(GL_ARRAY_BUFFER, grassBatchVbo);
        glEnableClientState(GL_VERTEX_ARRAY);
        glEnableClientState(GL_TEXTURE_COORD_ARRAY);
        glVertexPointer(3, GL_FLOAT, 5 * Float.BYTES, 0);
        glTexCoordPointer(2, GL_FLOAT, 5 * Float.BYTES, 3 * Float.BYTES);
        glDrawArrays(GL_QUADS, 0, grassBatchVertexCount);
        glDisableClientState(GL_TEXTURE_COORD_ARRAY);
        glDisableClientState(GL_VERTEX_ARRAY);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_LOD_BIAS, DEFAULT_TEXTURE_LOD_BIAS);
        glDisable(GL_ALPHA_TEST);
        glDisable(GL_BLEND);
    }

    private void renderFlowerBatch() {
        if (flowerBatchVbo == -1 || flowerBatchVertexCount == 0) {
            return;
        }

        glDisable(GL_TEXTURE_2D);
        glBindBuffer(GL_ARRAY_BUFFER, flowerBatchVbo);
        glEnableClientState(GL_VERTEX_ARRAY);
        glEnableClientState(GL_COLOR_ARRAY);
        glVertexPointer(3, GL_FLOAT, 6 * Float.BYTES, 0);
        glColorPointer(3, GL_FLOAT, 6 * Float.BYTES, 3 * Float.BYTES);
        glDrawArrays(GL_TRIANGLES, 0, flowerBatchVertexCount);
        glDisableClientState(GL_COLOR_ARRAY);
        glDisableClientState(GL_VERTEX_ARRAY);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
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

    private void renderFlowerBatchDepth() {
        if (flowerBatchVbo == -1 || flowerBatchVertexCount == 0) {
            return;
        }

        glBindBuffer(GL_ARRAY_BUFFER, flowerBatchVbo);
        glEnableClientState(GL_VERTEX_ARRAY);
        glVertexPointer(3, GL_FLOAT, 6 * Float.BYTES, 0);
        glDrawArrays(GL_TRIANGLES, 0, flowerBatchVertexCount);
        glDisableClientState(GL_VERTEX_ARRAY);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    private void renderTerrainBuffers() {
        if (terrainBatches.isEmpty()) {
            return;
        }

        glColor4f(1f, 1f, 1f, 1f);
        glEnableClientState(GL_VERTEX_ARRAY);
        glEnableClientState(GL_NORMAL_ARRAY);
        glEnableClientState(GL_TEXTURE_COORD_ARRAY);
        float lodBias = getTextureLodBias();

        int strideBytes = STRIDE_FLOATS * Float.BYTES;
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(-0.5f, -0.5f);
        for (TerrainBatch batch : terrainBatches) {
            if (batch.alpha < 0.999f) {
                continue;
            }
            glBindTexture(GL_TEXTURE_2D, batch.textureId);
            glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_LOD_BIAS, lodBias);
            glBindBuffer(GL_ARRAY_BUFFER, batch.vboId);
            glVertexPointer(3, GL_FLOAT, strideBytes, 0);
            glNormalPointer(GL_FLOAT, strideBytes, 3 * Float.BYTES);
            glTexCoordPointer(2, GL_FLOAT, strideBytes, 6 * Float.BYTES);
            glDrawArrays(GL_TRIANGLES, 0, batch.vertexCount);
        }

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(false);
        glDepthFunc(GL_LEQUAL);
        glPolygonOffset(-1f, -1f);
        for (TerrainBatch batch : terrainBatches) {
            if (batch.alpha >= 0.999f) {
                continue;
            }
            glColor4f(1f, 1f, 1f, batch.alpha);
            glBindTexture(GL_TEXTURE_2D, batch.textureId);
            glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_LOD_BIAS, lodBias);
            glBindBuffer(GL_ARRAY_BUFFER, batch.vboId);
            glVertexPointer(3, GL_FLOAT, strideBytes, 0);
            glNormalPointer(GL_FLOAT, strideBytes, 3 * Float.BYTES);
            glTexCoordPointer(2, GL_FLOAT, strideBytes, 6 * Float.BYTES);
            glDrawArrays(GL_TRIANGLES, 0, batch.vertexCount);
        }
        glDisable(GL_POLYGON_OFFSET_FILL);
        glDepthFunc(GL_LESS);
        glDepthMask(true);
        glDisable(GL_BLEND);
        glColor4f(1f, 1f, 1f, 1f);
        glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_LOD_BIAS, DEFAULT_TEXTURE_LOD_BIAS);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glDisableClientState(GL_TEXTURE_COORD_ARRAY);
        glDisableClientState(GL_NORMAL_ARRAY);
        glDisableClientState(GL_VERTEX_ARRAY);
    }

    private float getTextureLodBias() {
        if (lod >= 3) {
            return 4f;
        }
        if (lod == 2) {
            return 3f;
        }
        if (lod == 1) {
            return 2f;
        }
        return DEFAULT_TEXTURE_LOD_BIAS;
    }

    public void renderDepth(int chunkDistance, int featureImpostorDistance,
                            int grassDetailDistance, int featureRenderDist) {
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

        if (chunkDistance <= grassDetailDistance && chunkDistance <= featureRenderDist) {
            renderGrassBatchDepth();
        }
        if (chunkDistance <= featureRenderDist) {
            renderFlowerBatchDepth();
        }

        if (chunkDistance > featureRenderDist) {
            return;
        }

        FeatureLod lod = getFeatureLod(chunkDistance, featureImpostorDistance, featureRenderDist);
        for (Feature f : features) {
            if (f instanceof Grass || f instanceof ColorBatchableFeature) {
                continue;
            }
            if (!shouldDrawFeatureForLod(f, lod)) {
                continue;
            }
            if (f.y >= WATER_LEVEL) {
                drawFeatureDepthForLod(f, lod);
            }
        }
    }

    private FeatureLod getFeatureLod(int chunkDistance, int impostorDistance,
                                     int cullDistance) {
        if (chunkDistance > cullDistance) {
            return FeatureLod.CULLED;
        }
        if (chunkDistance > impostorDistance) {
            return FeatureLod.IMPOSTOR;
        }
        return FeatureLod.FULL;
    }

    private boolean shouldDrawFeatureForLod(Feature feature, FeatureLod lod) {
        if (lod == FeatureLod.CULLED) {
            return false;
        }
        if (lod == FeatureLod.IMPOSTOR) {
            return feature instanceof Tree || feature instanceof Lake;
        }
        return true;
    }

    private void drawFeatureForLod(Feature feature, FeatureLod lod) {
        if (lod == FeatureLod.IMPOSTOR) {
            drawFeatureImpostor(feature);
        } else {
            feature.draw();
        }
    }

    private void drawFeatureDepthForLod(Feature feature, FeatureLod lod) {
        if (lod == FeatureLod.IMPOSTOR) {
            drawFeatureImpostorDepth(feature);
        } else {
            feature.drawDepth();
        }
    }

    private void drawFeatureImpostor(Feature feature) {
        ImpostorEntry entry = getImpostorEntry(feature);
        int texture = entry.textureId;
        ImpostorSize size = getActualImpostorSize(feature);

        float[] modelView = new float[16];
        glGetFloatv(GL_MODELVIEW_MATRIX, modelView);
        float rightX = modelView[0];
        float rightY = modelView[4];
        float rightZ = modelView[8];

        float halfW = size.width * 0.5f;
        float x1 = feature.x - rightX * halfW;
        float y1 = feature.y;
        float z1 = feature.z - rightZ * halfW;
        float x2 = feature.x + rightX * halfW;
        float y2 = feature.y;
        float z2 = feature.z + rightZ * halfW;

        glEnable(GL_TEXTURE_2D);
        glDisable(GL_LIGHTING);
        glDisable(GL_CULL_FACE);
        glColor4f(1f, 1f, 1f, 1f);
        if (feature instanceof Tree) {
            glDisable(GL_BLEND);
            glEnable(GL_ALPHA_TEST);
            glAlphaFunc(GL_GREATER, 0.5f);
        } else {
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            glEnable(GL_ALPHA_TEST);
            glAlphaFunc(GL_GREATER, 0.05f);
        }
        glBindTexture(GL_TEXTURE_2D, texture);
        glBegin(GL_QUADS);
        glTexCoord2f(0f, 0f);
        glVertex3f(x1, y1, z1);
        glTexCoord2f(1f, 0f);
        glVertex3f(x2, y2, z2);
        glTexCoord2f(1f, 1f);
        glVertex3f(x2, y2 + size.height, z2);
        glTexCoord2f(0f, 1f);
        glVertex3f(x1, y1 + size.height, z1);
        glEnd();
        glDisable(GL_ALPHA_TEST);
        glDisable(GL_BLEND);
    }

    private void drawFeatureImpostorDepth(Feature feature) {
        ImpostorSize size = getActualImpostorSize(feature);

        float[] modelView = new float[16];
        glGetFloatv(GL_MODELVIEW_MATRIX, modelView);
        float rightX = modelView[0];
        float rightZ = modelView[8];

        float halfW = size.width * 0.5f;
        float x1 = feature.x - rightX * halfW;
        float y1 = feature.y;
        float z1 = feature.z - rightZ * halfW;
        float x2 = feature.x + rightX * halfW;
        float y2 = feature.y;
        float z2 = feature.z + rightZ * halfW;

        glBegin(GL_QUADS);
        glVertex3f(x1, y1, z1);
        glVertex3f(x2, y2, z2);
        glVertex3f(x2, y2 + size.height, z2);
        glVertex3f(x1, y1 + size.height, z1);
        glEnd();
    }

    private ImpostorSize getImpostorSize(Feature feature, float heightBucket, float canopyBucket,
                                         float lakeRadiusBucket) {
        float width = 2.5f;
        float height = 4.5f;
        if (feature instanceof Tree) {
            width = canopyBucket * 1.8f;
            height = heightBucket;
        } else if (feature instanceof Lake) {
            width = lakeRadiusBucket * 2.2f;
            height = 1.5f;
        }
        return new ImpostorSize(width * IMPOSTOR_PADDING, height * IMPOSTOR_PADDING);
    }

    private ImpostorSize getActualImpostorSize(Feature feature) {
        float width = 2.5f;
        float height = 4.5f;
        if (feature instanceof Tree) {
            Tree tree = (Tree) feature;
            float canopy = Math.max(tree.getCanopyRadius(), tree.getType().baseThickness * 2f);
            width = canopy * 1.8f;
            height = tree.getHeight();
        } else if (feature instanceof Lake) {
            Lake lake = (Lake) feature;
            float radius = Math.max(lake.getRadiusX(), lake.getRadiusZ());
            width = radius * 2.2f;
            height = 1.5f;
        }
        return new ImpostorSize(width * IMPOSTOR_PADDING, height * IMPOSTOR_PADDING);
    }

    private ImpostorEntry getImpostorEntry(Feature feature) {
        String key = feature.getClass().getSimpleName();
        float heightBucket = 0f;
        float canopyBucket = 0f;
        float lakeRadiusBucket = 0f;
        if (feature instanceof Tree) {
            Tree tree = (Tree) feature;
            float canopy = Math.max(tree.getCanopyRadius(), tree.getType().baseThickness * 2f);
            heightBucket = Math.max(0.25f, quantizeUp(tree.getHeight(), 0.25f));
            canopyBucket = Math.max(0.1f, quantizeUp(canopy, 0.1f));
            key = "Tree:" + tree.getType().name() + ":" + tree.hasLeaves() + ":" + heightBucket + ":" + canopyBucket;
        } else if (feature instanceof Lake) {
            Lake lake = (Lake) feature;
            float radius = Math.max(lake.getRadiusX(), lake.getRadiusZ());
            lakeRadiusBucket = Math.max(0.5f, quantizeUp(radius, 0.5f));
            key = "Lake:" + lakeRadiusBucket;
        }
        float finalHeightBucket = heightBucket;
        float finalCanopyBucket = canopyBucket;
        float finalLakeRadiusBucket = lakeRadiusBucket;
        return IMPOSTOR_TEXTURES.computeIfAbsent(key, ignored -> {
            ImpostorSize size = getImpostorSize(feature, finalHeightBucket, finalCanopyBucket, finalLakeRadiusBucket);
            int textureId = renderImpostorTexture(feature, size);
            return new ImpostorEntry(textureId, size);
        });
    }

    private int renderImpostorTexture(Feature feature, ImpostorSize size) {
        int textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, textureId);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, IMPOSTOR_TEXTURE_SIZE, IMPOSTOR_TEXTURE_SIZE,
                0, GL_RGBA, GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        int fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, textureId, 0);

        int depthBuffer = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, depthBuffer);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, IMPOSTOR_TEXTURE_SIZE, IMPOSTOR_TEXTURE_SIZE);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depthBuffer);

        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteRenderbuffers(depthBuffer);
            glDeleteFramebuffers(fbo);
            return manager.getTexture(biome.grassTex);
        }

        glDrawBuffer(GL_COLOR_ATTACHMENT0);
        glReadBuffer(GL_COLOR_ATTACHMENT0);

        int[] viewport = new int[4];
        glGetIntegerv(GL_VIEWPORT, viewport);
        glViewport(0, 0, IMPOSTOR_TEXTURE_SIZE, IMPOSTOR_TEXTURE_SIZE);

        glClearColor(0f, 0f, 0f, 0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);
        glPushAttrib(GL_ENABLE_BIT | GL_LIGHTING_BIT | GL_CURRENT_BIT);
        glDisable(GL_FOG);
        glEnable(GL_LIGHTING);
        glEnable(GL_LIGHT0);
        glEnable(GL_COLOR_MATERIAL);
        glColorMaterial(GL_FRONT_AND_BACK, GL_AMBIENT_AND_DIFFUSE);
        FloatBuffer impostorLightPos = BufferUtils.createFloatBuffer(4).put(new float[] { -0.2f, 1f, 0.3f, 0f })
                .flip();
        glLightfv(GL_LIGHT0, GL_POSITION, impostorLightPos);
        FloatBuffer impostorDiffuse = BufferUtils.createFloatBuffer(4).put(new float[] { 0.9f, 0.9f, 0.9f, 1f })
                .flip();
        glLightfv(GL_LIGHT0, GL_DIFFUSE, impostorDiffuse);
        FloatBuffer impostorAmbient = BufferUtils.createFloatBuffer(4).put(new float[] { 0.3f, 0.3f, 0.3f, 1f })
                .flip();
        glLightfv(GL_LIGHT0, GL_AMBIENT, impostorAmbient);
        glDisable(GL_CULL_FACE);
        glColor4f(1f, 1f, 1f, 1f);

        float halfWidth = size.width * 0.5f;

        glMatrixMode(GL_PROJECTION);
        glPushMatrix();
        glLoadIdentity();
        glOrtho(-halfWidth, halfWidth, 0f, size.height, -10f, 10f);

        glMatrixMode(GL_MODELVIEW);
        glPushMatrix();
        glLoadIdentity();
        glTranslatef(-feature.x, -feature.y, -feature.z);
        feature.draw();
        glPopMatrix();

        glMatrixMode(GL_PROJECTION);
        glPopMatrix();
        glMatrixMode(GL_MODELVIEW);
        glPopAttrib();

        glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glDeleteRenderbuffers(depthBuffer);
        glDeleteFramebuffers(fbo);

        return textureId;
    }

    private float quantizeUp(float value, float step) {
        if (step <= 0f) {
            return value;
        }
        return (float) Math.ceil(value / step) * step;
    }

    private void addSkirts(Map<BatchKey, FloatBuilder> builders, int step, float texScale) {
        for (int x = 0; x < SIZE; x += step) {
            int x2 = Math.min(x + step, SIZE);
            addSkirtQuad(builders, x, 0, x2, 0, heights[x][0], heights[x2][0], texScale);
            addSkirtQuad(builders, x, SIZE, x2, SIZE, heights[x][SIZE], heights[x2][SIZE], texScale);
        }

        for (int z = 0; z < SIZE; z += step) {
            int z2 = Math.min(z + step, SIZE);
            addSkirtQuad(builders, 0, z, 0, z2, heights[0][z], heights[0][z2], texScale);
            addSkirtQuad(builders, SIZE, z, SIZE, z2, heights[SIZE][z], heights[SIZE][z2], texScale);
        }
    }

    private void addSkirtQuad(Map<BatchKey, FloatBuilder> builders, int x1, int z1, int x2, int z2,
                              float y1, float y2, float texScale) {
        int tex = manager.getTexture(biome.rockTex);
        FloatBuilder builder = getBuilder(builders, tex, 1f);
        float[] normal = computeSkirtNormal(x1, z1, x2, z2);

        float wx1 = (cx * SIZE + x1) * scale;
        float wz1 = (cz * SIZE + z1) * scale;
        float wx2 = (cx * SIZE + x2) * scale;
        float wz2 = (cz * SIZE + z2) * scale;

        float y1b = y1 - SKIRT_DEPTH;
        float y2b = y2 - SKIRT_DEPTH;

        float y1Top = y1 - SKIRT_TOP_OFFSET;
        float y2Top = y2 - SKIRT_TOP_OFFSET;

        builder.putVertex(wx1, y1Top, wz1, normal, x1 * texScale, z1 * texScale);
        builder.putVertex(wx2, y2Top, wz2, normal, x2 * texScale, z2 * texScale);
        builder.putVertex(wx2, y2b, wz2, normal, x2 * texScale, z2 * texScale);

        builder.putVertex(wx1, y1Top, wz1, normal, x1 * texScale, z1 * texScale);
        builder.putVertex(wx2, y2b, wz2, normal, x2 * texScale, z2 * texScale);
        builder.putVertex(wx1, y1b, wz1, normal, x1 * texScale, z1 * texScale);
    }

    private float[] computeSkirtNormal(int x1, int z1, int x2, int z2) {
        float dx = (x2 - x1) * scale;
        float dz = (z2 - z1) * scale;
        float nx = dz;
        float ny = 0f;
        float nz = -dx;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 0.0001f) {
            return new float[] { 0f, 1f, 0f };
        }
        return new float[] { nx / length, ny / length, nz / length };
    }

    private void addTriangle(Map<BatchKey, FloatBuilder> builders, int x1, int z1, int x2, int z2, int x3, int z3,
            float y1, float y2, float y3, float texScale) {
        float slope = (computeSlope(x1, z1) + computeSlope(x2, z2) + computeSlope(x3, z3)) / 3f;
        float height = Math.max(y1, Math.max(y2, y3));
        if (isRiverBed(x1, z1, y1) || isRiverBed(x2, z2, y2) || isRiverBed(x3, z3, y3)) {
            int tex = manager.getTexture(biome.rockTex);
            addTriangleToBuilder(builders, tex, 1f, x1, z1, x2, z2, x3, z3, y1, y2, y3, texScale);
            return;
        }

        float wx = (cx * SIZE + (x1 + x2 + x3) / 3f) * scale;
        float wz = (cz * SIZE + (z1 + z2 + z3) / 3f) * scale;
        BiomeBlend blend = getBiomeBlend(wx, wz, manager);
        int primaryTex = pickTextureForBiome(blend.primary, height, slope, manager);
        addTriangleToBuilder(builders, primaryTex, 1f, x1, z1, x2, z2, x3, z3, y1, y2, y3, texScale);
        if (blend.blend > 0.0f && blend.secondary != null) {
            int secondaryTex = pickTextureForBiome(blend.secondary, height, slope, manager);
            if (secondaryTex != primaryTex) {
                addTriangleToBuilder(builders, secondaryTex, blend.blend, x1, z1, x2, z2, x3, z3, y1, y2, y3,
                        texScale);
            }
        }
    }

    private void drawTriangle(int x1, int z1, int x2, int z2, int x3, int z3, float y1, float y2, float y3,
            float texScale) {
        float slope = (computeSlope(x1, z1) + computeSlope(x2, z2) + computeSlope(x3, z3)) / 3f;
        float height = Math.max(y1, Math.max(y2, y3));
        int tex = isRiverBed(x1, z1, y1) || isRiverBed(x2, z2, y2) || isRiverBed(x3, z3, y3)
                ? manager.getTexture(biome.rockTex)
                : pickTexture(height, slope);

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

    private void addTriangleToBuilder(Map<BatchKey, FloatBuilder> builders, int tex, float alpha,
                                      int x1, int z1, int x2, int z2, int x3, int z3,
                                      float y1, float y2, float y3, float texScale) {
        FloatBuilder builder = getBuilder(builders, tex, alpha);
        if (builder == null) {
            return;
        }
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

    private FloatBuilder getBuilder(Map<BatchKey, FloatBuilder> builders, int tex, float alpha) {
        int bucket = quantizeAlpha(alpha);
        if (bucket == 0) {
            return null;
        }
        BatchKey key = new BatchKey(tex, bucket);
        return builders.computeIfAbsent(key, k -> new FloatBuilder());
    }

    private int quantizeAlpha(float alpha) {
        float clamped = Math.max(0f, Math.min(1f, alpha));
        int bucket = (int) Math.floor(clamped * BLEND_STEPS + 0.0001f);
        return Math.min(BLEND_STEPS, Math.max(0, bucket));
    }

    private float alphaFromBucket(int bucket) {
        return Math.min(1f, Math.max(0f, bucket / (float) BLEND_STEPS));
    }

    private static final class BiomeBlend {
        private final Biome primary;
        private final Biome secondary;
        private final float blend;

        private BiomeBlend(Biome primary, Biome secondary, float blend) {
            this.primary = primary;
            this.secondary = secondary;
            this.blend = blend;
        }
    }

    private BiomeBlend getBiomeBlend(float wx, float wz, TerrainManager manager) {
        Map<Biome, Float> weights = manager.getBiomeWeights(wx, wz);
        Biome primary = Biome.PLAINS;
        Biome secondary = Biome.PLAINS;
        float primaryWeight = -1f;
        float secondaryWeight = -1f;
        for (Map.Entry<Biome, Float> entry : weights.entrySet()) {
            float weight = entry.getValue();
            if (weight > primaryWeight) {
                secondary = primary;
                secondaryWeight = primaryWeight;
                primary = entry.getKey();
                primaryWeight = weight;
            } else if (weight > secondaryWeight) {
                secondary = entry.getKey();
                secondaryWeight = weight;
            }
        }
        if (secondaryWeight <= 0.0001f) {
            return new BiomeBlend(primary, null, 0f);
        }
        float blend = secondaryWeight / Math.max(0.0001f, primaryWeight + secondaryWeight);
        blend = (float) smoothstep(BLEND_EDGE_START, BLEND_EDGE_END, blend);
        if (blend < 0.2f || blend > 0.8f) {
            return new BiomeBlend(primary, null, 0f);
        }
        float minStep = 1f / BLEND_STEPS;
        blend = Math.max(minStep, Math.min(1f - minStep, blend));
        return new BiomeBlend(primary, secondary, blend);
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
            disposeFlowerBatch();

            for (int x = 0; x < SIZE; x++) {
                Arrays.fill(featureMask[x], false);
                // lakeMask[x][z] is NOT cleared so lakes persist
            }
        }
    }

    private void generateFeatures() {
        FeatureGenerationResult result = generateFeatureSpawns(
                new FeatureGenerationInput(cx, cz, scale, biome, heights, riverSurface, featureMask, lakeMask,
                        manager.getSeed()));
        applyFeatureGenerationResult(result);
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

    private static void applyCavesAndRavines(float[][] heights, float[][] riverSurface, int cx, int cz, float scale,
                                             OpenSimplexNoise terrainNoise, long seed) {
        final double riverFreq = 0.0018;
        final double riverWidth = 0.06;
        final double riverBlendWidth = 0.12;
        final double riverDepth = 8.0;
        final double riverMaskFreq = 0.0009;
        final double riverMaskThreshold = 0.38;
        final float ridgeOffset = 0.55f;
        final float surfaceNoiseAmp = 0.2f;
        final float flowSlopeScale = 0.00075f;
        final float minRiverDepth = 1.8f;
        final float surfaceCarveRatio = 0.6f;

        for (int x = 0; x <= SIZE; x++) {
            for (int z = 0; z <= SIZE; z++) {
                double wx = (cx * SIZE + x) * scale;
                double wz = (cz * SIZE + z) * scale;

                double riverNoise = terrainNoise.eval(wx * riverFreq + 2200.0, wz * riverFreq - 1300.0);
                double warpNoise = terrainNoise.eval(wx * riverFreq * 1.6 - 500.0, wz * riverFreq * 1.6 + 900.0);
                double riverBand = Math.abs(riverNoise + warpNoise * 0.15);
                if (riverBand < riverBlendWidth) {
                    double riverMask = terrainNoise.eval(wx * riverMaskFreq - 3400.0, wz * riverMaskFreq + 2600.0);
                    double maskValue = Math.abs(riverMask);
                    double maskBlend = 1.0 - smoothstep(riverMaskThreshold, riverMaskThreshold + 0.2, maskValue);
                    if (maskBlend > 0.001) {
                        double t = (riverBlendWidth - riverBand) / riverBlendWidth;
                        double bankBlend = t * t * (3.0 - 2.0 * t) * maskBlend;
                        float localSlope = computeSlope(heights, x, z);
                        float slopeBoost = Math.min(1.0f, localSlope * 0.9f);
                        double localWidth = riverWidth
                                * (0.76 + 0.28 * (terrainNoise.eval(wx * 0.002, wz * 0.002) * 0.5 + 0.5))
                                * (1.0 + 0.6 * slopeBoost);
                        double depthFactor = riverBand < localWidth ? (localWidth - riverBand) / localWidth : 0.0;
                        depthFactor = depthFactor * depthFactor * (3.0 - 2.0 * depthFactor);
                        depthFactor = Math.max(depthFactor, bankBlend * 0.2) * maskBlend;
                        float baseHeight = heights[x][z];
                        float surfaceNoise = (float) (terrainNoise.eval(wx * 0.00012 + 1200.0, wz * 0.00012 - 800.0) * surfaceNoiseAmp);
                        double flowAngle = terrainNoise.eval(wx * 0.0006 - 2000.0, wz * 0.0006 + 1500.0) * Math.PI;
                        double flowX = Math.cos(flowAngle);
                        double flowZ = Math.sin(flowAngle);
                        float flowCoord = (float) (wx * flowX + wz * flowZ);
                        float flowSlope = flowCoord * flowSlopeScale;
                        float downhillSurface = (float) (terrainNoise.eval(wx * 0.00015 + 5000.0, wz * 0.00015 - 5000.0) * 4.0)
                                - flowSlope;
                        float depthNoise = (float) (terrainNoise.eval(wx * 0.004 - 2200.0, wz * 0.004 + 1900.0) * 0.5 + 0.5);
                        float carveDepth = (float) (riverDepth * (0.7f + 0.6f * depthNoise) * depthFactor);
                        carveDepth = Math.max(carveDepth, minRiverDepth * (float) Math.max(0.15, depthFactor));
                        float bedHeight = baseHeight - carveDepth;
                        float surfaceFromCarve = baseHeight - carveDepth * surfaceCarveRatio;
                        float riverSurfaceHeight = Math.min(baseHeight - ridgeOffset * 0.35f,
                                Math.min(surfaceFromCarve + 0.2f, downhillSurface + surfaceNoise));
                        riverSurfaceHeight = Math.max(WATER_LEVEL, riverSurfaceHeight);
                        float target = Math.min(bedHeight, riverSurfaceHeight - minRiverDepth);
                        float blended = (float) (baseHeight * (1.0 - bankBlend) + target * bankBlend);
                        heights[x][z] = blended;
                        if (riverSurface != null && bankBlend > 0.0) {
                            riverSurface[x][z] = riverSurfaceHeight;
                        }
                    }
                }
            }
        }
    }

    private static void resetRiverSurface(float[][] riverSurface) {
        for (int x = 0; x < riverSurface.length; x++) {
            Arrays.fill(riverSurface[x], Float.NEGATIVE_INFINITY);
        }
    }

    private static double smoothstep(double edge0, double edge1, double x) {
        double t = Math.max(0.0, Math.min(1.0, (x - edge0) / (edge1 - edge0)));
        return t * t * (3.0 - 2.0 * t);
    }

    private float computeSlope(int x, int z) {
        return computeSlope(heights, x, z);
    }

    private static float computeSlope(float[][] heights, int x, int z) {
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
        return pickTextureForBiome(biome, height, slope, manager);
    }

    private static int pickTextureForBiome(Biome targetBiome, float height, float slope, TerrainManager manager) {
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
            return manager.getTexture(targetBiome.rockTex);
        }
        if (slope > DIRT_SLOPE_START) {
            return manager.getTexture(targetBiome.dirtTex);
        }

        return manager.getTexture(targetBiome.grassTex);
    }


    private boolean isRiverCell(int x, int z) {
        return riverSurface[x][z] > Float.NEGATIVE_INFINITY / 2;
    }

    private boolean isRiverBed(int x, int z, float height) {
        if (!isRiverCell(x, z)) {
            return false;
        }
        return height <= riverSurface[x][z] + 0.02f;
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
        disposeFlowerBatch();
    }

    public void refreshAfterNeighborUpdate() {
        stitchEdges();
        buildTerrainBuffers();
        buildWaterDisplayList();
        renderResourcesBuilt = true;
    }

    public void markRenderDirty() {
        renderResourcesBuilt = false;
    }

    public boolean needsRenderResources() {
        return !renderResourcesBuilt;
    }

    public void buildRenderResources() {
        if (renderResourcesBuilt) {
            return;
        }
        stitchEdges();
        buildTerrainBuffers();
        buildWaterDisplayList();
        buildFeatureBatches();
        renderResourcesBuilt = true;
    }

    public OpenSimplexNoise getTerrainNoise() {
        return terrainNoise;
    }

    public float getScale() {
        return scale;
    }

    private void applyPrecomputedData(ChunkBuildData data) {
        copyHeightsInto(data.heights, heights);
        copyMaskInto(data.featureMask, featureMask);
        copyMaskInto(data.lakeMask, lakeMask);
        if (data.riverSurface != null) {
            copyHeightsInto(data.riverSurface, riverSurface);
        } else {
            resetRiverSurface(riverSurface);
        }
        if (data.lakes != null) {
            features.addAll(data.lakes);
        }
        featuresGenerated = false;
    }

    private static float[][] copyHeights(float[][] source) {
        float[][] copy = new float[source.length][];
        for (int i = 0; i < source.length; i++) {
            copy[i] = Arrays.copyOf(source[i], source[i].length);
        }
        return copy;
    }

    private static boolean[][] copyMask(boolean[][] source) {
        boolean[][] copy = new boolean[source.length][];
        for (int i = 0; i < source.length; i++) {
            copy[i] = Arrays.copyOf(source[i], source[i].length);
        }
        return copy;
    }

    private static void copyHeightsInto(float[][] source, float[][] target) {
        for (int i = 0; i < source.length && i < target.length; i++) {
            System.arraycopy(source[i], 0, target[i], 0, Math.min(source[i].length, target[i].length));
        }
    }

    private static void copyMaskInto(boolean[][] source, boolean[][] target) {
        for (int i = 0; i < source.length && i < target.length; i++) {
            System.arraycopy(source[i], 0, target[i], 0, Math.min(source[i].length, target[i].length));
        }
    }


    public static FeatureGenerationResult generateFeatureSpawns(FeatureGenerationInput input) {
        List<FeatureSpawn> spawns = new ArrayList<>();
        if (input.biome.features == null || input.biome.features.isEmpty()) {
            return new FeatureGenerationResult(input.cx, input.cz, spawns, input.featureMask);
        }

        long chunkSeed = FeatureUtil.hashSeed(input.cx, 0, input.cz, input.seed);
        Random rand = new Random(chunkSeed);

        List<FeatureSpawner> spawners = new ArrayList<>();
        List<Float> weights = new ArrayList<>();
        List<Boolean> treeSpawners = new ArrayList<>();
        float totalWeight = 0f;
        for (Map.Entry<FeatureSpawner, Float> entry : input.biome.features.entrySet()) {
            FeatureSpawner spawner = entry.getKey();
            if (spawner instanceof LakeSpawner) {
                continue;
            }
            float weight = entry.getValue();
            if (weight <= 0f) {
                continue;
            }
            spawners.add(spawner);
            weights.add(weight);
            treeSpawners.add(spawner.getClass().getSimpleName().contains("TreeSpawner"));
            totalWeight += weight;
        }

        if (spawners.isEmpty()) {
            return new FeatureGenerationResult(input.cx, input.cz, spawns, input.featureMask);
        }

        final int sampleStep = 4;
        final float sampleArea = sampleStep * sampleStep;
        for (int gx = 0; gx < SIZE; gx += sampleStep) {
            for (int gz = 0; gz < SIZE; gz += sampleStep) {
                int x = Math.min(SIZE - 1, gx + rand.nextInt(sampleStep));
                int z = Math.min(SIZE - 1, gz + rand.nextInt(sampleStep));

                if (input.featureMask[x][z] || input.lakeMask[x][z]) {
                    continue;
                }
                if (input.riverSurface != null && input.riverSurface[x][z] > Float.NEGATIVE_INFINITY / 2) {
                    continue;
                }

                float height = input.heights[x][z];
                if (height < FEATURE_MIN_HEIGHT || height > FEATURE_MAX_HEIGHT) {
                    continue;
                }

                float slope = computeSlope(input.heights, x, z);
                if (slope > FEATURE_SLOPE_SPAWN_THRESHOLD) {
                    continue;
                }

                float spawnChance = Math.min(1f, totalWeight * sampleArea);
                if (rand.nextFloat() > spawnChance) {
                    continue;
                }

                float roll = rand.nextFloat() * totalWeight;
                FeatureSpawner spawner = null;
                boolean isTreeSpawner = false;
                float accum = 0f;
                for (int i = 0; i < spawners.size(); i++) {
                    accum += weights.get(i);
                    if (accum >= roll) {
                        spawner = spawners.get(i);
                        isTreeSpawner = treeSpawners.get(i);
                        break;
                    }
                }
                if (spawner == null) {
                    continue;
                }
                if (isTreeSpawner && height > FEATURE_TREE_MAX_HEIGHT) {
                    continue;
                }

                float wx = (input.cx * SIZE + x + 0.5f) * input.scale;
                float wz = (input.cz * SIZE + z + 0.5f) * input.scale;
                float slopeAdjustment = slope * 2.0f;
                float wy = height - slopeAdjustment;

                int ix = input.cx * SIZE + x;
                int iz = input.cz * SIZE + z;
                int iy = Math.round(height * 1000);
                long seed = FeatureUtil.hashSeed(ix, iy, iz, input.seed);

                spawns.add(new FeatureSpawn(spawner, wx, wy, wz, seed));
                input.featureMask[x][z] = true;
            }
        }

        return new FeatureGenerationResult(input.cx, input.cz, spawns, input.featureMask);
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

    private void disposeFlowerBatch() {
        if (flowerBatchVbo != -1) {
            glDeleteBuffers(flowerBatchVbo);
            flowerBatchVbo = -1;
        }
        flowerBatchVertexCount = 0;
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

    private static final class BatchKey {
        private final int textureId;
        private final int alphaBucket;

        private BatchKey(int textureId, int alphaBucket) {
            this.textureId = textureId;
            this.alphaBucket = alphaBucket;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            BatchKey other = (BatchKey) obj;
            return textureId == other.textureId && alphaBucket == other.alphaBucket;
        }

        @Override
        public int hashCode() {
            return 31 * textureId + alphaBucket;
        }
    }

    private static final class TerrainBatch {
        private final int textureId;
        private final int vboId;
        private final int vertexCount;
        private final float alpha;

        private TerrainBatch(int textureId, int vboId, int vertexCount, float alpha) {
            this.textureId = textureId;
            this.vboId = vboId;
            this.vertexCount = vertexCount;
            this.alpha = alpha;
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
