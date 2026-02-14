package game;

import static org.lwjgl.opengl.GL11.*;

public class WeatherSystem {
    private static final int SNOW_PARTICLES_NEAR = 900;
    private static final int SNOW_PARTICLES_MID = 1400;
    private static final int SNOW_PARTICLES_FAR = 1700;
    private static final int RAIN_PARTICLES = 1500;

    private WeatherType weatherType = WeatherType.SUNNY;
    private float temperatureC = 8f;
    private float snowCoverage = 0f;
    private float precipitationStrength = 0f;
    private float precipitationTime = 0f;

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
            snowCoverage = Math.min(1f, snowCoverage + dt * 0.018f);
        } else if (temperatureC > 0f) {
            snowCoverage = Math.max(0f, snowCoverage - dt * 0.010f);
        }

        precipitationTime += dt;
    }

    public void renderPrecipitation(float camX, float camY, float camZ) {
        if (precipitationStrength <= 0.01f || weatherType == WeatherType.SUNNY) {
            return;
        }

        glDisable(GL_LIGHTING);
        glDisable(GL_TEXTURE_2D);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        if (weatherType == WeatherType.SNOWY) {
            renderSnowParticles(camX, camY, camZ);
        } else {
            renderRainParticles(camX, camY, camZ);
        }

        glDisable(GL_BLEND);
    }

    private void renderSnowParticles(float camX, float camY, float camZ) {
        renderSnowPass(camX, camY, camZ, SNOW_PARTICLES_FAR, 85f, 3.2f, 0.9f, 0.30f);
        renderSnowPass(camX, camY, camZ, SNOW_PARTICLES_MID, 62f, 4.2f, 1.7f, 0.48f);
        renderSnowPass(camX, camY, camZ, SNOW_PARTICLES_NEAR, 42f, 5.1f, 2.7f, 0.72f);
    }

    private void renderSnowPass(float camX, float camY, float camZ, int count, float radius,
                                float baseSpeed, float pointSize, float alpha) {
        final float top = camY + 34f;
        glPointSize(pointSize);
        glColor4f(1f, 1f, 1f, alpha * precipitationStrength);
        glBegin(GL_POINTS);
        for (int i = 0; i < count; i++) {
            float seed = i * 13.37f + radius * 0.123f;
            float x = camX + hash(seed) * radius;
            float z = camZ + hash(seed + 31.7f) * radius;
            float yCycle = 34f + hash(seed + 6.3f) * 8f;
            float speed = baseSpeed + hash(seed + 13.2f) * 2.4f;
            float sway = hash(seed + precipitationTime * 0.33f) * 0.16f;
            float y = top - ((precipitationTime * speed + hash(seed + 7.3f) * yCycle) % yCycle);
            glVertex3f(x + sway, y, z - sway * 0.6f);
        }
        glEnd();
    }

    private void renderRainParticles(float camX, float camY, float camZ) {
        final float radius = 62f;
        final float top = camY + 34f;
        glColor4f(0.72f, 0.82f, 0.95f, 0.7f * precipitationStrength);
        glBegin(GL_LINES);
        for (int i = 0; i < RAIN_PARTICLES; i++) {
            float seed = i * 7.1352f;
            float x = camX + hash(seed) * radius;
            float z = camZ + hash(seed + 19.1f) * radius;
            float fallSpeed = 18f + hash(seed + 4.6f) * 8f;
            float y = top - ((precipitationTime * fallSpeed + hash(seed + 2.4f) * 36f) % 36f);
            glVertex3f(x, y, z);
            glVertex3f(x + 0.07f, y - 1.55f, z + 0.07f);
        }
        glEnd();
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

    private float hash(float v) {
        float s = (float) Math.sin(v * 12.9898f + 78.233f) * 43758.5453f;
        return (s - (float) Math.floor(s)) * 2f - 1f;
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

    public boolean isWaterFrozen() {
        return temperatureC <= -0.5f || (snowCoverage > 0.3f && temperatureC < 1.5f);
    }
}
