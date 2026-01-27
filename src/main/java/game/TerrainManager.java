package game;
import objects.Feature;
import renderers.SkyRenderer;
import util.BoundingBox;
import util.TextureLoader;
import static org.lwjgl.opengl.GL11.*;
import org.lwjgl.BufferUtils;
import java.nio.FloatBuffer;


import java.util.*;

public class TerrainManager {
    private static final int MAX_CHUNKS_PER_FRAME = 10;
    private static final int MAX_FEATURE_CHUNKS_PER_FRAME = 6;
    private static final long FEATURE_BUDGET_NS = 4_000_000L;

    private final Map<Long, Chunk> chunks = new HashMap<>();
    private final ArrayDeque<Long> pendingChunks = new ArrayDeque<>();
    private final Map<Long, Integer> pendingChunkLods = new HashMap<>();
    private final ArrayDeque<Chunk> pendingFeatureChunks = new ArrayDeque<>();
    private final Set<Long> pendingFeatureKeys = new HashSet<>();
    private final OpenSimplexNoise terrainNoise;
    private final BiomeRegionGenerator regionGenerator;
    private final SkyRenderer skyRenderer;


    private final float scale;
    private int renderDist;
    private int featureRenderDist;
    private int shadowRenderDist;
    private int cacheRenderDist;
    private int cacheFeatureRenderDist;
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
        Set<Long> needed = new HashSet<>();

        for (int dx = -renderDist; dx <= renderDist; dx++) {
            for (int dz = -renderDist; dz <= renderDist; dz++) {
                int cx = pcx + dx, cz = pcz + dz;
                long k = key(cx, cz);

                Chunk existing = chunks.get(k);

                needed.add(k);


                int dist = Math.max(Math.abs(cx - pcx), Math.abs(cz - pcz));
                int targetLOD = 0;
                /*
                 * if (dist >= 15)
                 * targetLOD = 3;
                 * else if (dist >= 10)
                 * targetLOD = 2;
                 * else if (dist >= 5)
                 * targetLOD = 1;
                 */

                if (existing == null) {
                    queueChunkGeneration(k, cx, cz, targetLOD);
                } else if (targetLOD < existing.getLOD()) {
                    queueChunkGeneration(k, cx, cz, targetLOD);
                }

            }
        }

        // Generate/unload features based on featureRenderDist
        for (Chunk c : chunks.values()) {
            c.unloadFeaturesIfOutOfRange(pcx, pcz, cacheFeatureRenderDist);

            queueFeatureGeneration(c, pcx, pcz);

        }

        // Dispose chunks no longer needed
        for (Iterator<Map.Entry<Long, Chunk>> it = chunks.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Long, Chunk> entry = it.next();
            if (!needed.contains(entry.getKey())) {
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
    }

    public Chunk getChunk(int cx, int cz) {
        return chunks.get(key(cx, cz));
    }

    private void queueChunkGeneration(long key, int cx, int cz, int targetLOD) {
        Integer existing = pendingChunkLods.get(key);
        if (existing != null) {
            pendingChunkLods.put(key, Math.min(existing, targetLOD));
            return;
        }
        pendingChunkLods.put(key, targetLOD);
        pendingChunks.add(key);
    }

    private void processPendingChunkGenerations() {
        int backlog = pendingChunks.size();
        int budget = MAX_CHUNKS_PER_FRAME + Math.min(8, backlog / 10);
        int count = 0;
        while (count < budget && !pendingChunks.isEmpty()) {
            long key = pendingChunks.poll();
            Integer pendingLod = pendingChunkLods.remove(key);
            if (pendingLod == null) {
                continue;
            }
            int cx = (int) (key >> 32);
            int cz = (int) key;
            Chunk existing = chunks.get(key);
            int targetLOD = pendingLod;
            if (existing != null && targetLOD >= existing.getLOD()) {
                continue;
            }
            Biome b = pickBiome(cx, cz);
            Chunk upgraded = new Chunk(cx, cz, terrainNoise, scale, b, this, false, targetLOD);
            if (existing != null) {
                existing.dispose();
            }
            chunks.put(key, upgraded);
            count++;
        }
    }

    private void queueFeatureGeneration(Chunk chunk, int pcx, int pcz) {
        long key = key(chunk.cx, chunk.cz);
        if (pendingFeatureKeys.contains(key)) {
            return;
        }
        if (!chunk.needsFeatureGeneration(pcx, pcz, featureRenderDist)) {
            return;
        }
        pendingFeatureKeys.add(key);
        pendingFeatureChunks.add(chunk);
    }

    private void processPendingFeatureGenerations(int pcx, int pcz) {
        int backlog = pendingFeatureChunks.size();
        int budget = MAX_FEATURE_CHUNKS_PER_FRAME + Math.min(6, backlog / 14);
        int count = 0;
        long start = System.nanoTime();
        while (count < budget && !pendingFeatureChunks.isEmpty()) {
            if (System.nanoTime() - start > FEATURE_BUDGET_NS) {
                break;
            }
            Chunk chunk = pendingFeatureChunks.poll();
            long key = key(chunk.cx, chunk.cz);
            pendingFeatureKeys.remove(key);
            if (!chunk.needsFeatureGeneration(pcx, pcz, featureRenderDist)) {
                continue;
            }
            chunk.generateFeaturesIfNeeded(pcx, pcz, featureRenderDist);
            count++;
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
        int featureDetailDistance = Math.max(1, featureRenderDist - 1);
        int grassDetailDistance = Math.max(1, featureRenderDist - 2);

        for (Chunk c : chunks.values()) {
            int dist = Math.max(Math.abs(c.cx - pcx), Math.abs(c.cz - pcz));
            c.drawTerrainAndFeatures(dist, featureDetailDistance, grassDetailDistance);
        }

        disableFog();
    }

    public void drawWater() {
        for (Chunk c : chunks.values()) {
            c.drawWater();
        }
    }

    public void drawDepth(float wx, float wz) {
        int pcx = (int) Math.floor(wx / (Chunk.SIZE * scale));
        int pcz = (int) Math.floor(wz / (Chunk.SIZE * scale));
        int featureDetailDistance = Math.max(1, featureRenderDist - 1);
        int grassDetailDistance = Math.max(1, featureRenderDist - 2);

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
        float fogStart = Math.max(0f, (renderDist - 3f) * Chunk.SIZE * scale);
        glFogf(GL_FOG_START, fogStart);
        glFogf(GL_FOG_END, fogEnd);

        // --- New: match fog color to sky color ---
        float r = 0.6f * brightness;
        float g = 0.75f * brightness;
        float b = 1.0f * brightness;

        FloatBuffer fogColor = BufferUtils.createFloatBuffer(4).put(new float[]{ r, g, b, 1f }).flip();
        glFogfv(GL_FOG_COLOR, fogColor);

        glHint(GL_FOG_HINT, GL_NICEST);
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
        cacheRenderDist = renderDist + 4;
        shadowRenderDist = Math.max(shadowRenderDist, renderDist + 12);
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
        cacheFeatureRenderDist = featureRenderDist + 4;
        shadowRenderDist = Math.max(shadowRenderDist, renderDist + 12);
    }

    public int getFeatureRenderDistance() {
        return featureRenderDist;
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
