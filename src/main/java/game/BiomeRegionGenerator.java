package game;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

public class BiomeRegionGenerator {
    private static final int BASE_REGION_SIZE = Chunk.SIZE * 8;
    private static final float MIN_RADIUS_SCALE = 0.6f;
    private static final float MAX_RADIUS_SCALE = 1.4f;

    private final long seed;
    private final int searchRadius;

    public BiomeRegionGenerator(long seed) {
        this.seed = seed;
        this.searchRadius = computeSearchRadius();
    }

    public Biome getDominantBiome(double wx, double wz) {
        return getBiomeWeights(wx, wz).entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(Biome.PLAINS);
    }

    public Map<Biome, Float> getBiomeWeights(double wx, double wz) {
        int cellX = (int) Math.floor(wx / BASE_REGION_SIZE);
        int cellZ = (int) Math.floor(wz / BASE_REGION_SIZE);

        Map<Biome, Float> weights = new EnumMap<>(Biome.class);
        float total = 0f;

        BiomeCenter nearest = null;
        double nearestScore = Double.MAX_VALUE;

        for (int dx = -searchRadius; dx <= searchRadius; dx++) {
            for (int dz = -searchRadius; dz <= searchRadius; dz++) {
                BiomeCenter center = generateCenter(cellX + dx, cellZ + dz);
                double dxw = wx - center.x;
                double dzw = wz - center.z;
                double dist = Math.sqrt(dxw * dxw + dzw * dzw);
                double normalized = dist / center.influenceRadius;

                if (normalized < nearestScore) {
                    nearestScore = normalized;
                    nearest = center;
                }

                if (dist > center.influenceRadius) {
                    continue;
                }

                float influence = (float) (1.0 - normalized);
                float weight = influence * influence;
                weights.merge(center.biome, weight, Float::sum);
                total += weight;
            }
        }

        if (weights.isEmpty() && nearest != null) {
            weights.put(nearest.biome, 1f);
            return weights;
        }

        if (total > 0f) {
            for (Biome biome : weights.keySet()) {
                weights.put(biome, weights.get(biome) / total);
            }
        }

        return weights;
    }

    private BiomeCenter generateCenter(int cellX, int cellZ) {
        Random rand = randomForCell(cellX, cellZ);

        double jitterX = (rand.nextDouble() - 0.5) * BASE_REGION_SIZE;
        double jitterZ = (rand.nextDouble() - 0.5) * BASE_REGION_SIZE;

        Biome biome = pickRandomBiome(rand);
        float radius = BASE_REGION_SIZE * (MIN_RADIUS_SCALE + rand.nextFloat() * (MAX_RADIUS_SCALE - MIN_RADIUS_SCALE));
        radius *= biome.patchScale;

        double centerX = (cellX + 0.5) * BASE_REGION_SIZE + jitterX;
        double centerZ = (cellZ + 0.5) * BASE_REGION_SIZE + jitterZ;

        return new BiomeCenter(biome, centerX, centerZ, radius);
    }

    private int computeSearchRadius() {
        float maxScale = 1f;
        for (Biome b : Biome.values()) {
            maxScale = Math.max(maxScale, b.patchScale);
        }
        float maxRadius = BASE_REGION_SIZE * MAX_RADIUS_SCALE * maxScale;
        return (int) Math.ceil(maxRadius / BASE_REGION_SIZE) + 1;
    }

    private Random randomForCell(int cellX, int cellZ) {
        long hash = seed;
        hash ^= mix((long) cellX * 0x9E3779B97F4A7C15L);
        hash ^= mix((long) cellZ * 0xC2B2AE3D27D4EB4FL);
        return new Random(mix(hash));
    }

    private long mix(long value) {
        long z = value;
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    private Biome pickRandomBiome(Random rand) {
        float total = 0f;
        for (Biome b : Biome.values()) {
            total += b.spawnChance;
        }

        float threshold = rand.nextFloat() * total;
        float sum = 0f;
        for (Biome b : Biome.values()) {
            sum += b.spawnChance;
            if (sum >= threshold) {
                return b;
            }
        }
        return Biome.PLAINS;
    }

    private static class BiomeCenter {
        final Biome biome;
        final double x;
        final double z;
        final float influenceRadius;

        BiomeCenter(Biome biome, double x, double z, float influenceRadius) {
            this.biome = biome;
            this.x = x;
            this.z = z;
            this.influenceRadius = influenceRadius;
        }
    }
}
