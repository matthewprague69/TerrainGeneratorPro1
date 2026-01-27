package spawners;

import objects.Feature;
import objects.Tree;
import objects.TreeType;

public class DeadTreeSpawner implements FeatureSpawner {
    public static final DeadTreeSpawner INSTANCE = new DeadTreeSpawner();

    private DeadTreeSpawner() {
    }

    @Override
    public Feature spawn(float x, float y, float z, long seed) {
        return new Tree(x, y, z, TreeType.OAK, false, seed); // Leafless oak tree, seeded
    }

    @Override
    public int hashCode() {
        return 73; // Arbitrary constant
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof DeadTreeSpawner;
    }
}
