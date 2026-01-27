package util;

import game.Frustum;

public class BoundingBox {
    public final float minX, minY, minZ;
    public final float maxX, maxY, maxZ;

    public BoundingBox(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    public boolean intersects(Frustum frustum) {
        return frustum.isBoxVisible(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
