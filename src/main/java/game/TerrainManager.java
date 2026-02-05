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
    private static final int MAX_CHUNKS_PER_FRAME = 20;
    private static final int MAX_FEATURE_CHUNKS_PER_FRAME = 4;
    private static final long CHUNK_BUDGET_NS = 10_000_000L;
    private static final long FEATURE_BUDGET_NS = 3_000_000L;
    private static final int MAX_APPLIED_CHUNKS_PER_FRAME = 8;
    private static final int MAX_APPLIED_FEATURES_PER_FRAME = 6;
    private static final int MAX_RENDER_BUILDS_PER_FRAME = 2;
    private static final long RENDER_BUILD_BUDGET_NS = 3_000_000L;
    private static final int LOD_NEAR_THRESHOLD = 16;
    private static final int LOD_MID_THRESHOLD = 24;
    private static final int LOD_FAR_THRESHOLD = 32;
    private static final int LOD_PRIORITY_RADIUS = 2;
    private static final int LOD_PRIORITY_MIN_BUDGET = 6;

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
    private final Map<Long, Chunk> pendingChunkReplacements = new HashMap<>();
    private final Map<Long, float[][][]> biomeWeightCache = new HashMap<>();
    private final ExecutorService chunkGenerator = Executors.newFixedThreadPool(2, r -> {
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
    private int featureImpostorDistance;
    private int grassDetailDistance;
    private int impostorAngleCount = 1;
    private boolean renderDistanceDirty = true;
    private int lastUpdateChunkX = Integer.MIN_VALUE;
    private int lastUpdateChunkZ = Integer.MIN_VALUE;
    private final long seed;
    private static final int BIOME_WEIGHT_REGION_SIZE = 4;

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
        this.featureImpostorDistance = Math.max(1, featureRenderDist);
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
        processPendingRenderBuilds(pcx, pcz);
        drainCompletedFeatureGenerations(pcx, pcz);
        if (renderDistanceDirty) {
            rebuildNeededChunks(pcx, pcz);
            refreshChunkLods(pcx, pcz);
            reprioritizePendingQueues(pcx, pcz, frustum);
            lastUpdateChunkX = pcx;
            lastUpdateChunkZ = pcz;
            renderDistanceDirty = false;
        }
        boolean movedChunk = pcx != lastUpdateChunkX || pcz != lastUpdateChunkZ;
        if (!movedChunk) {
            refreshChunkLods(pcx, pcz);
            reprioritizePendingQueues(pcx, pcz, frustum);
            processPendingChunkGenerations(pcx, pcz, frustum);
            processPendingRenderBuilds(pcx, pcz);
            processPendingFeatureGenerations(pcx, pcz);
            return;
        }
        int prevChunkX = lastUpdateChunkX;
        int prevChunkZ = lastUpdateChunkZ;
        lastUpdateChunkX = pcx;
        lastUpdateChunkZ = pcz;
        updateNeededChunks(pcx, pcz, prevChunkX, prevChunkZ);

        reprioritizePendingQueues(pcx, pcz, frustum);

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

        processPendingChunkGenerations(pcx, pcz, frustum);
        processPendingRenderBuilds(pcx, pcz);
        processPendingFeatureGenerations(pcx, pcz);
    }

    private void updateNeededChunks(int pcx, int pcz, int prevChunkX, int prevChunkZ) {
        if (neededKeys.isEmpty() || Math.abs(pcx - prevChunkX) > 1 || Math.abs(pcz - prevChunkZ) > 1) {
            rebuildNeededChunks(pcx, pcz);
            refreshChunkLods(pcx, pcz);
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

        refreshChunkLods(pcx, pcz);
    }

    private void refreshChunkLods(int pcx, int pcz) {
        for (long key : neededKeys) {
            int cx = (int) (key >> 32);
            int cz = (int) key;
            int dist = Math.max(Math.abs(cx - pcx), Math.abs(cz - pcz));
            int targetLOD = computeTargetLod(dist);
            Chunk existing = chunks.get(key);
            if (existing != null && existing.getLOD() != targetLOD) {
                queueChunkGeneration(key, cx, cz, targetLOD, 0);
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
        int targetLOD = computeTargetLod(dist);

        if (existing == null) {
            queueChunkGeneration(k, cx, cz, targetLOD, dist);
        } else if (existing.getLOD() != targetLOD) {
            queueChunkGeneration(k, cx, cz, targetLOD, dist);
        }
    }

    private int computeTargetLod(int dist) {
        int nearThreshold = Math.max(LOD_NEAR_THRESHOLD, featureRenderDist);
        if (dist <= nearThreshold) {
            return 0;
        }
        if (dist > LOD_FAR_THRESHOLD) {
            return 3;
        }
        if (dist > LOD_MID_THRESHOLD) {
            return 2;
        }
        if (dist > nearThreshold) {
            return 1;
        }
        return 0;
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
            pendingChunkLods.put(key, targetLOD);
            pendingChunks.remove(key);
            if (dist <= LOD_PRIORITY_RADIUS) {
                pendingChunks.addFirst(key);
            } else {
                pendingChunks.addLast(key);
            }
            return;
        }
        pendingChunkLods.put(key, targetLOD);
        if (dist <= LOD_PRIORITY_RADIUS) {
            pendingChunks.addFirst(key);
        } else {
            pendingChunks.addLast(key);
        }
    }

    private void reprioritizePendingQueues(int pcx, int pcz, Frustum frustum) {
        if (!pendingChunks.isEmpty()) {
            ArrayDeque<Long> visibleNear = new ArrayDeque<>();
            ArrayDeque<Long> visibleFar = new ArrayDeque<>();
            ArrayDeque<Long> hiddenNear = new ArrayDeque<>();
            ArrayDeque<Long> hiddenFar = new ArrayDeque<>();
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
                boolean visible = frustum != null && isChunkVisible(frustum, cx, cz);
                if (dist <= 2) {
                    if (visible) {
                        visibleNear.addLast(key);
                    } else {
                        hiddenNear.addLast(key);
                    }
                } else {
                    if (visible) {
                        visibleFar.addLast(key);
                    } else {
                        hiddenFar.addLast(key);
                    }
                }
                iterator.remove();
            }
            pendingChunks.addAll(visibleNear);
            pendingChunks.addAll(visibleFar);
            pendingChunks.addAll(hiddenNear);
            pendingChunks.addAll(hiddenFar);
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

    private boolean isChunkVisible(Frustum frustum, int cx, int cz) {
        float minX = cx * Chunk.SIZE * scale;
        float minZ = cz * Chunk.SIZE * scale;
        float maxX = (cx + 1) * Chunk.SIZE * scale;
        float maxZ = (cz + 1) * Chunk.SIZE * scale;
        float minY = -200f;
        float maxY = 1000f;
        return frustum.isBoxVisible(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private void processPendingChunkGenerations(int pcx, int pcz, Frustum frustum) {
        int backlog = pendingChunks.size();
        int budget = MAX_CHUNKS_PER_FRAME + Math.min(8, backlog / 10);
        long start = System.nanoTime();

        Comparator<Long> priorityComparator = (a, b) -> {
            int cxA = (int) (a >> 32);
            int czA = (int) (long) a;
            int cxB = (int) (b >> 32);
            int czB = (int) (long) b;
            int distA = Math.max(Math.abs(cxA - pcx), Math.abs(czA - pcz));
            int distB = Math.max(Math.abs(cxB - pcx), Math.abs(czB - pcz));
            if (distA != distB) {
                return Integer.compare(distA, distB);
            }
            Chunk existingA = chunks.get(a);
            Chunk existingB = chunks.get(b);
            Integer pendingA = pendingChunkLods.get(a);
            Integer pendingB = pendingChunkLods.get(b);
            boolean upgradeA = existingA != null && pendingA != null && pendingA < existingA.getLOD();
            boolean upgradeB = existingB != null && pendingB != null && pendingB < existingB.getLOD();
            if (upgradeA != upgradeB) {
                return upgradeA ? -1 : 1;
            }
            return 0;
        };

        List<Long> nearVisible = new ArrayList<>();
        List<Long> nearHidden = new ArrayList<>();
        List<Long> farVisible = new ArrayList<>();
        List<Long> farHidden = new ArrayList<>();

        for (long key : pendingChunks) {
            int cx = (int) (key >> 32);
            int cz = (int) key;
            int dist = Math.max(Math.abs(cx - pcx), Math.abs(cz - pcz));
            boolean visible = frustum == null || isChunkVisible(frustum, cx, cz);
            if (dist <= LOD_PRIORITY_RADIUS) {
                if (visible) {
                    nearVisible.add(key);
                } else {
                    nearHidden.add(key);
                }
            } else {
                if (visible) {
                    farVisible.add(key);
                } else {
                    farHidden.add(key);
                }
            }
        }

        nearVisible.sort(priorityComparator);
        nearHidden.sort(priorityComparator);
        farVisible.sort(priorityComparator);
        farHidden.sort(priorityComparator);

        int processed = 0;
        int nearBudget = Math.min(budget, Math.max(LOD_PRIORITY_MIN_BUDGET, budget / 2));

        for (long key : nearVisible) {
            if (processed >= nearBudget || System.nanoTime() - start > CHUNK_BUDGET_NS) {
                break;
            }
            if (tryDispatchChunkGeneration(key, true)) {
                processed++;
            }
        }

        for (long key : nearHidden) {
            if (processed >= nearBudget || System.nanoTime() - start > CHUNK_BUDGET_NS) {
                break;
            }
            if (tryDispatchChunkGeneration(key, true)) {
                processed++;
            }
        }

        if (processed >= budget || System.nanoTime() - start > CHUNK_BUDGET_NS) {
            return;
        }

        for (long key : farVisible) {
            if (processed >= budget || System.nanoTime() - start > CHUNK_BUDGET_NS) {
                break;
            }
            if (tryDispatchChunkGeneration(key, false)) {
                processed++;
            }
        }

        if (processed >= budget || System.nanoTime() - start > CHUNK_BUDGET_NS) {
            return;
        }

        for (long key : farHidden) {
            if (processed >= budget || System.nanoTime() - start > CHUNK_BUDGET_NS) {
                break;
            }
            if (tryDispatchChunkGeneration(key, false)) {
                processed++;
            }
        }
    }

    private boolean tryDispatchChunkGeneration(long key, boolean prioritizeRequeue) {
        if (!pendingChunks.remove(key)) {
            return false;
        }
        Integer pendingLod = pendingChunkLods.remove(key);
        if (pendingLod == null) {
            return false;
        }
        if (inflightChunkKeys.contains(key)) {
            pendingChunkLods.put(key, pendingLod);
            if (prioritizeRequeue) {
                pendingChunks.addFirst(key);
            } else {
                pendingChunks.addLast(key);
            }
            return false;
        }
        int cx = (int) (key >> 32);
        int cz = (int) key;
        Chunk existing = chunks.get(key);
        if (existing != null && pendingLod >= existing.getLOD()) {
            return false;
        }
        Biome b = pickBiome(cx, cz);
        int targetLOD = pendingLod;
        inflightChunkKeys.add(key);
        chunkGenerator.submit(() -> {
            Chunk.ChunkBuildData data =
                    Chunk.generateChunkData(cx, cz, terrainNoise, scale, b, this, targetLOD);
            completedChunkBuilds.add(data);
        });
        return true;
    }

    private void queueFeatureGeneration(Chunk chunk, int pcx, int pcz) {
        long key = key(chunk.cx, chunk.cz);
        if (pendingFeatureKeys.contains(key) || inflightFeatureKeys.contains(key)) {
            return;
        }
        Integer pendingLod = pendingChunkLods.get(key);
        if (pendingLod != null && pendingLod != chunk.getLOD()) {
            return;
        }
        if (chunk.needsRenderResources()) {
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
            Integer pendingLod = pendingChunkLods.get(key);
            if (pendingLod != null && pendingLod != chunk.getLOD()) {
                continue;
            }
            if (!chunk.needsFeatureGeneration(pcx, pcz, featureRenderDist)) {
                continue;
            }
            if (chunk.needsRenderResources()) {
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
            if (existing != null && data.lod == existing.getLOD()) {
                continue;
            }
            Chunk built = new Chunk(data.cx, data.cz, terrainNoise, scale, data.biome, this, data.lod, data);
            if (existing != null && built.needsRenderResources()) {
                pendingChunkReplacements.put(key, built);
                queueRenderBuild(built, dist);
                continue;
            }
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
            Chunk pendingReplacement = pendingChunkReplacements.get(key);
            if (chunks.get(key) != chunk && pendingReplacement != chunk) {
                if (pendingReplacement != null) {
                    chunk.dispose();
                }
                continue;
            }
            int dist = Math.max(Math.abs(chunk.cx - pcx), Math.abs(chunk.cz - pcz));
            if (dist > cacheRenderDist) {
                if (pendingReplacement == chunk) {
                    pendingChunkReplacements.remove(key);
                    chunk.dispose();
                }
                continue;
            }
            chunk.buildRenderResources();
            if (pendingReplacement == chunk) {
                Chunk existing = chunks.get(key);
                if (existing != null) {
                    existing.dispose();
                }
                chunks.put(key, chunk);
                pendingChunkReplacements.remove(key);
                if (chunk.needsFeatureGeneration(pcx, pcz, featureRenderDist)) {
                    queueFeatureGeneration(chunk, pcx, pcz);
                }
                refreshNeighborEdges(chunk, pcx, pcz);
            } else {
                if (chunk.needsFeatureGeneration(pcx, pcz, featureRenderDist)) {
                    queueFeatureGeneration(chunk, pcx, pcz);
                }
            }
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
        int impostorDistance = Math.min(this.featureImpostorDistance, featureRenderDist);
        int grassDetailDistance = Math.min(this.grassDetailDistance, featureRenderDist);

        for (Chunk c : chunks.values()) {
            int dist = Math.max(Math.abs(c.cx - pcx), Math.abs(c.cz - pcz));
            if (dist > renderDist) {
                continue;
            }
            c.drawTerrainAndFeatures(dist, impostorDistance, grassDetailDistance,
                    featureRenderDist);
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
        int impostorDistance = Math.min(this.featureImpostorDistance, featureRenderDist);
        int grassDetailDistance = Math.min(this.grassDetailDistance, featureRenderDist);

        for (Chunk c : chunks.values()) {
            int dist = Math.max(Math.abs(c.cx - pcx), Math.abs(c.cz - pcz));
            if (dist > shadowRenderDist) {
                continue;
            }
            c.renderDepth(dist, impostorDistance, grassDetailDistance,
                    featureRenderDist);
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

    public float[][][] getBiomeWeightGridForChunk(int cx, int cz, int weightStep) {
        int regionCx = Math.floorDiv(cx, BIOME_WEIGHT_REGION_SIZE) * BIOME_WEIGHT_REGION_SIZE;
        int regionCz = Math.floorDiv(cz, BIOME_WEIGHT_REGION_SIZE) * BIOME_WEIGHT_REGION_SIZE;
        long key = key(regionCx, regionCz);
        float[][][] regionGrid = biomeWeightCache.get(key);
        int regionCells = BIOME_WEIGHT_REGION_SIZE * Chunk.SIZE;
        int gridSize = regionCells / weightStep + 1;
        if (regionGrid == null || regionGrid.length != gridSize) {
            Biome[] biomes = Biome.values();
            int biomeCount = biomes.length;
            regionGrid = new float[gridSize][gridSize][biomeCount];
            for (int gx = 0; gx < gridSize; gx++) {
                int lx = gx * weightStep;
                double wx = (regionCx * Chunk.SIZE + lx) * scale;
                for (int gz = 0; gz < gridSize; gz++) {
                    int lz = gz * weightStep;
                    double wz = (regionCz * Chunk.SIZE + lz) * scale;
                    Map<Biome, Float> weights = regionGenerator.getBiomeWeights(wx, wz);
                    for (int i = 0; i < biomeCount; i++) {
                        Float value = weights.get(biomes[i]);
                        regionGrid[gx][gz][i] = value == null ? 0f : value;
                    }
                }
            }
            biomeWeightCache.put(key, regionGrid);
        }

        int chunkSize = Chunk.SIZE;
        int chunkGridSize = (chunkSize + weightStep - 1) / weightStep + 1;
        float[][][] chunkGrid = new float[chunkGridSize][chunkGridSize][regionGrid[0][0].length];
        int offsetX = (cx - regionCx) * Chunk.SIZE;
        int offsetZ = (cz - regionCz) * Chunk.SIZE;
        for (int gx = 0; gx < chunkGridSize; gx++) {
            int chunkLx = Math.min(gx * weightStep, chunkSize);
            int worldLx = offsetX + chunkLx;
            int regionX = worldLx / weightStep;
            boolean sampleXOnGrid = (worldLx % weightStep) == 0;
            for (int gz = 0; gz < chunkGridSize; gz++) {
                int chunkLz = Math.min(gz * weightStep, chunkSize);
                int worldLz = offsetZ + chunkLz;
                int regionZ = worldLz / weightStep;
                boolean sampleZOnGrid = (worldLz % weightStep) == 0;
                if (sampleXOnGrid && sampleZOnGrid
                        && regionX >= 0 && regionX < regionGrid.length
                        && regionZ >= 0 && regionZ < regionGrid[regionX].length) {
                    System.arraycopy(regionGrid[regionX][regionZ], 0, chunkGrid[gx][gz], 0,
                            regionGrid[regionX][regionZ].length);
                } else {
                    double wx = (cx * Chunk.SIZE + chunkLx) * scale;
                    double wz = (cz * Chunk.SIZE + chunkLz) * scale;
                    Map<Biome, Float> weights = regionGenerator.getBiomeWeights(wx, wz);
                    Biome[] biomes = Biome.values();
                    for (int i = 0; i < biomes.length; i++) {
                        Float value = weights.get(biomes[i]);
                        chunkGrid[gx][gz][i] = value == null ? 0f : value;
                    }
                }
            }
        }
        return chunkGrid;
    }

    public Biome getDominantBiome(double wx, double wz) {
        return getBiomeWeights(wx, wz).entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(Biome.PLAINS);
    }

    public int getTexture(String name) {
        return textureMap.getOrDefault(name, 0);
    }

    public float[] getLightDirection() {
        return skyRenderer.getLightDirection();
    }

    public float[] getLightColor() {
        return skyRenderer.getLightColor();
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
        featureImpostorDistance = Math.min(featureImpostorDistance, featureRenderDist);
        grassDetailDistance = Math.min(grassDetailDistance, featureRenderDist);
        renderDistanceDirty = true;
    }

    public int getFeatureRenderDistance() {
        return featureRenderDist;
    }

    public void setFeatureImpostorDistance(int r) {
        featureImpostorDistance = Math.max(0, Math.min(r, featureRenderDist));
    }

    public int getFeatureImpostorDistance() {
        return featureImpostorDistance;
    }

    public void setImpostorAngleCount(int count) {
        if (count >= 8) {
            impostorAngleCount = 8;
        } else if (count >= 4) {
            impostorAngleCount = 4;
        } else {
            impostorAngleCount = 1;
        }
    }

    public int getImpostorAngleCount() {
        return impostorAngleCount;
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
