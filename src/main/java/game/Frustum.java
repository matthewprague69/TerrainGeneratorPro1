package game;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import java.nio.FloatBuffer;

public class Frustum {
    private final float[][] planes = new float[6][4]; // 6 frustum planes

    public static Frustum fromOpenGL() {
        FloatBuffer projBuffer = BufferUtils.createFloatBuffer(16);
        FloatBuffer modelBuffer = BufferUtils.createFloatBuffer(16);
        FloatBuffer clipBuffer = BufferUtils.createFloatBuffer(16);

        GL11.glGetFloatv(GL11.GL_PROJECTION_MATRIX, projBuffer);
        GL11.glGetFloatv(GL11.GL_MODELVIEW_MATRIX, modelBuffer);

        float[] proj = new float[16];
        float[] model = new float[16];
        projBuffer.get(proj).flip();
        modelBuffer.get(model).flip();

        float[] clip = new float[16];
        for (int i = 0; i < 4; i++) {
            int row = i * 4;
            for (int j = 0; j < 4; j++) {
                clip[row + j] = proj[0 + j] * model[row + 0]
                        + proj[4 + j] * model[row + 1]
                        + proj[8 + j] * model[row + 2]
                        + proj[12 + j] * model[row + 3];
            }
        }

        Frustum frustum = new Frustum();

        // Extract planes
        frustum.extractPlane(0, clip, -1, 0); // Right
        frustum.extractPlane(1, clip, 1, 0); // Left
        frustum.extractPlane(2, clip, 1, 1); // Bottom
        frustum.extractPlane(3, clip, -1, 1); // Top
        frustum.extractPlane(4, clip, -1, 2); // Far
        frustum.extractPlane(5, clip, 1, 2); // Near

        return frustum;
    }

    private void extractPlane(int plane, float[] clip, int sign, int column) {
        int base = column;
        planes[plane][0] = clip[3] + sign * clip[base];
        planes[plane][1] = clip[7] + sign * clip[base + 4];
        planes[plane][2] = clip[11] + sign * clip[base + 8];
        planes[plane][3] = clip[15] + sign * clip[base + 12];

        normalizePlane(plane);
    }

    private void normalizePlane(int plane) {
        float[] p = planes[plane];
        float length = (float) Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
        for (int i = 0; i < 4; i++) {
            p[i] /= length;
        }
    }

    public boolean isBoxVisible(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        for (int i = 0; i < 6; i++) {
            float[] p = planes[i];
            if (p[0] * ((p[0] < 0) ? minX : maxX) +
                    p[1] * ((p[1] < 0) ? minY : maxY) +
                    p[2] * ((p[2] < 0) ? minZ : maxZ) +
                    p[3] <= 0) {
                return false;
            }
        }
        return true;
    }
}
