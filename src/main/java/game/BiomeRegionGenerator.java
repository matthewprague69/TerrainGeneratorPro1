package game;



import java.util.HashMap;
import java.util.Map;
import java.util.Random;
public class BiomeRegionGenerator {
    private static final int REGION_SIZE = 10; // Average region size (in chunks)
    private final Map<Point, BiomeCenter> centers = new HashMap<>();
    private final Random rand;

    public BiomeRegionGenerator(long seed) {
        this.rand = new Random(seed ^ 0xCAFEBABE);

        // Pre-generate biome centers with random offsets
        for (int gx = -100; gx <= 100; gx++) {
            for (int gz = -100; gz <= 100; gz++) {
                Biome b = pickRandomBiome(rand);
                int offsetX = rand.nextInt(REGION_SIZE) - REGION_SIZE / 2; // random -32..+32
                int offsetZ = rand.nextInt(REGION_SIZE) - REGION_SIZE / 2;
                float influenceRadius = REGION_SIZE * (0.8f + rand.nextFloat() * 0.6f); // random 0.8x..1.4x

                centers.put(new Point(gx, gz), new BiomeCenter(b, offsetX, offsetZ, influenceRadius));
            }
        }
    }

    public Biome getBiomeAtChunk(int cx, int cz) {
        int gx = Math.floorDiv(cx, REGION_SIZE);
        int gz = Math.floorDiv(cz, REGION_SIZE);

        // Find nearest center
        double minDist = Double.MAX_VALUE;
        Biome best = Biome.PLAINS;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Point p = new Point(gx + dx, gz + dz);
                BiomeCenter center = centers.get(p);
                if (center == null) continue;

                int centerX = (gx + dx) * REGION_SIZE + REGION_SIZE / 2 + center.offsetX;
                int centerZ = (gz + dz) * REGION_SIZE + REGION_SIZE / 2 + center.offsetZ;
                double dist = Math.sqrt(Math.pow(cx - centerX, 2) + Math.pow(cz - centerZ, 2)) / center.influenceRadius;
                if (dist < minDist) {
                    minDist = dist;
                    best = center.biome;
                }
            }
        }

        return best;
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
            if (sum >= threshold)
                return b;
        }
        return Biome.PLAINS;
    }

    private static record Point(int x, int z) {}

    private static class BiomeCenter {
        final Biome biome;
        final int offsetX, offsetZ;
        final float influenceRadius;

        BiomeCenter(Biome biome, int offsetX, int offsetZ, float influenceRadius) {
            this.biome = biome;
            this.offsetX = offsetX;
            this.offsetZ = offsetZ;
            this.influenceRadius = influenceRadius;
        }
    }
}
