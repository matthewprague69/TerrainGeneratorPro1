package util;

import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;
import java.util.Arrays;

public class VertexBatchBuilder {
    private float[] data = new float[8192];
    private int size = 0;

    public void append(float[] vertices, float offsetX, float offsetY, float offsetZ) {
        for (int i = 0; i < vertices.length; i += 5) {
            ensureCapacity(5);
            data[size++] = vertices[i] + offsetX;
            data[size++] = vertices[i + 1] + offsetY;
            data[size++] = vertices[i + 2] + offsetZ;
            data[size++] = vertices[i + 3];
            data[size++] = vertices[i + 4];
        }
    }

    public int getVertexCount() {
        return size / 5;
    }

    public FloatBuffer toBuffer() {
        FloatBuffer buffer = BufferUtils.createFloatBuffer(size);
        buffer.put(data, 0, size).flip();
        return buffer;
    }

    private void ensureCapacity(int floats) {
        int needed = size + floats;
        if (needed > data.length) {
            int newSize = Math.max(needed, data.length * 2);
            data = Arrays.copyOf(data, newSize);
        }
    }
}
