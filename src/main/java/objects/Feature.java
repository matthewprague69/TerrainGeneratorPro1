package objects;

import game.TerrainManager;

import static org.lwjgl.opengl.GL11.*;

public abstract class Feature {
    public float x, y, z;

    public Feature(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public void dispose() {

    }

    public abstract void draw();

    public void drawSimplified() {
        draw();
    }

    public void drawDepth() {
        draw();
    }

    public void drawShadow(TerrainManager terrain, float[] lightDir, float strength) {
        float radius = getShadowRadius();
        float height = getShadowHeight();
        if (radius <= 0f || height <= 0f || strength <= 0f) {
            return;
        }

        float dirY = lightDir[1];
        if (Math.abs(dirY) < 0.15f) {
            return;
        }

        float offset = -height / dirY;
        float shadowX = x + lightDir[0] * offset;
        float shadowZ = z + lightDir[2] * offset;
        float shadowY = terrain.getHeight(shadowX, shadowZ) + 0.03f;

        float horizontalLen = (float) Math.sqrt(lightDir[0] * lightDir[0] + lightDir[2] * lightDir[2]);
        float hx = 0f;
        float hz = 1f;
        if (horizontalLen > 0.001f) {
            hx = lightDir[0] / horizontalLen;
            hz = lightDir[2] / horizontalLen;
        }
        float px = -hz;
        float pz = hx;

        float stretch = Math.min(3.5f, 1f / Math.max(0.25f, Math.abs(dirY)));
        float longAxis = radius * stretch;
        float shortAxis = radius;

        glPushAttrib(GL_ENABLE_BIT | GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_POLYGON_BIT);
        glDisable(GL_TEXTURE_2D);
        glDisable(GL_LIGHTING);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(false);
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(-1f, -1f);
        glColor4f(0f, 0f, 0f, strength * getShadowAlpha());

        int segments = 18;
        glBegin(GL_TRIANGLE_FAN);
        glVertex3f(shadowX, shadowY, shadowZ);
        for (int i = 0; i <= segments; i++) {
            double angle = 2 * Math.PI * i / segments;
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            float offsetX = px * cos * shortAxis + hx * sin * longAxis;
            float offsetZ = pz * cos * shortAxis + hz * sin * longAxis;
            glVertex3f(shadowX + offsetX, shadowY, shadowZ + offsetZ);
        }
        glEnd();

        glPopAttrib();
    }

    protected float getShadowRadius() {
        return 0f;
    }

    protected float getShadowHeight() {
        return 0f;
    }

    protected float getShadowAlpha() {
        return 0.35f;
    }
}
