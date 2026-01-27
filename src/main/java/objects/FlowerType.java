package objects;
public enum FlowerType {
    ROSE(0.4f, 0.1f, 1.0f, 0.3f), // pink/red
    TULIP(0.5f, 1.0f, 0.5f, 0.25f), // soft green-white
    DAISY(0.5f, 1.0f, 1.0f, 0.2f); // yellow-white

    public final float r, g, b;
    public final float height;

    FlowerType(float r, float g, float b, float height) {
        this.r = r;
        this.g = g;
        this.b = b;
        this.height = height;
    }
}
