package spawners;

import objects.Feature;
import objects.Grass;

public class DeadGrassSpawner implements FeatureSpawner {
    public static final DeadGrassSpawner INSTANCE = new DeadGrassSpawner();

    private DeadGrassSpawner() {
    }

    @Override
    public Feature spawn(float x, float y, float z, long seed) {
        return new Grass(x, y, z, 0.05f, 0.05f, 0.05f, seed); // Almost black, seeded
    }

    @Override
    public int hashCode() {
        return 31; // Constant since it's a singleton
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof DeadGrassSpawner;
    }
}
