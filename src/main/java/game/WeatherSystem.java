package game;

import static org.lwjgl.opengl.GL11.*;

public class WeatherSystem {
    private static final float PRECIPITATION_DENSE_RADIUS = 9f;
    private static final float PRECIPITATION_MID_RADIUS = 18f;
    private static final float PRECIPITATION_FAR_RADIUS = 30f;
    private static final int SNOW_PARTICLES_NEAR = 900;
    private static final int SNOW_PARTICLES_MID = 1000;
    private static final int SNOW_PARTICLES_FAR = 800;
    private static final int RAIN_PARTICLES = 1200;
    private static final int MAX_SNOW_PARTICLES = SNOW_PARTICLES_NEAR + SNOW_PARTICLES_MID + SNOW_PARTICLES_FAR;
    private static final ParticleTemplate[] SNOW_PARTICLE_TEMPLATES = buildTemplates(MAX_SNOW_PARTICLES, 0x51A9E31D);
    private static final ParticleTemplate[] RAIN_PARTICLE_TEMPLATES = buildTemplates(RAIN_PARTICLES, 0x39C2B47F);

    private static final class ParticleTemplate {
        private final float xNorm;
        private final float zNorm;
        private final float phase;
        private final float speed;
        private final float sway;

        private ParticleTemplate(float xNorm, float zNorm, float phase, float speed, float sway) {
            this.xNorm = xNorm;
            this.zNorm = zNorm;
            this.phase = phase;
            this.speed = speed;
            this.sway = sway;
        }
    }

    private WeatherType weatherType = WeatherType.SUNNY;
    private float temperatureC = 8f;
    private float snowCoverage = 0f;
    private float waterSnowCoverage = 0f;
    private float precipitationStrength = 0f;
    private float precipitationTime = 0f;
    private float iceThickness = 0f;

    public void update(float dt, float timeOfDay) {
        float targetTemp = getBaseTemperatureForWeather(weatherType);
        if (timeOfDay < 0.23f || timeOfDay > 0.78f) {
            targetTemp -= 4f;
        }
        float tempBlend = Math.min(1f, dt * 0.25f);
        temperatureC += (targetTemp - temperatureC) * tempBlend;

        if (weatherType == WeatherType.SNOWY && temperatureC > -0.25f) {
            temperatureC = Math.max(-8f, temperatureC - dt * 12f);
        }

        float targetPrecip = weatherType == WeatherType.SUNNY ? 0f : 1f;
        precipitationStrength += (targetPrecip - precipitationStrength) * Math.min(1f, dt * 0.8f);

        if (weatherType == WeatherType.SNOWY && temperatureC <= 0f) {
            snowCoverage = Math.min(1f, snowCoverage + dt * 0.045f);
            waterSnowCoverage = Math.min(1f, waterSnowCoverage + dt * 0.055f);
        } else if (temperatureC > 0f) {
            // Land snow melts first.
            snowCoverage = Math.max(0f, snowCoverage - dt * 0.020f);
            // Snow on ice melts gradually, revealing ice before the ice itself disappears.
            waterSnowCoverage = Math.max(0f, waterSnowCoverage - dt * 0.030f);
        }

        if (isWaterFrozen()) {
            float growth = 0.010f + snowCoverage * 0.030f;
            iceThickness = Math.min(0.85f, iceThickness + dt * growth);
        } else {
            // Ice melts slower and lasts longer than snow.
            iceThickness = Math.max(0f, iceThickness - dt * 0.008f);
        }

        precipitationTime += dt;
    }

    public void renderPrecipitation(float camX, float camY, float camZ, float surfaceY) {
        if (precipitationStrength <= 0.01f || weatherType == WeatherType.SUNNY) {
            return;
        }

        glDisable(GL_LIGHTING);
        glDisable(GL_TEXTURE_2D);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        if (weatherType == WeatherType.SNOWY) {
            renderSnowParticles(camX, camY, camZ, surfaceY);
        } else {
            renderRainParticles(camX, camY, camZ);
        }

        glDisable(GL_BLEND);
    }

    private void renderSnowParticles(float camX, float camY, float camZ, float surfaceY) {
        float snappedGroundY = (float) Math.floor(surfaceY * 0.5f) * 2f;
        float desiredTop = Math.max(snappedGroundY + 32f, camY + 14f);
        float topY = (float) Math.ceil(desiredTop / 4f) * 4f;
        float snowSpan = Math.max(32f, Math.min(96f, topY - snappedGroundY));

        int start = 0;
        start = renderSnowPass(camX, camY, camZ, start, SNOW_PARTICLES_FAR,
                PRECIPITATION_FAR_RADIUS, 2.8f, 2.8f, 0.20f, topY, snowSpan);
        start = renderSnowPass(camX, camY, camZ, start, SNOW_PARTICLES_MID,
                PRECIPITATION_MID_RADIUS, 3.8f, 5.5f, 0.38f, topY, snowSpan);
        renderSnowPass(camX, camY, camZ, start, SNOW_PARTICLES_NEAR,
                PRECIPITATION_DENSE_RADIUS, 4.8f, 10.0f, 0.66f, topY, snowSpan);
    }

    private int renderSnowPass(float camX, float camY, float camZ, int startIndex, int count,
                               float radius, float baseSpeed, float pointSize, float alpha,
                               float topOffset, float verticalSpan) {
        final float top = topOffset;
        final float effectiveRadius = radius * (0.9f + precipitationStrength * 0.1f);
        final float snappedCenterX = (float) Math.floor(camX / 8f) * 8f;
        final float snappedCenterZ = (float) Math.floor(camZ / 8f) * 8f;
        glPointSize(pointSize);
        glColor4f(1f, 1f, 1f, alpha * precipitationStrength);
        glBegin(GL_POINTS);
        int end = Math.min(startIndex + count, SNOW_PARTICLE_TEMPLATES.length);
        float time = precipitationTime;
        for (int i = startIndex; i < end; i++) {
            ParticleTemplate p = SNOW_PARTICLE_TEMPLATES[i];
            float speed = baseSpeed + p.speed * 2.2f;
            float y = top - ((time * speed + p.phase * verticalSpan) % verticalSpan);
            float drift = (float) Math.sin((time + p.phase) * 0.8f) * p.sway;
            float x = snappedCenterX + p.xNorm * effectiveRadius + drift;
            float z = snappedCenterZ + p.zNorm * effectiveRadius - drift * 0.5f;
            glVertex3f(x, y, z);
        }
        glEnd();
        return end;
    }

    private void renderRainParticles(float camX, float camY, float camZ) {
        final float radius = PRECIPITATION_MID_RADIUS;
        final float top = (float) Math.ceil((camY + 14f) / 4f) * 4f;
        final float dropHeight = 30f;
        final float snappedCenterX = (float) Math.floor(camX / 8f) * 8f;
        final float snappedCenterZ = (float) Math.floor(camZ / 8f) * 8f;
        glColor4f(0.72f, 0.82f, 0.95f, 0.7f * precipitationStrength);
        glBegin(GL_LINES);
        for (int i = 0; i < RAIN_PARTICLES; i++) {
            ParticleTemplate p = RAIN_PARTICLE_TEMPLATES[i];
            float x = snappedCenterX + p.xNorm * radius;
            float z = snappedCenterZ + p.zNorm * radius;
            float fallSpeed = 16f + p.speed * 8f;
            float y = top - ((precipitationTime * fallSpeed + p.phase * dropHeight) % dropHeight);
            glVertex3f(x, y, z);
            glVertex3f(x + 0.07f, y - 1.55f, z + 0.07f);
        }
        glEnd();
    }

    private static ParticleTemplate[] buildTemplates(int count, int seed) {
        ParticleTemplate[] templates = new ParticleTemplate[count];
        int state = seed;
        for (int i = 0; i < count; i++) {
            state = lcg(state);
            float angle = ((state >>> 8) & 0xFFFF) / 65535f * ((float) Math.PI * 2f);
            state = lcg(state);
            float radius = (float) Math.sqrt(((state >>> 8) & 0xFFFF) / 65535f);
            state = lcg(state);
            float phase = ((state >>> 8) & 0xFFFF) / 65535f;
            state = lcg(state);
            float speed = ((state >>> 8) & 0xFFFF) / 65535f;
            state = lcg(state);
            float sway = 0.05f + (((state >>> 8) & 0xFFFF) / 65535f) * 0.24f;

            float xNorm = (float) Math.cos(angle) * radius;
            float zNorm = (float) Math.sin(angle) * radius;
            templates[i] = new ParticleTemplate(xNorm, zNorm, phase, speed, sway);
        }
        return templates;
    }

    private static int lcg(int state) {
        return state * 1664525 + 1013904223;
    }

    private float getBaseTemperatureForWeather(WeatherType type) {
        switch (type) {
            case SNOWY:
                return -6f;
            case RAINY:
                return 4f;
            default:
                return 14f;
        }
    }

    public WeatherType getWeatherType() {
        return weatherType;
    }

    public void setWeatherType(WeatherType weatherType) {
        this.weatherType = weatherType == null ? WeatherType.SUNNY : weatherType;

        if (this.weatherType == WeatherType.SNOWY) {
            temperatureC = Math.min(temperatureC, -2f);
        } else {
            temperatureC = Math.max(temperatureC, 2f);
        }
    }

    public float getTemperatureC() {
        return temperatureC;
    }

    public float getSnowCoverage() {
        return snowCoverage;
    }

    public float getWaterSnowCoverage() {
        return waterSnowCoverage;
    }

    public boolean isWaterFrozen() {
        return iceThickness > 0.01f || temperatureC <= -0.5f || (snowCoverage > 0.3f && temperatureC < 1.5f);
    }

    public float getIceThickness() {
        return iceThickness;
    }
}
