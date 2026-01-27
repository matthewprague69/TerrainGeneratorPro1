package spawners;

import objects.Feature;
import objects.Grass;

public class GrassSpawner implements FeatureSpawner {
    private final String textureName;

    public GrassSpawner() {
        this("grass.png"); // default
    }

    public GrassSpawner(String textureName) {
        this.textureName = textureName;
    }

    @Override
    public Feature spawn(float x, float y, float z, long seed) {
        return new Grass(x, y, z, textureName, seed);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GrassSpawner)) return false;
        GrassSpawner other = (GrassSpawner) o;
        return textureName.equals(other.textureName);
    }

    @Override
    public int hashCode() {
        return textureName.hashCode();
    }
}
