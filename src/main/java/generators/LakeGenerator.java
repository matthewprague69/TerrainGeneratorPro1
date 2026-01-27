package generators;

import game.Biome;
import game.TerrainManager;
import game.Chunk;
import objects.Feature;
import objects.Lake;
import spawners.FeatureSpawner;
import spawners.LakeSpawner;
import util.FeatureUtil;

import java.util.List;
import java.util.Map;
import java.util.Random;

public class LakeGenerator {
    public static void generateLakes(
            Chunk chunk,
            Biome biome,
            float[][] heights,
            boolean[][] featureMask,
            List<Feature> features,
            TerrainManager manager) {

        if (biome.features == null || biome.features.isEmpty())
            return;

        int SIZE = Chunk.SIZE;
        int cx = chunk.cx;
        int cz = chunk.cz;
        float scale = chunk.getScale();

        long chunkSeed = FeatureUtil.hashSeed(cx, 0, cz, manager.getSeed());
        Random rand = new Random(chunkSeed);

        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                if (featureMask[x][z])
                    continue;

                float wx = (cx * SIZE + x + 0.5f) * scale;
                float wz = (cz * SIZE + z + 0.5f) * scale;
                float wy = heights[x][z];

                float avgSlope = averageSurroundingSlope(heights, x, z, 2);
                if (avgSlope > 0.7f)
                    continue;

                for (Map.Entry<FeatureSpawner, Float> entry : biome.features.entrySet()) {
                    if (!(entry.getKey() instanceof LakeSpawner))
                        continue;

                    if (rand.nextFloat() < entry.getValue()) {
                        int ix = cx * SIZE + x;
                        int iz = cz * SIZE + z;
                        int iy = Math.round(wy * 1000);
                        long seed = FeatureUtil.hashSeed(ix, iy, iz, manager.getSeed());

                        Feature f = entry.getKey().spawn(wx, wy, wz, seed);
                        if (!(f instanceof Lake lake))
                            continue;
                        if (!isLakeWithinChunk(lake, cx, cz, scale, SIZE))
                            continue;
                        if (!canPlaceLake(lake, cx, cz, scale, SIZE, featureMask))
                            continue;

                        features.add(lake);
                        carveLakeHole(lake, chunk, cx, cz, scale, heights, featureMask);
                        markLakeArea(lake, cx, cz, scale, SIZE, featureMask);
                    }
                }
            }
        }
    }

    private static float averageSurroundingSlope(float[][] heights, int centerX, int centerZ, int radius) {
        int count = 0;
        float totalSlope = 0;
        int SIZE = Chunk.SIZE;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = centerX + dx;
                int z = centerZ + dz;

                if (x >= 0 && z >= 0 && x < SIZE && z < SIZE) {
                    totalSlope += computeSlope(heights, x, z);
                    count++;
                }
            }
        }
        return (count > 0) ? totalSlope / count : 1f;
    }

    private static float computeSlope(float[][] heights, int x, int z) {
        int SIZE = Chunk.SIZE;
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

    private static boolean isLakeWithinChunk(Lake lake, int cx, int cz, float scale, int SIZE) {
        float lakeMinX = lake.x - lake.getRadiusX();
        float lakeMaxX = lake.x + lake.getRadiusX();
        float lakeMinZ = lake.z - lake.getRadiusZ();
        float lakeMaxZ = lake.z + lake.getRadiusZ();

        float chunkMinX = cx * SIZE * scale;
        float chunkMaxX = (cx + 1) * SIZE * scale;
        float chunkMinZ = cz * SIZE * scale;
        float chunkMaxZ = (cz + 1) * SIZE * scale;

        return lakeMinX >= chunkMinX && lakeMaxX <= chunkMaxX
                && lakeMinZ >= chunkMinZ && lakeMaxZ <= chunkMaxZ;
    }

    private static boolean canPlaceLake(Lake lake, int cx, int cz, float scale, int SIZE, boolean[][] featureMask) {
        float buffer = 4.0f;
        float totalRadiusX = lake.getRadiusX() + buffer;
        float totalRadiusZ = lake.getRadiusZ() + buffer;
        float centerWX = lake.x;
        float centerWZ = lake.z;

        int startX = Math.max(0, (int) ((centerWX / scale) - cx * SIZE - totalRadiusX));
        int endX = Math.min(SIZE - 1, (int) ((centerWX / scale) - cx * SIZE + totalRadiusX));
        int startZ = Math.max(0, (int) ((centerWZ / scale) - cz * SIZE - totalRadiusZ));
        int endZ = Math.min(SIZE - 1, (int) ((centerWZ / scale) - cz * SIZE + totalRadiusZ));

        for (int lx = startX; lx <= endX; lx++) {
            for (int lz = startZ; lz <= endZ; lz++) {
                float dx = ((cx * SIZE + lx + 0.5f) * scale) - centerWX;
                float dz = ((cz * SIZE + lz + 0.5f) * scale) - centerWZ;
                if ((dx * dx) / (totalRadiusX * totalRadiusX) + (dz * dz) / (totalRadiusZ * totalRadiusZ) <= 1f) {
                    if (featureMask[lx][lz]) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static void markLakeArea(Lake lake, int cx, int cz, float scale, int SIZE, boolean[][] featureMask) {
        float buffer = 4.0f;
        float totalRadiusX = lake.getRadiusX() + buffer;
        float totalRadiusZ = lake.getRadiusZ() + buffer;
        float centerWX = lake.x;
        float centerWZ = lake.z;

        int startX = Math.max(0, (int) ((centerWX / scale) - cx * SIZE - totalRadiusX));
        int endX = Math.min(SIZE - 1, (int) ((centerWX / scale) - cx * SIZE + totalRadiusX));
        int startZ = Math.max(0, (int) ((centerWZ / scale) - cz * SIZE - totalRadiusZ));
        int endZ = Math.min(SIZE - 1, (int) ((centerWZ / scale) - cz * SIZE + totalRadiusZ));

        for (int lx = startX; lx <= endX; lx++) {
            for (int lz = startZ; lz <= endZ; lz++) {
                float dx = ((cx * SIZE + lx + 0.5f) * scale) - centerWX;
                float dz = ((cz * SIZE + lz + 0.5f) * scale) - centerWZ;
                if ((dx * dx) / (totalRadiusX * totalRadiusX) + (dz * dz) / (totalRadiusZ * totalRadiusZ) <= 1f) {
                    featureMask[lx][lz] = true;
                }
            }
        }
    }

    private static void carveLakeHole(Lake lake, Chunk chunk, int cx, int cz, float scale, float[][] heights,
                                      boolean[][] featureMask) {
        int SIZE = Chunk.SIZE;
        float wx = lake.x;
        float wz = lake.z;
        float radiusX = lake.getRadiusX();
        float radiusZ = lake.getRadiusZ();
        float depth = lake.getDepth();

        int startX = (int) ((wx / scale) - cx * SIZE - radiusX);
        int endX = (int) ((wx / scale) - cx * SIZE + radiusX);
        int startZ = (int) ((wz / scale) - cz * SIZE - radiusZ);
        int endZ = (int) ((wz / scale) - cz * SIZE + radiusZ);

        float requiredLakeY = Float.MAX_VALUE;

        for (int ix = startX; ix <= endX; ix++) {
            for (int iz = startZ; iz <= endZ; iz++) {
                if (ix < 0 || iz < 0 || ix >= SIZE || iz >= SIZE)
                    continue;

                float wxi = (cx * SIZE + ix + 0.5f) * scale;
                float wzi = (cz * SIZE + iz + 0.5f) * scale;
                float dx = wxi - wx;
                float dz = wzi - wz;

                float angle = (float) Math.atan2(dz, dx);
                float shapeOffset = lake.getShapeOffset(angle);

                float localRadiusX = radiusX * shapeOffset;
                float localRadiusZ = radiusZ * shapeOffset;

                float dist = (dx * dx) / (localRadiusX * localRadiusX) + (dz * dz) / (localRadiusZ * localRadiusZ);
                if (dist < 1f) {
                    float falloff = 1f - (float) Math.sqrt(dist);
                    float drop = depth * (float) Math.pow(falloff, 1.5f);
                    float requiredY = heights[ix][iz] + drop;
                    requiredLakeY = Math.min(requiredLakeY, requiredY);
                }
            }
        }

        lake.y = requiredLakeY;

        for (int ix = startX; ix <= endX; ix++) {
            for (int iz = startZ; iz <= endZ; iz++) {
                if (ix < 0 || iz < 0 || ix >= SIZE || iz >= SIZE)
                    continue;

                float wxi = (cx * SIZE + ix + 0.5f) * scale;
                float wzi = (cz * SIZE + iz + 0.5f) * scale;
                float dx = wxi - wx;
                float dz = wzi - wz;

                float angle = (float) Math.atan2(dz, dx);
                float shapeOffset = lake.getShapeOffset(angle);

                float localRadiusX = radiusX * shapeOffset;
                float localRadiusZ = radiusZ * shapeOffset;

                float dist = (dx * dx) / (localRadiusX * localRadiusX) + (dz * dz) / (localRadiusZ * localRadiusZ);
                if (dist < 1f) {
                    float falloff = 1f - (float) Math.sqrt(dist);
                    float drop = depth * (float) Math.pow(falloff, 1.5f);
                    float targetY = lake.y - drop;
                    heights[ix][iz] = Math.min(heights[ix][iz], targetY);
                    featureMask[ix][iz] = true;
                }
            }
        }
    }
}
