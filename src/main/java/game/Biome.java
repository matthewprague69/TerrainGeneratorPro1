

package game;
import objects.FlowerType;
import objects.TreeType;
import spawners.*;

import java.util.Map;


public enum Biome {
        /*smooth*/
        /*PLAINS(0.1f, 0.005f, 100f, 30f, "grass.png", "dirt.png", "rock.png", 0.15f, 0.15f),
        SWAMP(0.2f, 0.01f, 3f, 2f, "swamp_grass.png", "dirt.png", "rock.png", 0.15f, 0.15f),
        SAND(0.3f, 0.03f, 10f, 5f, "sand.png", "sand.png", "sand_rock.png", 0.12f, 0.15f),
        HILLS(0.4f, 0.01f, 25f, 15f, "grass.png", "dirt.png", "rock.png", 0.25f, 0.15f),
        MOUNTAINS(0.5f, 0.02f, 35f, 40f, "grass.png", "dirt.png", "rock.png", 0.2f, 0.15f),
        HIGH_MOUNTAINS(0.6f, 0.03f, 70f, 70f, "grass.png", "dirt.png", "rock.png", 0.15f, 0.3f),
        DEAD_FOREST(0.7f, 0.01f, 1f, 7f, "dead_grass.png", "dark_dirt.png", "dark_rock.png", 0.05f, 0.15f),
        BLOOM(0.8f, 0.01f, 7f, 5f, "bloom_grass.png", "bloom_dirt.png", "gold_ore_rock.png", 0.2f, 0.15f),
        BIG_TREES(0.9f, 0.01f, 4f, 2f, "dark_grass.png", "dirt.png", "rock.png", 0.1f, 0.15f),
        PREHISTORIC(1.0f, 0.005f, 150f, 5f, "prehistoric_grass.png", "dark_dirt.png", "rock.png", 0.15f, 0.15f);*/
        PLAINS(0.20f, 0.005f, 100f, 30f, "grass.png", "dirt.png", "rock.png", 0.15f, 0.25f),
        HILLS(0.50f, 0.01f, 25f, 15f, "grass.png", "dirt.png", "rock.png", 0.25f, 0.25f),
        SAND(0.40f, 0.03f, 10f, 5f, "sand.png", "sand.png", "sand_rock.png", 0.12f, 0.25f),
        MOUNTAINS(0.75f, 0.02f, 35f, 40f, "grass.png", "dirt.png", "rock.png", 0.2f, 0.25f),
        HIGH_MOUNTAINS(0.90f, 0.03f, 70f, 70f, "grass.png", "dirt.png", "rock.png", 0.035f, 0.25f),
        DEAD_FOREST(0.1f, 0.01f, 1f, 7f, "dead_grass.png", "dark_dirt.png", "dark_rock.png", 0.05f, 0.25f),
        BLOOM(0.2f, 0.01f, 7f, 5f, "bloom_grass.png", "bloom_dirt.png", "gold_ore_rock.png", 0.2f, 0.25f),
        BIG_TREES(0.35f, 0.01f, 4f, 2f, "dark_grass.png", "dirt.png", "rock.png", 0.1f, 0.25f),
        PREHISTORIC(0.37f, 0.005f, 150f, 5f, "prehistoric_grass.png", "dark_dirt.png", "rock.png", 0.15f, 0.25f),
        SWAMP(0.01f, 0.01f, 3f, 2f, "swamp_grass.png", "dirt.png", "rock.png", 0.15f, 0.25f),
        SPRUCE_FOREST(0.6f, 0.008f, 18f, 10f, "dark_grass.png", "dirt.png", "rock.png", 0.2f, 0.25f);
        //OCEAN(0.1f, 0.008f, 0f, 0f, "dark_grass.png", "dirt.png", "rock.png", 0.5f, 0.01f);




        public final float center;
        public final float frequency, amplitude, baseHeight;
        public final float spawnChance;
        public final float blendRadius;
        public final String grassTex, dirtTex, rockTex;
        public Map<FeatureSpawner, Float> features;

        // Global multiplier for all feature spawn rates
        public static float featureSpawnMultiplier = 0.25f;

        Biome(float center, float frequency, float amplitude, float baseHeight,
                        String grassTex, String dirtTex, String rockTex,
                        float spawnChance, float blendRadius) {
                this.center = center;
                this.frequency = frequency;
                this.amplitude = amplitude;
                this.baseHeight = baseHeight;
                this.grassTex = grassTex;
                this.dirtTex = dirtTex;
                this.rockTex = rockTex;
                this.spawnChance = spawnChance;
                this.blendRadius = blendRadius;
        }

        static {
                float m = featureSpawnMultiplier;

                PLAINS.features = Map.of(
                        new TreeSpawner(TreeType.BIRCH), 0.006f * m,
                        new TreeSpawner(TreeType.OAK), 0.001f * m,
                        new TreeSpawner(TreeType.GIANT_OAK), 0.0001f * m,
                        new TreeSpawner(TreeType.BUSH), 0.003f * m,
                        new TreeSpawner(TreeType.RED_BERRY_BUSH), 0.001f * m,
                        new GrassSpawner(PLAINS.grassTex), 0.4f * m,
                        new FlowerSpawner(FlowerType.ROSE), 0.01f * m,
                        new FlowerSpawner(FlowerType.TULIP), 0.008f * m,
                        new LakeSpawner(), 0.001f * m);

                HILLS.features = Map.of(
                        new TreeSpawner(TreeType.OAK), 0.008f * m,
                        new TreeSpawner(TreeType.BIRCH), 0.001f * m,
                        new TreeSpawner(TreeType.BUSH), 0.0015f * m,
                        new TreeSpawner(TreeType.RED_BERRY_BUSH), 0.0005f * m,
                        new GrassSpawner(HILLS.grassTex), 0.4f * m,
                        new FlowerSpawner(FlowerType.DAISY), 0.007f * m);

                MOUNTAINS.features = Map.of(
                        new GrassSpawner(MOUNTAINS.grassTex), 0.4f * m,
                        new TreeSpawner(TreeType.OAK), 0.004f * m,
                        new TreeSpawner(TreeType.BUSH), 0.0015f * m,
                        new FlowerSpawner(FlowerType.TULIP), 0.008f * m);

                HIGH_MOUNTAINS.features = Map.of(
                        new GrassSpawner(HIGH_MOUNTAINS.grassTex), 0.4f * m,
                        new TreeSpawner(TreeType.BUSH), 0.003f * m,
                        new TreeSpawner(TreeType.RED_BERRY_BUSH), 0.001f * m,
                        new FlowerSpawner(FlowerType.TULIP), 0.008f * m);

                SAND.features = Map.of(
                        CactusSpawner.INSTANCE, 0.02f * m);

                DEAD_FOREST.features = Map.of(
                        DeadTreeSpawner.INSTANCE, 0.007f * m,
                        new GrassSpawner(DEAD_FOREST.grassTex), 0.4f * m);

                BLOOM.features = Map.of(
                        new TreeSpawner(TreeType.BLOSSOM), 0.008f * m,
                        new TreeSpawner(TreeType.GOLIATH_BLOSSOM), 0.00001f * m,
                        new TreeSpawner(TreeType.BLOOMING_BUSH), 0.01f * m,
                        new FlowerSpawner(FlowerType.TULIP), 0.2f * m,
                        new FlowerSpawner(FlowerType.DAISY), 0.2f * m,
                        new FlowerSpawner(FlowerType.ROSE), 0.2f * m,
                        new GrassSpawner(BLOOM.grassTex), 0.4f * m,
                        new LakeSpawner(), 0.0005f * m);

                BIG_TREES.features = Map.of(
                        new TreeSpawner(TreeType.GIANT_OAK), 0.005f * m,
                        new FlowerSpawner(FlowerType.TULIP), 0.1f * m,
                        new FlowerSpawner(FlowerType.DAISY), 0.1f * m,
                        new FlowerSpawner(FlowerType.ROSE), 0.1f * m,
                        new GrassSpawner(BIG_TREES.grassTex), 0.4f * m,
                        new LakeSpawner(), 0.0005f * m);

                PREHISTORIC.features = Map.of(
                        new TreeSpawner(TreeType.GIANT_OAK), 0.005f * m,
                        new GrassSpawner(PREHISTORIC.grassTex), 0.4f * m,
                        new LakeSpawner(), 0.01f * m,
                        new FlowerSpawner(FlowerType.TULIP), 0.008f * m);

                SWAMP.features = Map.of(
                        new TreeSpawner(TreeType.SWAMP_TREE), 0.08f * m,
                        new GrassSpawner(SWAMP.grassTex), 0.4f * m,
                        new LakeSpawner(), 0.2f * m);

                SPRUCE_FOREST.features = Map.of(
                        new TreeSpawner(TreeType.SPRUCE), 0.01f * m,
                        new TreeSpawner(TreeType.BUSH), 0.002f * m,
                        new GrassSpawner(SPRUCE_FOREST.grassTex), 0.1f * m,
                        new FlowerSpawner(FlowerType.DAISY), 0.01f * m,
                        new LakeSpawner(), 0.001f * m);

        }
}
