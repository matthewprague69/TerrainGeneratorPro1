package spawners;

import objects.Feature;
import objects.Flower;
import objects.FlowerType;

public class FlowerSpawner implements FeatureSpawner {
    private final FlowerType type;

    public FlowerSpawner(FlowerType type) {
        this.type = type;
    }

    @Override
    public Feature spawn(float x, float y, float z, long seed) {
        return new Flower(x, y, z, type, seed); // Pass the seed to ensure deterministic generation
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FlowerSpawner fs && fs.type == this.type;
    }

    @Override
    public int hashCode() {
        return type.hashCode();
    }
}
