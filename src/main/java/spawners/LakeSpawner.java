package spawners;

import objects.Feature;
import objects.Lake;

public class LakeSpawner implements FeatureSpawner {
    @Override
    public Feature spawn(float x, float y, float z, long seed) {
        return new Lake(x, y, z, seed);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof LakeSpawner;
    }

    @Override
    public int hashCode() {
        return 7;
    }
}
