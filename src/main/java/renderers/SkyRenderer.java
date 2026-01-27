package renderers;
import static org.lwjgl.opengl.GL11.*;
import java.nio.FloatBuffer;
import org.lwjgl.BufferUtils;
import java.util.Random;

public class SkyRenderer {
    private float timeOfDay = 0.4f;
    private final int cloudSize = 15;
    private final float cloudHeight = 80f;
    private final float spacing = 40f;
    private final float[][] cloudOffsets;

    public SkyRenderer() {
        Random rand = new Random(1337);
        cloudOffsets = new float[cloudSize][cloudSize];
        for (int i = 0; i < cloudSize; i++) {
            for (int j = 0; j < cloudSize; j++) {
                cloudOffsets[i][j] = rand.nextFloat() * 10f;
            }
        }
    }

    public void update(float dt) {
        boolean rewind = org.lwjgl.glfw.GLFW.glfwGetKey(org.lwjgl.glfw.GLFW.glfwGetCurrentContext(),
                org.lwjgl.glfw.GLFW.GLFW_KEY_R) == org.lwjgl.glfw.GLFW.GLFW_PRESS;

        if (rewind) {
            timeOfDay += dt * 0.1f; // Rewind faster than normal
        } else {
            timeOfDay += dt * 0.005f; // Normal forward time
        }

        if (timeOfDay > 1f)
            timeOfDay -= 1f;
        if (timeOfDay < 0f)
            timeOfDay += 1f;
    }

    public float getTimeOfDay() {
        return timeOfDay;
    }

    public void renderSkybox() {
        float brightness = getSkyBrightness();
        float r = 0.6f * brightness;
        float g = 0.75f * brightness;
        float b = 1.0f * brightness;
        glClearColor(r, g, b, 1f);
    }

    public void setLightDirectionFixed() {
        glEnable(GL_LIGHTING);
        glEnable(GL_LIGHT0);
        glEnable(GL_COLOR_MATERIAL);
        glColorMaterial(GL_FRONT_AND_BACK, GL_AMBIENT_AND_DIFFUSE);

        float[] sunDir = getSunDirection();
        float[] lightDir;

        float brightness = getSkyBrightness();

        if (brightness > 0f) {
            // Daytime: sun shines from sun's direction
            lightDir = new float[] { -sunDir[0], -sunDir[1], -sunDir[2], 0f };
        } else {
            // Nighttime: moon shines from opposite direction
            lightDir = new float[] { sunDir[0], sunDir[1], sunDir[2], 0f };
        }
        FloatBuffer lightBuf = BufferUtils.createFloatBuffer(4).put(lightDir).flip();
        glLightfv(GL_LIGHT0, GL_POSITION, lightBuf);

        if (brightness > 0f) {
            // Daylight: yellowish-white sunlight
            FloatBuffer diffuse = BufferUtils.createFloatBuffer(4).put(new float[] {
                    brightness * 2.0f, brightness * 1.8f, brightness * 1.6f, 1f
            }).flip();
            glLightfv(GL_LIGHT0, GL_DIFFUSE, diffuse);

            float ambient = 0.05f + 0.15f * brightness;
            FloatBuffer ambientBuf = BufferUtils.createFloatBuffer(4).put(new float[] {
                    ambient, ambient, ambient, 1f
            }).flip();
            glLightfv(GL_LIGHT0, GL_AMBIENT, ambientBuf);
        } else {
            // Moonlight fades in smoothly based on negative sun height
            float moonPower = Math.abs(sunDir[1]);
            FloatBuffer diffuse = BufferUtils.createFloatBuffer(4).put(new float[] {
                    moonPower * 0.3f, moonPower * 0.35f, moonPower * 0.5f, 1f
            }).flip();
            glLightfv(GL_LIGHT0, GL_DIFFUSE, diffuse);

            float ambient = 0.01f + 0.04f * moonPower;
            FloatBuffer ambientBuf = BufferUtils.createFloatBuffer(4).put(new float[] {
                    ambient * 0.6f, ambient * 0.7f, ambient, 1f
            }).flip();
            glLightfv(GL_LIGHT0, GL_AMBIENT, ambientBuf);
        }
    }

    public void renderSunAndMoon(float camX, float camY, float camZ) {
        glPushAttrib(GL_ENABLE_BIT | GL_CURRENT_BIT);
        glDisable(GL_LIGHTING);
        glDisable(GL_TEXTURE_2D);
        glEnable(GL_DEPTH_TEST);

        float[] sunDir = getSunDirection();
        float dist = 1000f;

        // --- SUN ---
        if (sunDir[1] > -0.1f) {
            glColor3f(1.2f, 1.1f, 0.7f); // <<< Fixed yellow sun color
            drawSphere(camX + sunDir[0] * dist, camY + sunDir[1] * dist, camZ + sunDir[2] * dist, 60f);
        }

        // --- MOON ---
        if (-sunDir[1] > -0.1f) {
            glColor3f(0.8f, 0.9f, 1.0f); // Pale blue moon
            drawSphere(camX - sunDir[0] * dist, camY - sunDir[1] * dist, camZ - sunDir[2] * dist, 40f);
        }

        glEnable(GL_TEXTURE_2D);
        glPopAttrib();
    }


    public void renderClouds(float camX, float camZ) {
        glDisable(GL_LIGHTING);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        glColor4f(1f, 1f, 1f, 0.35f);
        glBegin(GL_QUADS);

        int renderRadius = 10;
        for (int i = -renderRadius; i <= renderRadius; i++) {
            for (int j = -renderRadius; j <= renderRadius; j++) {
                float worldX = camX + i * spacing;
                float worldZ = camZ + j * spacing;

                int cloudX = (int) Math.floor(worldX / spacing);
                int cloudZ = (int) Math.floor(worldZ / spacing);

                float offset = cloudOffsets[Math.floorMod(cloudX, cloudSize)][Math.floorMod(cloudZ, cloudSize)];
                float y = cloudHeight + (float) Math.sin((cloudX + cloudZ + timeOfDay * 50f) * 0.5f + offset) * 3f;
                float size = 20f + (float) Math.sin((cloudX * cloudZ + offset) * 0.1f) * 5f;

                float cx = cloudX * spacing;
                float cz = cloudZ * spacing;

                glVertex3f(cx - size, y, cz - size);
                glVertex3f(cx + size, y, cz - size);
                glVertex3f(cx + size, y, cz + size);
                glVertex3f(cx - size, y, cz + size);
            }
        }
        glEnd();

        glDisable(GL_BLEND);
        glEnable(GL_LIGHTING);
    }

    public float[] getSunDirection() {
        float angle = (timeOfDay - 0.25f) * 360f;
        double radians = Math.toRadians(angle);
        float x = 0f;
        float y = (float) Math.sin(radians);
        float z = (float) Math.cos(radians);
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        return new float[] { x / len, y / len, z / len };
    }

    public float[] getShadowDirection() {
        float[] sunDir = getSunDirection();
        float brightness = getSkyBrightness();
        if (brightness > 0f) {
            return new float[] { -sunDir[0], -sunDir[1], -sunDir[2] };
        }
        return new float[] { sunDir[0], sunDir[1], sunDir[2] };
    }

    public float getShadowStrength() {
        return Math.min(0.65f, getSkyBrightness());
    }

    private void drawSphere(float cx, float cy, float cz, float r) {
        int lats = 16;
        int longs = 16;

        glPushMatrix();
        glTranslatef(cx, cy, cz);
        for (int i = 0; i <= lats; i++) {
            double lat0 = Math.PI * (-0.5 + (double) (i - 1) / lats);
            double z0 = Math.sin(lat0);
            double zr0 = Math.cos(lat0);

            double lat1 = Math.PI * (-0.5 + (double) i / lats);
            double z1 = Math.sin(lat1);
            double zr1 = Math.cos(lat1);

            glBegin(GL_QUAD_STRIP);
            for (int j = 0; j <= longs; j++) {
                double lng = 2 * Math.PI * (double) (j - 1) / longs;
                double x = Math.cos(lng);
                double y = Math.sin(lng);

                glNormal3d(x * zr0, y * zr0, z0);
                glVertex3d(r * x * zr0, r * y * zr0, r * z0);
                glNormal3d(x * zr1, y * zr1, z1);
                glVertex3d(r * x * zr1, r * y * zr1, r * z1);
            }
            glEnd();
        }
        glPopMatrix();
    }

    public float getSkyBrightness() {
        float brightness;

        if (timeOfDay > 0.2f && timeOfDay < 0.3f) {
            // Sunrise
            brightness = smoothstep(0.2f, 0.3f, timeOfDay);
        } else if (timeOfDay > 0.7f && timeOfDay < 0.8f) {
            // Sunset
            brightness = 1f - smoothstep(0.7f, 0.8f, timeOfDay);
        } else if (timeOfDay >= 0.3f && timeOfDay <= 0.7f) {
            // Full day
            brightness = 1f;
        } else {
            // Full night
            brightness = 0f;
        }

        return brightness;
    }

    private float smoothstep(float edge0, float edge1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3f - 2f * t);
    }
}
