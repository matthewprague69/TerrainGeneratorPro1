package game;
import objects.Feature;
import renderers.SkyRenderer;
import util.BoundingBox;
import util.TextureLoader;
import static org.lwjgl.opengl.GL11.*;
import org.lwjgl.BufferUtils;
import java.nio.FloatBuffer;


import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TerrainManager {
    private static final int MAX_CHUNKS_PER_FRAME = 12;
    private static final int MAX_FEATURE_CHUNKS_PER_FRAME = 8;
    private static final long CHUNK_BUDGET_NS = 4_000_000L;
    private static final long FEATURE_BUDGET_NS = 6_000_000L;
    private static final int MAX_APPLIED_CHUNKS_PER_FRAME = 4;
    private static final int MAX_APPLIED_FEATURES_PER_FRAME = 6;
    private static final int MAX_RENDER_BUILDS_PER_FRAME = 2;
    private static final long RENDER_BUILD_BUDGET_NS = 3_000_000L;

    private final Map<Long, Chunk> chunks = new HashMap<>();
    private final ArrayDeque<Long> pendingChunks = new ArrayDeque<>();
    private final Map<Long, Integer> pendingChunkLods = new HashMap<>();
    private final ArrayDeque<Chunk> pendingFeatureChunks = new ArrayDeque<>();
    private final Set<Long> pendingFeatureKeys = new HashSet<>();
    private final Set<Long> neededKeys = new HashSet<>();
    private final Set<Long> inflightChunkKeys = new HashSet<>();
    private final Set<Long> inflightFeatureKeys = new HashSet<>();
    private final ConcurrentLinkedQueue<Chunk.ChunkBuildData> completedChunkBuilds = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Chunk.FeatureGenerationResult> completedFeatureGenerations =
            new ConcurrentLinkedQueue<>();
    private final ArrayDeque<Chunk> pendingRenderBuilds = new ArrayDeque<>();
    private final Set<Long> pendingRenderBuildKeys = new HashSet<>();
    private final ExecutorService chunkGenerator = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "chunk-generator");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService featureGenerator = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "feature-generator");
        t.setDaemon(true);
        return t;
    });
    private final OpenSimplexNoise terrainNoise;
    private final BiomeRegionGenerator regionGenerator;
    private final SkyRenderer skyRenderer;
    private final FloatBuffer fogColorBuffer = BufferUtils.createFloatBuffer(4);


    private final float scale;
    private int renderDist;
    private int featureRenderDist;
    private int shadowRenderDist;
    private int cacheRenderDist;
    private int cacheFeatureRenderDist;
    private int featureSimplifiedDistance;
    private int grassDetailDistance;
    private boolean renderDistanceDirty = true;
    private int lastUpdateChunkX = Integer.MIN_VALUE;
    private int lastUpdateChunkZ = Integer.MIN_VALUE;
    private final long seed;

    private final Map<String, Integer> textureMap = new HashMap<>();
    private final int snowTex;
    private final int waterBottomTex;
    private final int waterBottomAbsTex;

    public TerrainManager(long seed, float scale, int renderDist, SkyRenderer skyRenderer) {
        this(seed, scale, renderDist, renderDist - 1,  skyRenderer);
    }

    public TerrainManager(long seed, float scale, int renderDist, int featureRenderDist,SkyRenderer skyRenderer) {
        this.seed = seed;
        this.terrainNoise = new OpenSimplexNoise(seed);
        this.scale = scale;
        this.renderDist = renderDist;
        this.featureRenderDist = featureRenderDist;
        this.shadowRenderDist = renderDist + 12;
        this.cacheRenderDist = renderDist + 4;
        this.cacheFeatureRenderDist = featureRenderDist + 4;
        this.featureSimplifiedDistance = Math.max(1, featureRenderDist - 1);
        this.grassDetailDistance = Math.max(1, featureRenderDist - 2);
        this.regionGenerator = new BiomeRegionGenerator(seed);


        snowTex = TextureLoader.getOrLoad("snow.png");
        waterBottomTex = TextureLoader.getOrLoad("sand.png");
        waterBottomAbsTex = TextureLoader.getOrLoad("water_bottom.png");


        for (Biome b : Biome.values()) {
            textureMap.put(b.grassTex, TextureLoader.getOrLoad(b.grassTex));
            textureMap.put(b.dirtTex, TextureLoader.getOrLoad(b.dirtTex));
            textureMap.put(b.rockTex, TextureLoader.getOrLoad(b.rockTex));
        }
        this.skyRenderer = skyRenderer;
    }

    private long key(int cx, int cz) {
        return (((long) cx) << 32) | (cz & 0xffffffffL);
    }
/*EVEN BIOMES NOT FINNISHED
    private Biome pickBiome(int cx, int cz) {
        return regionGenerator.getBiomeAtChunk(cx, cz);
    }*/
    private Biome pickBiome(int cx, int cz) {
        double wx = (cx * Chunk.SIZE + Chunk.SIZE / 2.0) * scale;
        double wz = (cz * Chunk.SIZE + Chunk.SIZE / 2.0) * scale;
        return regionGenerator.getDominantBiome(wx, wz);
    }

    public List<Feature> getNearbyFeatures(float wx, float wz, int chunkRadius) {
        int cx = (int) (wx / (Chunk.SIZE * scale));
        int cz = (int) (wz / (Chunk.SIZE * scale));
        List<Feature> results = new ArrayList<>();

        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                Chunk chunk = getChunk(cx + dx, cz + dz);
                if (chunk != null) {
                    results.addAll(chunk.getFeatures());
                }
            }
        }

        return results;
    }

    public void update(float wx, float wz, Frustum frustum) {

        int pcx = (int) Math.floor(wx / (Chunk.SIZE * scale));
        int pcz = (int) Math.floor(wz / (Chunk.SIZE * scale));
        drainCompletedChunkBuilds(pcx, pcz);
        drainCompletedFeatureGenerations(pcx, pcz);
        processPendingRenderBuilds(pcx, pcz);
        if (renderDistanceDirty) {
            rebuildNeededChunks(pcx, pcz);
            reprioritizePendingQueues(pcx, pcz);
            lastUpdateChunkX = pcx;
            lastUpdateChunkZ = pcz;
            renderDistanceDirty = false;
        }
        boolean movedChunk = pcx != lastUpdateChunkX || pcz != lastUpdateChunkZ;
        if (!movedChunk) {
            processPendingChunkGenerations();
            processPendingFeatureGenerations(pcx, pcz);
            processPendingRenderBuilds(pcx, pcz);
            return;
        }
        int prevChunkX = lastUpdateChunkX;
        int prevChunkZ = lastUpdateChunkZ;
        lastUpdateChunkX = pcx;
        lastUpdateChunkZ = pcz;
        updateNeededChunks(pcx, pcz, prevChunkX, prevChunkZ);

        reprioritizePendingQueues(pcx, pcz);

        // Generate/unload features based on featureRenderDist
        for (Chunk c : chunks.values()) {
            c.unloadFeaturesIfOutOfRange(pcx, pcz, cacheFeatureRenderDist);
            int dist = Math.max(Math.abs(c.cx - pcx), Math.abs(c.cz - pcz));
            if (dist <= cacheFeatureRenderDist) {
                queueFeatureGeneration(c, pcx, pcz);
            }
        }

        // Dispose chunks no longer needed
        for (Iterator<Map.Entry<Long, Chunk>> it = chunks.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Long, Chunk> entry = it.next();
            if (!neededKeys.contains(entry.getKey())) {
                int cx = (int) (entry.getKey() >> 32);
                int cz = (int) entry.getKey().intValue();
                int dist = Math.max(Math.abs(cx - pcx), Math.abs(cz - pcz));
                if (dist <= cacheRenderDist) {
                    continue;
                }
                entry.getValue().dispose();
                it.remove();
            }
        }

        processPendingChunkGenerations();
        processPendingFeatureGenerations(pcx, pcz);
        processPendingRenderBuilds(pcx, pcz);
    }

    private void updateNeededChunks(int pcx, int pcz, int prevChunkX, int prevChunkZ) {
        if (neededKeys.isEmpty() || Math.abs(pcx - prevChunkX) > 1 || Math.abs(pcz - prevChunkZ) > 1) {
            rebuildNeededChunks(pcx, pcz);
            return;
        }

        int dx = pcx - prevChunkX;
        int dz = pcz - prevChunkZ;
        if (dx != 0) {
            int newCol = pcx + renderDist * Integer.signum(dx);
            int oldCol = pcx - renderDist - Integer.signum(dx);
            for (int offset = -renderDist; offset <= renderDist; offset++) {
                addNeededChunk(newCol, pcz + offset, pcx, pcz);
                removeNeededChunk(oldCol, pcz + offset);
            }
        }

        if (dz != 0) {
            int newRow = pcz + renderDist * Integer.signum(dz);
            int oldRow = pcz - renderDist - Integer.signum(dz);
            for (int offset = -renderDist; offset <= renderDist; offset++) {
                addNeededChunk(pcx + offset, newRow, pcx, pcz);
                removeNeededChunk(pcx + offset, oldRow);
            }
        }
    }

    private void rebuildNeededChunks(int pcx, int pcz) {
        neededKeys.clear();

        for (int dx = -renderDist; dx <= renderDist; dx++) {
            for (int dz = -renderDist; dz <= renderDist; dz++) {
                addNeededChunk(pcx + dx, pcz + dz, pcx, pcz);
            }
        }
    }

    private void addNeededChunk(int cx, int cz, int pcx, int pcz) {
        long k = key(cx, cz);
        neededKeys.add(k);

        Chunk existing = chunks.get(k);
        int dist = Math.max(Math.abs(cx - pcx), Math.abs(cz - pcz));
        int targetLOD = 0;

        if (existing == null) {
            queueChunkGeneration(k, cx, cz, targetLOD, dist);
        } else if (existing.getLOD() != targetLOD) {
            queueChunkGeneration(k, cx, cz, targetLOD, dist);
        }
    }

    private void removeNeededChunk(int cx, int cz) {
        neededKeys.remove(key(cx, cz));
    }

    public Chunk getChunk(int cx, int cz) {
        return chunks.get(key(cx, cz));
    }

    private void queueChunkGeneration(long key, int cx, int cz, int targetLOD, int dist) {
        Integer existing = pendingChunkLods.get(key);
        if (existing != null) {
            pendingChunkLods.put(key, Math.min(existing, targetLOD));
            if (!pendingChunks.contains(key)) {
                if (dist <= 2) {
                    pendingChunks.addFirst(key);
                } else {
                    pendingChunks.addLast(key);
                }
            }
            return;
        }
        pendingChunkLods.put(key, targetLOD);
        if (dist <= 2) {
            pendingChunks.addFirst(key);
        } else {
            pendingChunks.addLast(key);
        }
    }

    private void reprioritizePendingQueues(int pcx, int pcz) {
        if (!pendingChunks.isEmpty()) {
            ArrayDeque<Long> near = new ArrayDeque<>();
            ArrayDeque<Long> far = new ArrayDeque<>();
            Iterator<Long> iterator = pendingChunks.iterator();
            while (iterator.hasNext()) {
                long key = iterator.next();
                int cx = (int) (key >> 32);
                int cz = (int) key;
                int dist = Math.max(Math.abs(cx - pcx), Math.abs(cz - pcz));
                if (dist > cacheRenderDist) {
                    pendingChunkLods.remove(key);
                    iterator.remove();
                    continue;
                }
                if (dist <= 2) {
                    near.addLast(key);
                } else {
                    far.addLast(key);
                }
                iterator.remove();
            }
            pendingChunks.addAll(near);
            pendingChunks.addAll(far);
        }

        if (!pendingFeatureChunks.isEmpty()) {
            ArrayDeque<Chunk> near = new ArrayDeque<>();
            ArrayDeque<Chunk> far = new ArrayDeque<>();
            Iterator<Chunk> iterator = pendingFeatureChunks.iterator();
            while (iterator.hasNext()) {
                Chunk chunk = iterator.next();
                long key = key(chunk.cx, chunk.cz);
                if (chunks.get(key) != chunk) {
                    pendingFeatureKeys.remove(key);
                    iterator.remove();
                    continue;
                }
                int dist = Math.max(Math.abs(chunk.cx - pcx), Math.abs(chunk.cz - pcz));
                if (dist > cacheFeatureRenderDist || !chunk.needsFeatureGeneration(pcx, pcz, featureRenderDist)) {
                    pendingFeatureKeys.remove(key);
                    iterator.remove();
                    continue;
                }
                if (dist <= 2) {
                    near.addLast(chunk);
                } else {
                    far.addLast(chunk);
                }
                iterator.remove();
            }
            pendingFeatureChunks.addAll(near);
            pendingFeatureChunks.addAll(far);
        }
    }

    private void processPendingChunkGenerations() {
        int backlog = pendingChunks.size();
        int budget = MAX_CHUNKS_PER_FRAME + Math.min(8, backlog / 10);
        int count = 0;
        long start = System.nanoTime();
        while (count < budget && !pendingChunks.isEmpty()) {
            if (System.nanoTime() - start > CHUNK_BUDGET_NS) {
                break;
            }
            long key = pendingChunks.pollFirst();
            Integer pendingLod = pendingChunkLods.remove(key);
            if (pendingLod == null) {
                continue;
            }
            if (inflightChunkKeys.contains(key)) {
                pendingChunkLods.put(key, pendingLod);
                if (!pendingChunks.contains(key)) {
                    pendingChunks.addFirst(key);
                }
                continue;
            }
            int cx = (int) (key >> 32);
            int cz = (int) key;
            int targetLOD = pendingLod;
            Biome b = pickBiome(cx, cz);
            Chunk existing = chunks.get(key);
            if (existing != null && targetLOD >= existing.getLOD()) {
                continue;
            }
            inflightChunkKeys.add(key);
            chunkGenerator.submit(() -> {
                Chunk.ChunkBuildData data =
                        Chunk.generateChunkData(cx, cz, terrainNoise, scale, b, this, targetLOD);
                completedChunkBuilds.add(data);
            });
            count++;
        }
    }

    private void queueFeatureGeneration(Chunk chunk, int pcx, int pcz) {
        long key = key(chunk.cx, chunk.cz);
        if (pendingFeatureKeys.contains(key) || inflightFeatureKeys.contains(key)) {
            return;
        }
        if (!chunk.needsFeatureGeneration(pcx, pcz, featureRenderDist)) {
            return;
        }
        pendingFeatureKeys.add(key);
        int dist = Math.max(Math.abs(chunk.cx - pcx), Math.abs(chunk.cz - pcz));
        if (dist <= 2) {
            pendingFeatureChunks.addFirst(chunk);
        } else {
            pendingFeatureChunks.addLast(chunk);
        }
    }

    private void processPendingFeatureGenerations(int pcx, int pcz) {
        int backlog = pendingFeatureChunks.size();
        int budget = MAX_FEATURE_CHUNKS_PER_FRAME + Math.min(6, backlog / 12);
        int count = 0;
        long start = System.nanoTime();
        while (count < budget && !pendingFeatureChunks.isEmpty()) {
            if (System.nanoTime() - start > FEATURE_BUDGET_NS) {
                break;
            }
            Chunk chunk = pendingFeatureChunks.poll();
            long key = key(chunk.cx, chunk.cz);
            pendingFeatureKeys.remove(key);
            if (chunks.get(key) != chunk) {
                continue;
            }
            if (inflightFeatureKeys.contains(key)) {
                continue;
            }
            if (!chunk.needsFeatureGeneration(pcx, pcz, featureRenderDist)) {
                continue;
            }
            Chunk.FeatureGenerationInput input = chunk.createFeatureGenerationInput();
            inflightFeatureKeys.add(key);
            featureGenerator.submit(() -> {
                Chunk.FeatureGenerationResult result = Chunk.generateFeatureSpawns(input);
                completedFeatureGenerations.add(result);
            });
            count++;
        }
    }

    private void drainCompletedChunkBuilds(int pcx, int pcz) {
        Chunk.ChunkBuildData data;
        int applied = 0;
        while (applied < MAX_APPLIED_CHUNKS_PER_FRAME && (data = completedChunkBuilds.poll()) != null) {
            long key = key(data.cx, data.cz);
            inflightChunkKeys.remove(key);
            Integer pendingLod = pendingChunkLods.get(key);
            if (pendingLod != null && pendingLod < data.lod) {
                if (!pendingChunks.contains(key)) {
                    pendingChunks.addFirst(key);
                }
            }
            int dist = Math.max(Math.abs(data.cx - pcx), Math.abs(data.cz - pcz));
            if (dist > cacheRenderDist) {
                continue;
            }
            Chunk existing = chunks.get(key);
            if (existing != null && data.lod >= existing.getLOD()) {
                continue;
            }
            Chunk built = new Chunk(data.cx, data.cz, terrainNoise, scale, data.biome, this, data.lod, data);
            if (existing != null) {
                existing.dispose();
            }
            chunks.put(key, built);
            if (built.needsRenderResources()) {
                queueRenderBuild(built, dist);
            }
            if (built.needsFeatureGeneration(pcx, pcz, featureRenderDist)) {
                queueFeatureGeneration(built, pcx, pcz);
            }
            refreshNeighborEdges(built, pcx, pcz);
            applied++;
        }
    }

    private void refreshNeighborEdges(Chunk chunk, int pcx, int pcz) {
        Chunk right = getChunk(chunk.cx + 1, chunk.cz);
        if (right != null) {
            right.markRenderDirty();
            int dist = Math.max(Math.abs(right.cx - pcx), Math.abs(right.cz - pcz));
            queueRenderBuild(right, dist);
        }
        Chunk bottom = getChunk(chunk.cx, chunk.cz + 1);
        if (bottom != null) {
            bottom.markRenderDirty();
            int dist = Math.max(Math.abs(bottom.cx - pcx), Math.abs(bottom.cz - pcz));
            queueRenderBuild(bottom, dist);
        }
        Chunk bottomRight = getChunk(chunk.cx + 1, chunk.cz + 1);
        if (bottomRight != null) {
            bottomRight.markRenderDirty();
            int dist = Math.max(Math.abs(bottomRight.cx - pcx), Math.abs(bottomRight.cz - pcz));
            queueRenderBuild(bottomRight, dist);
        }
    }

    private void queueRenderBuild(Chunk chunk, int dist) {
        long key = key(chunk.cx, chunk.cz);
        if (pendingRenderBuildKeys.contains(key)) {
            return;
        }
        pendingRenderBuildKeys.add(key);
        if (dist <= 2) {
            pendingRenderBuilds.addFirst(chunk);
        } else {
            pendingRenderBuilds.addLast(chunk);
        }
    }

    private void processPendingRenderBuilds(int pcx, int pcz) {
        int built = 0;
        long start = System.nanoTime();
        while (built < MAX_RENDER_BUILDS_PER_FRAME && !pendingRenderBuilds.isEmpty()) {
            if (System.nanoTime() - start > RENDER_BUILD_BUDGET_NS) {
                break;
            }
            Chunk chunk = pendingRenderBuilds.poll();
            long key = key(chunk.cx, chunk.cz);
            pendingRenderBuildKeys.remove(key);
            if (chunks.get(key) != chunk) {
                continue;
            }
            int dist = Math.max(Math.abs(chunk.cx - pcx), Math.abs(chunk.cz - pcz));
            if (dist > cacheRenderDist) {
                continue;
            }
            chunk.buildRenderResources();
            built++;
        }
    }

    private void drainCompletedFeatureGenerations(int pcx, int pcz) {
        Chunk.FeatureGenerationResult result;
        int applied = 0;
        while (applied < MAX_APPLIED_FEATURES_PER_FRAME
                && (result = completedFeatureGenerations.poll()) != null) {
            long key = key(result.cx, result.cz);
            inflightFeatureKeys.remove(key);
            Chunk chunk = chunks.get(key);
            if (chunk == null) {
                continue;
            }
            if (!chunk.needsFeatureGeneration(pcx, pcz, featureRenderDist)) {
                continue;
            }
            chunk.applyFeatureGenerationResult(result);
            applied++;
        }
    }

    public float getHeight(float wx, float wz) {
        int cx = (int) Math.floor(wx / (Chunk.SIZE * scale));
        int cz = (int) Math.floor(wz / (Chunk.SIZE * scale));
        Chunk c = chunks.get(key(cx, cz));
        return c != null ? c.getHeight(wx / scale, wz / scale) : 0f;
    }

    public void drawTerrainAndFeatures(float wx, float wz) {
        enableFogDynamic();

        int pcx = (int) Math.floor(wx / (Chunk.SIZE * scale));
        int pcz = (int) Math.floor(wz / (Chunk.SIZE * scale));
        int featureDetailDistance = Math.min(featureSimplifiedDistance, featureRenderDist);
        int grassDetailDistance = Math.min(this.grassDetailDistance, featureRenderDist);

        for (Chunk c : chunks.values()) {
            int dist = Math.max(Math.abs(c.cx - pcx), Math.abs(c.cz - pcz));
            if (dist > renderDist) {
                continue;
            }
            c.drawTerrainAndFeatures(dist, featureDetailDistance, grassDetailDistance);
        }

        disableFog();
    }

    public void drawWater(float wx, float wz) {
        int pcx = (int) Math.floor(wx / (Chunk.SIZE * scale));
        int pcz = (int) Math.floor(wz / (Chunk.SIZE * scale));
        for (Chunk c : chunks.values()) {
            int dist = Math.max(Math.abs(c.cx - pcx), Math.abs(c.cz - pcz));
            if (dist > renderDist) {
                continue;
            }
            c.drawWater();
        }
    }

    public void drawDepth(float wx, float wz) {
        int pcx = (int) Math.floor(wx / (Chunk.SIZE * scale));
        int pcz = (int) Math.floor(wz / (Chunk.SIZE * scale));
        int featureDetailDistance = Math.min(featureSimplifiedDistance, featureRenderDist);
        int grassDetailDistance = Math.min(this.grassDetailDistance, featureRenderDist);

        for (Chunk c : chunks.values()) {
            int dist = Math.max(Math.abs(c.cx - pcx), Math.abs(c.cz - pcz));
            if (dist > shadowRenderDist) {
                continue;
            }
            c.renderDepth(dist, featureDetailDistance, grassDetailDistance);
        }
    }

    private void enableFogDynamic() {
        glEnable(GL_FOG);
        glFogi(GL_FOG_MODE, GL_LINEAR);

        float time = skyRenderer.getTimeOfDay();
        float brightness = getFogBrightness(time);

        float fogEnd = Math.max(0f, renderDist * Chunk.SIZE * scale);
        float fogStart = Math.max(0f, (renderDist - 1f) * Chunk.SIZE * scale);
        glFogf(GL_FOG_START, fogStart);
        glFogf(GL_FOG_END, fogEnd);

        // --- New: match fog color to sky color ---
        float r = 0.6f * brightness;
        float g = 0.75f * brightness;
        float b = 1.0f * brightness;

        fogColorBuffer.clear();
        fogColorBuffer.put(r).put(g).put(b).put(1f).flip();
        glFogfv(GL_FOG_COLOR, fogColorBuffer);

        glHint(GL_FOG_HINT, GL_NICEST);
    }

    public float[] getFogSettings() {
        float time = skyRenderer.getTimeOfDay();
        float brightness = getFogBrightness(time);
        float fogEnd = Math.max(0f, renderDist * Chunk.SIZE * scale);
        float fogStart = Math.max(0f, (renderDist - 1f) * Chunk.SIZE * scale);
        float r = 0.6f * brightness;
        float g = 0.75f * brightness;
        float b = 1.0f * brightness;
        return new float[] { fogStart, fogEnd, r, g, b };
    }


    private float getFogBrightness(float time) {
        if (time > 0.2f && time < 0.3f) {
            return smoothstep(0.2f, 0.3f, time); // sunrise
        } else if (time > 0.7f && time < 0.8f) {
            return 1f - smoothstep(0.7f, 0.8f, time); // sunset
        } else if (time >= 0.3f && time <= 0.7f) {
            return 1f; // day
        } else {
            return 0f; // night
        }
    }

    private float smoothstep(float edge0, float edge1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3f - 2f * t);
    }



    private void disableFog() {
        glDisable(GL_FOG);
    }

    public float getScale() {
        return scale;
    }

    public Map<Biome, Float> getBiomeWeights(double wx, double wz) {
        return regionGenerator.getBiomeWeights(wx, wz);
    }

    public Biome getDominantBiome(double wx, double wz) {
        return getBiomeWeights(wx, wz).entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(Biome.PLAINS);
    }

    public int getTexture(String name) {
        return textureMap.getOrDefault(name, 0);
    }
    public int getWaterBottomTexture() {
        return waterBottomTex;
    }
    public int getWaterBottomAbsTexture() {
        return waterBottomAbsTex;
    }

    public void setRenderDistance(int r) {
        System.out.println("Render distance set to " + r);
        renderDist = Math.max(1, r);
        cacheRenderDist = renderDist + 2;
        shadowRenderDist = Math.max(shadowRenderDist, renderDist + 12);
        renderDistanceDirty = true;
    }

    public int getRenderDistance() {
        return renderDist;
    }

    public void setShadowRenderDistance(int r) {
        shadowRenderDist = Math.max(1, r);
    }

    public int getShadowRenderDistance() {
        return shadowRenderDist;
    }

    public void setFeatureRenderDistance(int r) {
        System.out.println("Feature render distance set to " + r);
        featureRenderDist = Math.max(0, r);
        cacheFeatureRenderDist = featureRenderDist + 2;
        shadowRenderDist = Math.max(shadowRenderDist, renderDist + 12);
        featureSimplifiedDistance = Math.min(featureSimplifiedDistance, featureRenderDist);
        grassDetailDistance = Math.min(grassDetailDistance, featureRenderDist);
        renderDistanceDirty = true;
    }

    public int getFeatureRenderDistance() {
        return featureRenderDist;
    }

    public void setFeatureSimplifiedDistance(int r) {
        featureSimplifiedDistance = Math.max(0, Math.min(r, featureRenderDist));
    }

    public int getFeatureSimplifiedDistance() {
        return featureSimplifiedDistance;
    }

    public void setGrassDetailDistance(int r) {
        grassDetailDistance = Math.max(0, Math.min(r, featureRenderDist));
    }

    public int getGrassDetailDistance() {
        return grassDetailDistance;
    }

    public int getSnowTexture() {
        return snowTex;
    }

    public Biome getBiome(int wcx, int wcz) {
        return pickBiome(wcx, wcz);
    }

    public long getSeed() {
        return seed;
    }

    public float[] getShadowDirection() {
        return skyRenderer.getShadowDirection();
    }

    public float getShadowStrength() {
        return skyRenderer.getShadowStrength();
    }
}
