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
    private final Map<Long, Chunk> chunks = new HashMap<>();
    private final OpenSimplexNoise terrainNoise;
    private final OpenSimplexNoise biomeNoise;
    private final BiomeRegionGenerator regionGenerator;
    private final SkyRenderer skyRenderer;


    private final float scale;
    private int renderDist;
    private int featureRenderDist;
    private int shadowRenderDist;
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
        this.biomeNoise = new OpenSimplexNoise(seed + 12345);
        this.scale = scale;
        this.renderDist = renderDist;
        this.featureRenderDist = featureRenderDist;
        this.shadowRenderDist = Math.max(renderDist + 2, featureRenderDist + 2);
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
        double nx = (cx * Chunk.SIZE + Chunk.SIZE / 2.0) * 0.002;
        double nz = (cz * Chunk.SIZE + Chunk.SIZE / 2.0) * 0.002;
        double value = (biomeNoise.eval(nx, nz) + 1) * 0.5;

        // Normalize all spawn chances
        double totalChance = Arrays.stream(Biome.values()).mapToDouble(b -> b.spawnChance).sum();
        double threshold = value * totalChance;

        double sum = 0;
        for (Biome b : Biome.values()) {
            sum += b.spawnChance;
            if (threshold <= sum)
                return b;
        }

        // Force last biome (should never happen if normalized properly)
        return Biome.values()[Biome.values().length - 1];
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

                float chunkMinX = cx * Chunk.SIZE * scale;
                float chunkMinZ = cz * Chunk.SIZE * scale;
                float chunkMaxX = (cx + 1) * Chunk.SIZE * scale;
                float chunkMaxZ = (cz + 1) * Chunk.SIZE * scale;

// Use real Y bounds if chunk exists
                float chunkMinY = -20f;
                float chunkMaxY = 100f; // fallback default

                /*if (existing != null) {
                    BoundingBox box = existing.getBoundingBox();
                    chunkMinY = box.minY;
                    chunkMaxY = box.maxY;
                }*/

// Now frustum cull properly
                if (!frustum.isBoxVisible(chunkMinX, chunkMinY, chunkMinZ, chunkMaxX, chunkMaxY, chunkMaxZ))
                    continue;


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

                if (existing == null || targetLOD < existing.getLOD()) {
                    Biome b = pickBiome(cx, cz);
                    Chunk upgraded = new Chunk(cx, cz, terrainNoise, scale, b, this, false, targetLOD);
                    if (existing != null)
                        existing.dispose();
                    chunks.put(k, upgraded);
                }

            }
        }

        // Generate/unload features based on featureRenderDist
        for (Chunk c : chunks.values()) {
            c.unloadFeaturesIfOutOfRange(pcx, pcz, featureRenderDist);

            BoundingBox box = c.getBoundingBox();
            if (frustum.isBoxVisible(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)) {
                c.generateFeaturesIfNeeded(pcx, pcz, featureRenderDist);
            }

        }

        // Dispose chunks no longer needed
        for (Iterator<Map.Entry<Long, Chunk>> it = chunks.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Long, Chunk> entry = it.next();
            if (!needed.contains(entry.getKey())) {
                entry.getValue().dispose();
                it.remove();
            }
        }
    }

    public Chunk getChunk(int cx, int cz) {
        return chunks.get(key(cx, cz));
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

        glFogf(GL_FOG_START, renderDist * Chunk.SIZE * scale * 0.8f);
        glFogf(GL_FOG_END, renderDist * Chunk.SIZE * scale * 1.0f);

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
        double nx = wx * 0.001;
        double nz = wz * 0.001;
        double v = (biomeNoise.eval(nx, nz) + 1.0) / 2.0;

        Map<Biome, Float> weights = new EnumMap<>(Biome.class);
        float total = 0f;

        for (Biome biome : Biome.values()) {
            float distance = (float) Math.abs(v - biome.center);
            float influence = 1f - (distance / biome.blendRadius);
            influence = Math.max(0f, influence);
            weights.put(biome, influence);
            total += influence;
        }

        for (Biome biome : weights.keySet()) {
            weights.put(biome, weights.get(biome) / total);
        }

        return weights;
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
