package spawners;

import objects.Cactus;
import objects.Feature;

public class CactusSpawner implements FeatureSpawner {
    public static final CactusSpawner INSTANCE = new CactusSpawner();

    @Override
    public Feature spawn(float x, float y, float z, long seed) {
        return new Cactus(x, y, z, seed);
    }
}
