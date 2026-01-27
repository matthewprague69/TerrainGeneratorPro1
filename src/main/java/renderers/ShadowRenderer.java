package renderers;

import game.TerrainManager;
import org.lwjgl.BufferUtils;
import util.MatrixUtils;
import util.ShaderProgram;

import java.io.IOException;
import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL30.*;

public class ShadowRenderer {
    private static ShadowRenderer active;

    private final int shadowSize;
    private final int depthFbo;
    private final int depthTexture;
    private final ShaderProgram depthShader;
    private final ShaderProgram sceneShader;
    private final int sceneLightMatrixLoc;
    private final int sceneShadowMapLoc;
    private final int sceneDiffuseLoc;
    private final int sceneUseTextureLoc;
    private final int sceneLightDirLoc;
    private final int sceneLightStrengthLoc;
    private final int sceneViewMatrixLoc;
    private final int sceneViewInverseLoc;
    private float[] lastLightMatrix = MatrixUtils.identity();

    public ShadowRenderer(int shadowSize) {
        this.shadowSize = shadowSize;
        depthTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, depthTexture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, shadowSize, shadowSize, 0,
                GL_DEPTH_COMPONENT, GL_FLOAT, 0);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_2D, 0);

        depthFbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, depthFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depthTexture, 0);
        glDrawBuffer(GL_NONE);
        glReadBuffer(GL_NONE);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Shadow framebuffer incomplete");
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        depthShader = new ShaderProgram(loadShader("shaders/shadow_depth.vert"),
                loadShader("shaders/shadow_depth.frag"));
        sceneShader = new ShaderProgram(loadShader("shaders/shadow_main.vert"),
                loadShader("shaders/shadow_main.frag"));
        sceneLightMatrixLoc = sceneShader.getUniformLocation("uLightMatrix");
        sceneShadowMapLoc = sceneShader.getUniformLocation("uShadowMap");
        sceneDiffuseLoc = sceneShader.getUniformLocation("uDiffuse");
        sceneUseTextureLoc = sceneShader.getUniformLocation("uUseTexture");
        sceneLightDirLoc = sceneShader.getUniformLocation("uLightDir");
        sceneLightStrengthLoc = sceneShader.getUniformLocation("uLightStrength");
        sceneViewMatrixLoc = sceneShader.getUniformLocation("uViewMatrix");
        sceneViewInverseLoc = sceneShader.getUniformLocation("uViewInverse");
    }

    public float[] renderShadowMap(TerrainManager terrain, float[] lightDir, float centerX, float centerY, float centerZ,
                                   int screenWidth, int screenHeight) {
        lastLightMatrix = buildLightMatrix(lightDir, centerX, centerY, centerZ);

        glViewport(0, 0, shadowSize, shadowSize);
        glBindFramebuffer(GL_FRAMEBUFFER, depthFbo);
        glEnable(GL_DEPTH_TEST);
        glClearDepth(1.0f);
        glClear(GL_DEPTH_BUFFER_BIT);

        glMatrixMode(GL_PROJECTION);
        glLoadMatrixf(toBuffer(buildLightProjectionMatrix()));
        glMatrixMode(GL_MODELVIEW);
        glLoadMatrixf(toBuffer(buildLightViewMatrix(lightDir, centerX, centerY, centerZ)));

        depthShader.use();
        glColorMask(false, false, false, false);
        glDisable(GL_BLEND);
        glDisable(GL_TEXTURE_2D);
        glDisable(GL_LIGHTING);
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(1.5f, 4.0f);

        terrain.drawDepth(centerX, centerZ);

        glDisable(GL_POLYGON_OFFSET_FILL);
        glColorMask(true, true, true, true);
        depthShader.stop();
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, screenWidth, screenHeight);

        return lastLightMatrix;
    }

    public void beginScenePass(float[] lightMatrix, float[] lightDir, float lightStrength,
                               float[] viewMatrix, float[] viewInverse) {
        active = this;
        sceneShader.use();
        sceneShader.setUniformMatrix4(sceneLightMatrixLoc, toBuffer(lightMatrix));
        sceneShader.setUniformMatrix4(sceneViewMatrixLoc, toBuffer(viewMatrix));
        sceneShader.setUniformMatrix4(sceneViewInverseLoc, toBuffer(viewInverse));
        sceneShader.setUniform3f(sceneLightDirLoc, lightDir[0], lightDir[1], lightDir[2]);
        sceneShader.setUniform1f(sceneLightStrengthLoc, lightStrength);
        sceneShader.setUniform1i(sceneShadowMapLoc, 1);
        sceneShader.setUniform1i(sceneDiffuseLoc, 0);
        sceneShader.setUniform1i(sceneUseTextureLoc, 1);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, depthTexture);
        glActiveTexture(GL_TEXTURE0);
        glDisable(GL_LIGHTING);
    }

    public void endScenePass() {
        sceneShader.stop();
        glBindTexture(GL_TEXTURE_2D, 0);
        active = null;
    }

    public static void setUseTexture(boolean useTexture) {
        if (active == null) {
            return;
        }
        active.sceneShader.setUniform1i(active.sceneUseTextureLoc, useTexture ? 1 : 0);
    }

    private float[] buildLightMatrix(float[] lightDir, float centerX, float centerY, float centerZ) {
        float[] view = buildLightViewMatrix(lightDir, centerX, centerY, centerZ);
        float[] proj = buildLightProjectionMatrix();
        return MatrixUtils.multiply(proj, view);
    }

    private float[] buildLightProjectionMatrix() {
        return MatrixUtils.ortho(-180f, 180f, -180f, 180f, 0.1f, 340f);
    }

    private float[] buildLightViewMatrix(float[] lightDir, float centerX, float centerY, float centerZ) {
        float distance = 200f;
        float eyeX = centerX - lightDir[0] * distance;
        float eyeY = centerY - lightDir[1] * distance + 80f;
        float eyeZ = centerZ - lightDir[2] * distance;
        return MatrixUtils.lookAt(eyeX, eyeY, eyeZ, centerX, centerY, centerZ, 0f, 1f, 0f);
    }

    private static String loadShader(String path) {
        try (var stream = ShadowRenderer.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("Missing shader: " + path);
            }
            return new String(stream.readAllBytes());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read shader: " + path, e);
        }
    }

    private static FloatBuffer toBuffer(float[] matrix) {
        FloatBuffer buffer = BufferUtils.createFloatBuffer(16);
        buffer.put(matrix).flip();
        return buffer;
    }
}
