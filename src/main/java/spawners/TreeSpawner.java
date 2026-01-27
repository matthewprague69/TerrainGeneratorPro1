package spawners;

import objects.Feature;
import objects.Tree;
import objects.TreeType;

public class TreeSpawner implements FeatureSpawner {
    private final TreeType type;

    public TreeSpawner(TreeType type) {
        this.type = type;
    }

    @Override
    public Feature spawn(float x, float y, float z, long seed) {
        return new Tree(x, y, z, type, true, seed);
    }

    @Override
    public int hashCode() {
        return type.hashCode();
    }

    public TreeType getType() {
        return type;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof TreeSpawner other) {
            return this.type == other.type;
        }
        return false;
    }
}
