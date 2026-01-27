package spawners;

import objects.Feature;

public interface FeatureSpawner {
    Feature spawn(float x, float y, float z, long seed);
}
