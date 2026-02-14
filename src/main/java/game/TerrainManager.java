package game;
import objects.Feature;
import renderers.SkyRenderer;
import util.BoundingBox;
import util.TextureLoader;
import static org.lwjgl.opengl.GL11.*;
import org.lwjgl.BufferUtils;
import java.nio.FloatBuffer;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;


import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TerrainManager {
    public enum PipelineStage {
        DRAIN_COMPLETED_CHUNK_BUILDS,
        PROCESS_PENDING_RENDER_BUILDS,
        DRAIN_COMPLETED_FEATURES,
        REBUILD_NEEDED_CHUNKS,
        REFRESH_CHUNK_LODS,
        REPRIORITIZE_QUEUES,
        UPDATE_NEEDED_CHUNKS,
        QUEUE_FEATURES_FOR_VISIBLE_CHUNKS,
        DISPOSE_FAR_CHUNKS,
        PROCESS_PENDING_CHUNK_GENERATIONS,
        PROCESS_PENDING_FEATURE_GENERATIONS,
        DRAW_TERRAIN_AND_FEATURES,
        DRAW_WATER,
        DRAW_DEPTH
    }

    public static final class StageStats {
        public final long nanos;
        public final int calls;

        private StageStats(long nanos, int calls) {
            this.nanos = nanos;
            this.calls = calls;
        }
    }

    public static final class PerformanceSnapshot {
        public final EnumMap<PipelineStage, StageStats> stages;
        public final long totalUpdateNanos;
        public final int loadedChunks;
        public final int pendingChunkGenerations;
        public final int pendingFeatureGenerations;
        public final int pendingRenderBuilds;
        public final int inflightChunkGenerations;
        public final int inflightFeatureGenerations;
        public final long usedMemoryBytes;
        public final long freeMemoryBytes;
        public final long totalMemoryBytes;
        public final long maxMemoryBytes;
        public final long nonHeapUsedBytes;
        public final long directBufferBytes;
        public final int terrainChunksDrawn;
        public final int waterChunksDrawn;
        public final int depthChunksDrawn;
        public final int visibleFeatures;
        public final long estimatedFrameNanos;

        private PerformanceSnapshot(EnumMap<PipelineStage, StageStats> stages, long totalUpdateNanos,
                                    int loadedChunks, int pendingChunkGenerations,
                                    int pendingFeatureGenerations, int pendingRenderBuilds,
                                    int inflightChunkGenerations, int inflightFeatureGenerations,
                                    long usedMemoryBytes, long freeMemoryBytes,
                                    long totalMemoryBytes, long maxMemoryBytes,
                                    long nonHeapUsedBytes, long directBufferBytes,
                                    int terrainChunksDrawn, int waterChunksDrawn,
                                    int depthChunksDrawn, int visibleFeatures,
                                    long estimatedFrameNanos) {
            this.stages = stages;
            this.totalUpdateNanos = totalUpdateNanos;
            this.loadedChunks = loadedChunks;
            this.pendingChunkGenerations = pendingChunkGenerations;
            this.pendingFeatureGenerations = pendingFeatureGenerations;
            this.pendingRenderBuilds = pendingRenderBuilds;
            this.inflightChunkGenerations = inflightChunkGenerations;
            this.inflightFeatureGenerations = inflightFeatureGenerations;
            this.usedMemoryBytes = usedMemoryBytes;
            this.freeMemoryBytes = freeMemoryBytes;
            this.totalMemoryBytes = totalMemoryBytes;
            this.maxMemoryBytes = maxMemoryBytes;
            this.nonHeapUsedBytes = nonHeapUsedBytes;
            this.directBufferBytes = directBufferBytes;
            this.terrainChunksDrawn = terrainChunksDrawn;
            this.waterChunksDrawn = waterChunksDrawn;
            this.depthChunksDrawn = depthChunksDrawn;
            this.visibleFeatures = visibleFeatures;
            this.estimatedFrameNanos = estimatedFrameNanos;
        }
    }

    private static final int AVAILABLE_CORES = Math.max(1, Runtime.getRuntime().availableProcessors());
    private static final int CHUNK_GENERATOR_THREADS = Math.max(2, Math.min(8, AVAILABLE_CORES));
    private static final int FEATURE_GENERATOR_THREADS = Math.max(1, Math.min(4, AVAILABLE_CORES / 2));
    private static final int MAX_CHUNKS_PER_FRAME = 20;
    private static final int MAX_FEATURE_CHUNKS_PER_FRAME = 4;
    private static final long CHUNK_BUDGET_NS = 10_000_000L;
    private static final long FEATURE_BUDGET_NS = 3_000_000L;
    private static final int MAX_APPLIED_CHUNKS_PER_FRAME = 8;
    private static final int MAX_APPLIED_FEATURES_PER_FRAME = 6;
    private static final int MAX_RENDER_BUILDS_PER_FRAME = Math.max(2, Math.min(6, AVAILABLE_CORES / 2));
    private static final long RENDER_BUILD_BUDGET_NS = 3_000_000L;
    private static final int LOD_NEAR_THRESHOLD = 16;
    private static final int LOD_MID_THRESHOLD = 24;
    private static final int LOD_FAR_THRESHOLD = 32;
    private static final int LOD_PRIORITY_RADIUS = 4;
    private static final int LOD_PRIORITY_MIN_BUDGET = 6;
    private static final int MAX_PENDING_CHUNK_QUEUE = 4096;
    private static final int MAX_PENDING_RENDER_QUEUE = 4096;
    private static final int MAX_INFLIGHT_CHUNK_BUILDS = CHUNK_GENERATOR_THREADS * 8;
    private static final int MAX_INFLIGHT_FEATURE_BUILDS = FEATURE_GENERATOR_THREADS * 32;
    private static final int DISTANT_TERRAIN_EXTRA_CHUNKS = 120;
    private static final int DISTANT_TERRAIN_RADIAL_STEPS = 18;
    private static final int DISTANT_TERRAIN_ANGULAR_STEPS = 56;
    private static final float FOG_SMOOTHING = 0.14f;
    private static final float FOG_MIN_BAND_CHUNKS = 2.0f;

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
    private final ExecutorService chunkGenerator = Executors.newFixedThreadPool(CHUNK_GENERATOR_THREADS, r -> {
        Thread t = new Thread(r, "chunk-generator");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService featureGenerator = Executors.newFixedThreadPool(FEATURE_GENERATOR_THREADS, r -> {
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
    private int impostorQualityPreset = 1;
    private int impostorHighQualityDistance = 10;
    private boolean renderDistanceDirty = true;
    private int lastUpdateChunkX = Integer.MIN_VALUE;
    private int lastUpdateChunkZ = Integer.MIN_VALUE;
    private final long seed;
    private static final int BIOME_WEIGHT_REGION_SIZE = 4;

    private final Map<String, Integer> textureMap = new HashMap<>();
    private final int snowTex;
    private final int waterBottomTex;
    private final int waterBottomAbsTex;
    private final EnumMap<PipelineStage, Long> perfStageNanos = new EnumMap<>(PipelineStage.class);
    private final EnumMap<PipelineStage, Integer> perfStageCalls = new EnumMap<>(PipelineStage.class);
    private long perfTotalUpdateNanos = 0L;
    private int perfTerrainChunksDrawn = 0;
    private int perfWaterChunksDrawn = 0;
    private int perfDepthChunksDrawn = 0;
    private int perfVisibleFeatures = 0;
    private Frustum lastCameraFrustum = null;
    private float smoothedFogStart = -1f;
    private float smoothedFogEnd = -1f;

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
        this.impostorHighQualityDistance = Math.min(10, this.featureRenderDist);
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
        for (PipelineStage stage : PipelineStage.values()) {
            perfStageNanos.put(stage, 0L);
            perfStageCalls.put(stage, 0);
        }
    }

    private void resetPerformanceFrame() {
        perfTotalUpdateNanos = 0L;
        perfTerrainChunksDrawn = 0;
        perfWaterChunksDrawn = 0;
        perfDepthChunksDrawn = 0;
        perfVisibleFeatures = 0;
        for (PipelineStage stage : PipelineStage.values()) {
            perfStageNanos.put(stage, 0L);
            perfStageCalls.put(stage, 0);
        }
    }

    private void recordStage(PipelineStage stage, long nanos) {
        perfStageNanos.put(stage, perfStageNanos.get(stage) + Math.max(0L, nanos));
        perfStageCalls.put(stage, perfStageCalls.get(stage) + 1);
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
        resetPerformanceFrame();
        long updateStart = System.nanoTime();
        lastCameraFrustum = frustum;

        int pcx = (int) Math.floor(wx / (Chunk.SIZE * scale));
        int pcz = (int) Math.floor(wz / (Chunk.SIZE * scale));
        long stageStart = System.nanoTime();
        drainCompletedChunkBuilds(pcx, pcz);
        recordStage(PipelineStage.DRAIN_COMPLETED_CHUNK_BUILDS, System.nanoTime() - stageStart);
        stageStart = System.nanoTime();
        processPendingRenderBuilds(pcx, pcz);
        recordStage(PipelineStage.PROCESS_PENDING_RENDER_BUILDS, System.nanoTime() - stageStart);
        stageStart = System.nanoTime();
        drainCompletedFeatureGenerations(pcx, pcz);
        recordStage(PipelineStage.DRAIN_COMPLETED_FEATURES, System.nanoTime() - stageStart);
        if (renderDistanceDirty) {
            stageStart = System.nanoTime();
            rebuildNeededChunks(pcx, pcz);
            recordStage(PipelineStage.REBUILD_NEEDED_CHUNKS, System.nanoTime() - stageStart);
            stageStart = System.nanoTime();
            refreshChunkLods(pcx, pcz, frustum);
            recordStage(PipelineStage.REFRESH_CHUNK_LODS, System.nanoTime() - stageStart);
            stageStart = System.nanoTime();
            reprioritizePendingQueues(pcx, pcz, frustum);
            recordStage(PipelineStage.REPRIORITIZE_QUEUES, System.nanoTime() - stageStart);
            lastUpdateChunkX = pcx;
            lastUpdateChunkZ = pcz;
            renderDistanceDirty = false;
        }
        boolean movedChunk = pcx != lastUpdateChunkX || pcz != lastUpdateChunkZ;
        if (!movedChunk) {
            stageStart = System.nanoTime();
            refreshChunkLods(pcx, pcz, frustum);
            recordStage(PipelineStage.REFRESH_CHUNK_LODS, System.nanoTime() - stageStart);
            stageStart = System.nanoTime();
            reprioritizePendingQueues(pcx, pcz, frustum);
            recordStage(PipelineStage.REPRIORITIZE_QUEUES, System.nanoTime() - stageStart);
            stageStart = System.nanoTime();
            processPendingChunkGenerations(pcx, pcz, frustum);
            recordStage(PipelineStage.PROCESS_PENDING_CHUNK_GENERATIONS, System.nanoTime() - stageStart);
            stageStart = System.nanoTime();
            processPendingRenderBuilds(pcx, pcz);
            recordStage(PipelineStage.PROCESS_PENDING_RENDER_BUILDS, System.nanoTime() - stageStart);
            stageStart = System.nanoTime();
            processPendingFeatureGenerations(pcx, pcz);
            recordStage(PipelineStage.PROCESS_PENDING_FEATURE_GENERATIONS, System.nanoTime() - stageStart);
            perfTotalUpdateNanos = System.nanoTime() - updateStart;
            return;
        }
        int prevChunkX = lastUpdateChunkX;
        int prevChunkZ = lastUpdateChunkZ;
        lastUpdateChunkX = pcx;
        lastUpdateChunkZ = pcz;
        stageStart = System.nanoTime();
        updateNeededChunks(pcx, pcz, prevChunkX, prevChunkZ);
        recordStage(PipelineStage.UPDATE_NEEDED_CHUNKS, System.nanoTime() - stageStart);

        stageStart = System.nanoTime();
        refreshChunkLods(pcx, pcz, frustum);
        recordStage(PipelineStage.REFRESH_CHUNK_LODS, System.nanoTime() - stageStart);

        stageStart = System.nanoTime();
        reprioritizePendingQueues(pcx, pcz, frustum);
        recordStage(PipelineStage.REPRIORITIZE_QUEUES, System.nanoTime() - stageStart);

        // Generate/unload features based on featureRenderDist
        stageStart = System.nanoTime();
        for (Chunk c : chunks.values()) {
            c.unloadFeaturesIfOutOfRange(pcx, pcz, cacheFeatureRenderDist);
            int dist = Math.max(Math.abs(c.cx - pcx), Math.abs(c.cz - pcz));
            if (dist <= cacheFeatureRenderDist) {
                queueFeatureGeneration(c, pcx, pcz);
            }
        }
        recordStage(PipelineStage.QUEUE_FEATURES_FOR_VISIBLE_CHUNKS, System.nanoTime() - stageStart);

        // Dispose chunks no longer needed
        stageStart = System.nanoTime();
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
        recordStage(PipelineStage.DISPOSE_FAR_CHUNKS, System.nanoTime() - stageStart);

        stageStart = System.nanoTime();
        processPendingChunkGenerations(pcx, pcz, frustum);
        recordStage(PipelineStage.PROCESS_PENDING_CHUNK_GENERATIONS, System.nanoTime() - stageStart);
        stageStart = System.nanoTime();
        processPendingRenderBuilds(pcx, pcz);
        recordStage(PipelineStage.PROCESS_PENDING_RENDER_BUILDS, System.nanoTime() - stageStart);
        stageStart = System.nanoTime();
        processPendingFeatureGenerations(pcx, pcz);
        recordStage(PipelineStage.PROCESS_PENDING_FEATURE_GENERATIONS, System.nanoTime() - stageStart);
        perfTotalUpdateNanos = System.nanoTime() - updateStart;
    }

    private void updateNeededChunks(int pcx, int pcz, int prevChunkX, int prevChunkZ) {
        if (neededKeys.isEmpty() || Math.abs(pcx - prevChunkX) > 1 || Math.abs(pcz - prevChunkZ) > 1) {
            rebuildNeededChunks(pcx, pcz);
            refreshChunkLods(pcx, pcz, null);
            return;
        }

        int expectedKeyCount = (renderDist * 2 + 1) * (renderDist * 2 + 1);
        if (neededKeys.size() != expectedKeyCount) {
            rebuildNeededChunks(pcx, pcz);
            refreshChunkLods(pcx, pcz, null);
            return;
        }

        int dx = pcx - prevChunkX;
        int dz = pcz - prevChunkZ;
        if (dx != 0) {
            int sign = Integer.signum(dx);
            int newCol = pcx + renderDist * sign;
            int oldCol = prevChunkX - renderDist * sign;
            for (int offset = -renderDist; offset <= renderDist; offset++) {
                addNeededChunk(newCol, pcz + offset, pcx, pcz);
                removeNeededChunk(oldCol, prevChunkZ + offset);
            }
        }

        if (dz != 0) {
            int sign = Integer.signum(dz);
            int newRow = pcz + renderDist * sign;
            int oldRow = prevChunkZ - renderDist * sign;
            for (int offset = -renderDist; offset <= renderDist; offset++) {
                addNeededChunk(pcx + offset, newRow, pcx, pcz);
                removeNeededChunk(prevChunkX + offset, oldRow);
            }
        }

        if (neededKeys.size() != expectedKeyCount) {
            rebuildNeededChunks(pcx, pcz);
        }

        refreshChunkLods(pcx, pcz, null);
    }

    private void refreshChunkLods(int pcx, int pcz, Frustum frustum) {
        for (long key : neededKeys) {
            int cx = (int) (key >> 32);
            int cz = (int) key;
            int dist = Math.max(Math.abs(cx - pcx), Math.abs(cz - pcz));
            int targetLOD = computeTargetLod(dist);
            Chunk existing = chunks.get(key);
            if (existing == null || existing.getLOD() == targetLOD) {
                continue;
            }
            boolean forceNear = dist <= LOD_PRIORITY_RADIUS;
            boolean visible = frustum != null && isChunkVisible(frustum, cx, cz);
            if (forceNear || visible) {
                queueChunkGeneration(key, cx, cz, targetLOD, dist);
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
        if (pendingChunks.size() >= MAX_PENDING_CHUNK_QUEUE && dist > LOD_PRIORITY_RADIUS) {
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
        if (frustum != null) {
            ArrayDeque<Long> rebuiltPending = new ArrayDeque<>();
            Map<Long, Integer> rebuiltLods = new HashMap<>();
            for (long key : neededKeys) {
                int cx = (int) (key >> 32);
                int cz = (int) key;
                int dist = Math.max(Math.abs(cx - pcx), Math.abs(cz - pcz));
                if (dist > cacheRenderDist) {
                    continue;
                }
                Chunk existing = chunks.get(key);
                int targetLod = computeTargetLod(dist);
                boolean needsBuild = existing == null || existing.getLOD() != targetLod;
                if (!needsBuild) {
                    continue;
                }
                boolean visible = isChunkVisible(frustum, cx, cz);
                if (!visible && dist > LOD_PRIORITY_RADIUS) {
                    continue;
                }
                if (rebuiltPending.size() >= MAX_PENDING_CHUNK_QUEUE && dist > LOD_PRIORITY_RADIUS) {
                    continue;
                }
                rebuiltLods.put(key, targetLod);
                if (dist <= LOD_PRIORITY_RADIUS) {
                    rebuiltPending.addFirst(key);
                } else {
                    rebuiltPending.addLast(key);
                }
            }
            pendingChunks.clear();
            pendingChunkLods.clear();
            pendingChunks.addAll(rebuiltPending);
            pendingChunkLods.putAll(rebuiltLods);
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

        ArrayDeque<Chunk> visibleNear = new ArrayDeque<>();
        ArrayDeque<Chunk> visibleFar = new ArrayDeque<>();
        if (!pendingRenderBuilds.isEmpty()) {
            Iterator<Chunk> iterator = pendingRenderBuilds.iterator();
            while (iterator.hasNext()) {
                Chunk chunk = iterator.next();
                long key = key(chunk.cx, chunk.cz);
                Chunk pendingReplacement = pendingChunkReplacements.get(key);
                if (chunks.get(key) != chunk && pendingReplacement != chunk) {
                    pendingRenderBuildKeys.remove(key);
                    if (pendingReplacement != null) {
                        chunk.dispose();
                    }
                    iterator.remove();
                    continue;
                }

                int dist = Math.max(Math.abs(chunk.cx - pcx), Math.abs(chunk.cz - pcz));
                boolean visible = frustum != null && isChunkVisible(frustum, chunk.cx, chunk.cz);
                if (dist > cacheRenderDist || !visible) {
                    pendingRenderBuildKeys.remove(key);
                    if (pendingReplacement == chunk) {
                        pendingChunkReplacements.remove(key);
                        chunk.dispose();
                    }
                    iterator.remove();
                    continue;
                }

                if (dist <= LOD_PRIORITY_RADIUS) {
                    visibleNear.addLast(chunk);
                } else {
                    visibleFar.addLast(chunk);
                }
                iterator.remove();
            }
        }

        if (frustum != null) {
            for (long key : neededKeys) {
                Chunk chunk = chunks.get(key);
                if (chunk == null || !chunk.needsRenderResources() || pendingRenderBuildKeys.contains(key)) {
                    continue;
                }
                int cx = (int) (key >> 32);
                int cz = (int) key;
                if (!isChunkVisible(frustum, cx, cz)) {
                    continue;
                }
                int dist = Math.max(Math.abs(cx - pcx), Math.abs(cz - pcz));
                if (dist > cacheRenderDist) {
                    continue;
                }
                pendingRenderBuildKeys.add(key);
                if (dist <= LOD_PRIORITY_RADIUS) {
                    visibleNear.addLast(chunk);
                } else {
                    visibleFar.addLast(chunk);
                }
            }
        }

        pendingRenderBuilds.clear();
        pendingRenderBuilds.addAll(visibleNear);
        pendingRenderBuilds.addAll(visibleFar);
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
        if (frustum == null) {
            return;
        }

        seedVisibleLodMismatches(pcx, pcz, frustum);

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
        List<Long> upgradeVisible = new ArrayList<>();
        List<Long> farVisible = new ArrayList<>();

        for (long key : pendingChunks) {
            int cx = (int) (key >> 32);
            int cz = (int) key;
            if (!isChunkVisible(frustum, cx, cz)) {
                continue;
            }
            int dist = Math.max(Math.abs(cx - pcx), Math.abs(cz - pcz));
            if (isUpgradeRequest(key)) {
                upgradeVisible.add(key);
            } else if (dist <= LOD_PRIORITY_RADIUS) {
                nearVisible.add(key);
            } else {
                farVisible.add(key);
            }
        }

        upgradeVisible.sort(priorityComparator);
        nearVisible.sort(priorityComparator);
        farVisible.sort(priorityComparator);

        int processed = 0;
        int nearBudget = Math.min(budget, Math.max(LOD_PRIORITY_MIN_BUDGET, (budget * 2) / 3));

        for (long key : upgradeVisible) {
            if (processed >= nearBudget || System.nanoTime() - start > CHUNK_BUDGET_NS) {
                break;
            }
            if (tryDispatchChunkGeneration(key, true)) {
                processed++;
            }
        }

        for (long key : nearVisible) {
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
    }

    private boolean isUpgradeRequest(long key) {
        Chunk existing = chunks.get(key);
        Integer pendingLod = pendingChunkLods.get(key);
        return existing != null && pendingLod != null && pendingLod < existing.getLOD();
    }

    private void seedVisibleLodMismatches(int pcx, int pcz, Frustum frustum) {
        for (long key : neededKeys) {
            int cx = (int) (key >> 32);
            int cz = (int) key;
            if (!isChunkVisible(frustum, cx, cz)) {
                continue;
            }
            int dist = Math.max(Math.abs(cx - pcx), Math.abs(cz - pcz));
            int targetLOD = computeTargetLod(dist);
            Chunk existing = chunks.get(key);
            if (existing == null || existing.getLOD() != targetLOD) {
                queueChunkGeneration(key, cx, cz, targetLOD, dist);
            }
        }
    }

    private boolean tryDispatchChunkGeneration(long key, boolean prioritizeRequeue) {
        if (inflightChunkKeys.size() >= MAX_INFLIGHT_CHUNK_BUILDS) {
            return false;
        }
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
        if (inflightFeatureKeys.size() >= MAX_INFLIGHT_FEATURE_BUILDS) {
            return;
        }
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
            if (inflightFeatureKeys.size() >= MAX_INFLIGHT_FEATURE_BUILDS) {
                break;
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
        Frustum frustum = lastCameraFrustum;
        if (frustum != null && !isChunkVisible(frustum, chunk.cx, chunk.cz)) {
            Chunk pendingReplacement = pendingChunkReplacements.get(key);
            if (pendingReplacement == chunk) {
                pendingChunkReplacements.remove(key);
                chunk.dispose();
            }
            return;
        }
        if (pendingRenderBuilds.size() >= MAX_PENDING_RENDER_QUEUE) {
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
        int dynamicBudget = MAX_RENDER_BUILDS_PER_FRAME
                + Math.min(4, Math.max(0, pendingRenderBuilds.size() - 128) / 256);
        while (built < dynamicBudget && !pendingRenderBuilds.isEmpty()) {
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
        long start = System.nanoTime();
        enableFogDynamic();

        int pcx = (int) Math.floor(wx / (Chunk.SIZE * scale));
        int pcz = (int) Math.floor(wz / (Chunk.SIZE * scale));
        int impostorDistance = Math.min(this.featureImpostorDistance, featureRenderDist);
        int grassDetailDistance = Math.min(this.grassDetailDistance, featureRenderDist);

        renderDistantTerrainBackdrop(wx, wz);

        int renderedTerrainChunks = 0;
        int renderedFeatures = 0;
        for (Chunk c : chunks.values()) {
            int dist = Math.max(Math.abs(c.cx - pcx), Math.abs(c.cz - pcz));
            if (dist > renderDist) {
                continue;
            }
            if (lastCameraFrustum != null && !isChunkVisible(lastCameraFrustum, c.cx, c.cz)) {
                continue;
            }
            c.drawTerrainAndFeatures(dist, impostorDistance, grassDetailDistance,
                    featureRenderDist);
            renderedTerrainChunks++;
            renderedFeatures += c.getFeatures().size();
        }

        disableFog();
        perfTerrainChunksDrawn = renderedTerrainChunks;
        perfVisibleFeatures = renderedFeatures;
        recordStage(PipelineStage.DRAW_TERRAIN_AND_FEATURES, System.nanoTime() - start);
    }

    public void drawWater(float wx, float wz) {
        long start = System.nanoTime();
        int pcx = (int) Math.floor(wx / (Chunk.SIZE * scale));
        int pcz = (int) Math.floor(wz / (Chunk.SIZE * scale));
        int renderedWaterChunks = 0;
        for (Chunk c : chunks.values()) {
            int dist = Math.max(Math.abs(c.cx - pcx), Math.abs(c.cz - pcz));
            if (dist > renderDist) {
                continue;
            }
            if (lastCameraFrustum != null && !isChunkVisible(lastCameraFrustum, c.cx, c.cz)) {
                continue;
            }
            c.drawWater();
            renderedWaterChunks++;
        }
        perfWaterChunksDrawn = renderedWaterChunks;
        recordStage(PipelineStage.DRAW_WATER, System.nanoTime() - start);
    }

    public void drawDepth(float wx, float wz) {
        long start = System.nanoTime();
        int pcx = (int) Math.floor(wx / (Chunk.SIZE * scale));
        int pcz = (int) Math.floor(wz / (Chunk.SIZE * scale));
        int impostorDistance = Math.min(this.featureImpostorDistance, featureRenderDist);
        int grassDetailDistance = Math.min(this.grassDetailDistance, featureRenderDist);

        int renderedDepthChunks = 0;
        for (Chunk c : chunks.values()) {
            int dist = Math.max(Math.abs(c.cx - pcx), Math.abs(c.cz - pcz));
            if (dist > shadowRenderDist) {
                continue;
            }
            c.renderDepth(dist, impostorDistance, grassDetailDistance,
                    featureRenderDist);
            renderedDepthChunks++;
        }
        perfDepthChunksDrawn = renderedDepthChunks;
        recordStage(PipelineStage.DRAW_DEPTH, System.nanoTime() - start);
    }

    private void enableFogDynamic() {
        float[] fogRange = computeFogRange();
        if (fogRange == null) {
            glDisable(GL_FOG);
            return;
        }

        glEnable(GL_FOG);
        glFogi(GL_FOG_MODE, GL_LINEAR);

        float time = skyRenderer.getTimeOfDay();
        float brightness = getFogBrightness(time);

        float fogStart = fogRange[0];
        float fogEnd = fogRange[1];
        glFogf(GL_FOG_START, fogStart);
        glFogf(GL_FOG_END, fogEnd);

        float r = 0.6f * brightness;
        float g = 0.75f * brightness;
        float b = 1.0f * brightness;

        fogColorBuffer.clear();
        fogColorBuffer.put(r).put(g).put(b).put(1f).flip();
        glFogfv(GL_FOG_COLOR, fogColorBuffer);

        glHint(GL_FOG_HINT, GL_NICEST);
    }

    public float[] getFogSettings() {
        float[] fogRange = computeFogRange();
        float time = skyRenderer.getTimeOfDay();
        float brightness = getFogBrightness(time);
        float r = 0.6f * brightness;
        float g = 0.75f * brightness;
        float b = 1.0f * brightness;
        if (fogRange == null) {
            return new float[] { 0f, 0f, r, g, b };
        }
        return new float[] { fogRange[0], fogRange[1], r, g, b };
    }


    private float[] computeFogRange() {
        int pcx = lastUpdateChunkX;
        int pcz = lastUpdateChunkZ;
        if (pcx == Integer.MIN_VALUE || pcz == Integer.MIN_VALUE) {
            return null;
        }

        int nearestMissingDist = Integer.MAX_VALUE;
        for (long key : neededKeys) {
            int cx = (int) (key >> 32);
            int cz = (int) key;
            int dist = Math.max(Math.abs(cx - pcx), Math.abs(cz - pcz));
            if (dist > cacheRenderDist) {
                continue;
            }
            Chunk chunk = chunks.get(key);
            if (chunk == null || chunk.needsRenderResources()) {
                nearestMissingDist = Math.min(nearestMissingDist, dist);
            }
        }

        if (nearestMissingDist == Integer.MAX_VALUE) {
            smoothedFogStart = -1f;
            smoothedFogEnd = -1f;
            return null;
        }

        float chunkSpan = Chunk.SIZE * scale;
        float targetCenter = nearestMissingDist * chunkSpan;
        float minCenter = Math.max(0f, (renderDist - 2) * chunkSpan);
        float maxCenter = Math.max(minCenter + chunkSpan, (cacheRenderDist + 1) * chunkSpan);
        float fogCenter = Math.max(minCenter, Math.min(maxCenter, targetCenter));
        float fogHalfWidth = Math.max(FOG_MIN_BAND_CHUNKS * chunkSpan, chunkSpan * 1.5f);
        float targetStart = Math.max(0f, fogCenter - fogHalfWidth);
        float targetEnd = Math.max(targetStart + chunkSpan, fogCenter + fogHalfWidth);

        if (smoothedFogStart < 0f || smoothedFogEnd < 0f) {
            smoothedFogStart = targetStart;
            smoothedFogEnd = targetEnd;
        } else {
            smoothedFogStart += (targetStart - smoothedFogStart) * FOG_SMOOTHING;
            smoothedFogEnd += (targetEnd - smoothedFogEnd) * FOG_SMOOTHING;
        }

        return new float[] { smoothedFogStart, smoothedFogEnd };
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



    private void renderDistantTerrainBackdrop(float wx, float wz) {
        float chunkSpan = Chunk.SIZE * scale;
        float nearRadius = Math.max(0f, (cacheRenderDist + 6) * chunkSpan);
        float farRadius = Math.max(nearRadius + chunkSpan * 4f,
                (cacheRenderDist + DISTANT_TERRAIN_EXTRA_CHUNKS) * chunkSpan);

        float time = skyRenderer.getTimeOfDay();
        float brightness = Math.max(0.15f, getFogBrightness(time));
        float baseR = 0.34f * brightness;
        float baseG = 0.44f * brightness;
        float baseB = 0.56f * brightness;

        glDisable(GL_TEXTURE_2D);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(false);

        for (int ring = 0; ring < DISTANT_TERRAIN_RADIAL_STEPS; ring++) {
            float t0 = (float) ring / DISTANT_TERRAIN_RADIAL_STEPS;
            float t1 = (float) (ring + 1) / DISTANT_TERRAIN_RADIAL_STEPS;
            float r0 = nearRadius + (farRadius - nearRadius) * t0;
            float r1 = nearRadius + (farRadius - nearRadius) * t1;
            float alpha0 = 0.55f * (1f - t0);
            float alpha1 = 0.55f * (1f - t1);

            glBegin(GL_TRIANGLE_STRIP);
            for (int i = 0; i <= DISTANT_TERRAIN_ANGULAR_STEPS; i++) {
                float a = (float) (i * Math.PI * 2.0 / DISTANT_TERRAIN_ANGULAR_STEPS);
                float c = (float) Math.cos(a);
                float s = (float) Math.sin(a);

                float x0 = wx + c * r0;
                float z0 = wz + s * r0;
                float y0 = sampleDistantHeight(x0, z0);

                float x1 = wx + c * r1;
                float z1 = wz + s * r1;
                float y1 = sampleDistantHeight(x1, z1);

                glColor4f(baseR, baseG, baseB, alpha0);
                glVertex3f(x0, y0, z0);
                glColor4f(baseR, baseG, baseB, alpha1);
                glVertex3f(x1, y1, z1);
            }
            glEnd();
        }

        glDepthMask(true);
        glDisable(GL_BLEND);
        glColor4f(1f, 1f, 1f, 1f);
    }

    private float sampleDistantHeight(float wx, float wz) {
        double macro = terrainNoise.eval(wx * 0.00045, wz * 0.00045) * 36.0;
        double mid = terrainNoise.eval(wx * 0.00125 + 1200.0, wz * 0.00125 - 900.0) * 16.0;
        double detail = terrainNoise.eval(wx * 0.0035 - 400.0, wz * 0.0035 + 250.0) * 5.0;
        return (float) (macro + mid + detail + 6.0);
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


    public PerformanceSnapshot getPerformanceSnapshot() {
        EnumMap<PipelineStage, StageStats> stageCopies = new EnumMap<>(PipelineStage.class);
        for (PipelineStage stage : PipelineStage.values()) {
            long nanos = perfStageNanos.getOrDefault(stage, 0L);
            int calls = perfStageCalls.getOrDefault(stage, 0);
            stageCopies.put(stage, new StageStats(nanos, calls));
        }
        Runtime runtime = Runtime.getRuntime();
        long total = runtime.totalMemory();
        long free = runtime.freeMemory();
        long used = total - free;
        MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage nonHeap = memoryMXBean.getNonHeapMemoryUsage();
        long directBytes = 0L;
        for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            if ("direct".equalsIgnoreCase(pool.getName())) {
                directBytes = pool.getMemoryUsed();
                break;
            }
        }
        long estimatedFrameNanos = 0L;
        for (PipelineStage stage : PipelineStage.values()) {
            estimatedFrameNanos += perfStageNanos.getOrDefault(stage, 0L);
        }
        return new PerformanceSnapshot(
                stageCopies,
                perfTotalUpdateNanos,
                chunks.size(),
                pendingChunks.size(),
                pendingFeatureChunks.size(),
                pendingRenderBuilds.size(),
                inflightChunkKeys.size(),
                inflightFeatureKeys.size(),
                used,
                free,
                total,
                runtime.maxMemory(),
                nonHeap != null ? nonHeap.getUsed() : 0L,
                directBytes,
                perfTerrainChunksDrawn,
                perfWaterChunksDrawn,
                perfDepthChunksDrawn,
                perfVisibleFeatures,
                estimatedFrameNanos);
    }

    public String buildPerformanceReport() {
        PerformanceSnapshot snapshot = getPerformanceSnapshot();
        StringBuilder sb = new StringBuilder(4096);
        sb.append("=== Terrain Pipeline Debug Report ===\n");
        sb.append("update_total_ms=").append(String.format(Locale.US, "%.3f", snapshot.totalUpdateNanos / 1_000_000.0)).append('\n');
        sb.append("loaded_chunks=").append(snapshot.loadedChunks).append('\n');
        sb.append("pending_chunk_generations=").append(snapshot.pendingChunkGenerations).append('\n');
        sb.append("pending_feature_generations=").append(snapshot.pendingFeatureGenerations).append('\n');
        sb.append("pending_render_builds=").append(snapshot.pendingRenderBuilds).append('\n');
        sb.append("inflight_chunk_generations=").append(snapshot.inflightChunkGenerations).append('\n');
        sb.append("inflight_feature_generations=").append(snapshot.inflightFeatureGenerations).append('\n');
        sb.append("memory_used_mb=").append(String.format(Locale.US, "%.2f", snapshot.usedMemoryBytes / (1024.0 * 1024.0))).append('\n');
        sb.append("memory_free_mb=").append(String.format(Locale.US, "%.2f", snapshot.freeMemoryBytes / (1024.0 * 1024.0))).append('\n');
        sb.append("memory_total_mb=").append(String.format(Locale.US, "%.2f", snapshot.totalMemoryBytes / (1024.0 * 1024.0))).append('\n');
        sb.append("memory_max_mb=").append(String.format(Locale.US, "%.2f", snapshot.maxMemoryBytes / (1024.0 * 1024.0))).append('\n');
        sb.append("memory_non_heap_mb=").append(String.format(Locale.US, "%.2f", snapshot.nonHeapUsedBytes / (1024.0 * 1024.0))).append('\n');
        sb.append("memory_direct_mb=").append(String.format(Locale.US, "%.2f", snapshot.directBufferBytes / (1024.0 * 1024.0))).append('\n');
        sb.append("terrain_chunks_drawn=").append(snapshot.terrainChunksDrawn).append('\n');
        sb.append("water_chunks_drawn=").append(snapshot.waterChunksDrawn).append('\n');
        sb.append("depth_chunks_drawn=").append(snapshot.depthChunksDrawn).append('\n');
        sb.append("visible_features=").append(snapshot.visibleFeatures).append('\n');
        sb.append("estimated_frame_ms=").append(String.format(Locale.US, "%.3f", snapshot.estimatedFrameNanos / 1_000_000.0)).append('\n');
        sb.append("-- stages --\n");
        long total = Math.max(1L, snapshot.estimatedFrameNanos);
        for (PipelineStage stage : PipelineStage.values()) {
            StageStats stats = snapshot.stages.get(stage);
            long nanos = stats != null ? stats.nanos : 0L;
            int calls = stats != null ? stats.calls : 0;
            double pct = (nanos * 100.0) / total;
            sb.append(stage.name().toLowerCase(Locale.ROOT))
                    .append(": ms=")
                    .append(String.format(Locale.US, "%.3f", nanos / 1_000_000.0))
                    .append(", calls=")
                    .append(calls)
                    .append(", pct=")
                    .append(String.format(Locale.US, "%.2f", pct))
                    .append('\n');
        }
        return sb.toString();
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
        impostorHighQualityDistance = Math.min(impostorHighQualityDistance, featureRenderDist);
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

    public void setImpostorQualityPreset(int preset) {
        impostorQualityPreset = Math.max(0, Math.min(2, preset));
    }

    public int getImpostorQualityPreset() {
        return impostorQualityPreset;
    }

    public void setImpostorHighQualityDistance(int distance) {
        impostorHighQualityDistance = Math.max(0, Math.min(distance, featureRenderDist));
    }

    public int getImpostorHighQualityDistance() {
        return impostorHighQualityDistance;
    }

    public int getImpostorTextureSize(int chunkDistance) {
        boolean highQuality = chunkDistance <= impostorHighQualityDistance;
        switch (impostorQualityPreset) {
            case 2:
                return highQuality ? 512 : 256;
            case 1:
                return highQuality ? 256 : 128;
            default:
                return highQuality ? 128 : 64;
        }
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
