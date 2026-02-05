package game;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import util.MatrixUtils;
import java.nio.FloatBuffer;

public class Frustum {
    private final float[][] planes = new float[6][4]; // 6 frustum planes

    public static Frustum fromOpenGL() {
        FloatBuffer projBuffer = BufferUtils.createFloatBuffer(16);
        FloatBuffer modelBuffer = BufferUtils.createFloatBuffer(16);

        GL11.glGetFloatv(GL11.GL_PROJECTION_MATRIX, projBuffer);
        GL11.glGetFloatv(GL11.GL_MODELVIEW_MATRIX, modelBuffer);

        projBuffer.rewind();
        modelBuffer.rewind();

        float[] proj = new float[16];
        float[] model = new float[16];
        projBuffer.get(proj);
        modelBuffer.get(model);

        float[] clip = MatrixUtils.multiply(proj, model);

        Frustum frustum = new Frustum();

        // Column-major extraction from clip matrix.
        frustum.setPlane(0, clip[3] - clip[0], clip[7] - clip[4], clip[11] - clip[8], clip[15] - clip[12]); // Right
        frustum.setPlane(1, clip[3] + clip[0], clip[7] + clip[4], clip[11] + clip[8], clip[15] + clip[12]); // Left
        frustum.setPlane(2, clip[3] + clip[1], clip[7] + clip[5], clip[11] + clip[9], clip[15] + clip[13]); // Bottom
        frustum.setPlane(3, clip[3] - clip[1], clip[7] - clip[5], clip[11] - clip[9], clip[15] - clip[13]); // Top
        frustum.setPlane(4, clip[3] - clip[2], clip[7] - clip[6], clip[11] - clip[10], clip[15] - clip[14]); // Far
        frustum.setPlane(5, clip[3] + clip[2], clip[7] + clip[6], clip[11] + clip[10], clip[15] + clip[14]); // Near

        return frustum;
    }

    private void setPlane(int plane, float a, float b, float c, float d) {
        planes[plane][0] = a;
        planes[plane][1] = b;
        planes[plane][2] = c;
        planes[plane][3] = d;
        normalizePlane(plane);
    }

    private void normalizePlane(int plane) {
        float[] p = planes[plane];
        float length = (float) Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
        if (length < 1e-6f) {
            return;
        }
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
