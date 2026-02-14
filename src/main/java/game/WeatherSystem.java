package game;

import static org.lwjgl.opengl.GL11.*;

public class WeatherSystem {
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

        float targetPrecip = weatherType == WeatherType.SUNNY ? 0f : 1f;
        precipitationStrength += (targetPrecip - precipitationStrength) * Math.min(1f, dt * 0.8f);

        if (weatherType == WeatherType.SNOWY && temperatureC <= 0f) {
            snowCoverage = Math.min(1f, snowCoverage + dt * 0.015f);
        } else if (temperatureC > 0f) {
            snowCoverage = Math.max(0f, snowCoverage - dt * 0.012f);
        }

        precipitationTime += dt;
    }

    public void renderPrecipitation(float camX, float camY, float camZ) {
        if (precipitationStrength <= 0.01f || weatherType == WeatherType.SUNNY) {
            return;
        }

        final int particles = 450;
        final float radius = 52f;
        final float top = camY + 30f;

        glDisable(GL_LIGHTING);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        if (weatherType == WeatherType.SNOWY) {
            glDisable(GL_TEXTURE_2D);
            glPointSize(2.2f);
            glColor4f(1f, 1f, 1f, 0.8f * precipitationStrength);
            glBegin(GL_POINTS);
            for (int i = 0; i < particles; i++) {
                float seed = i * 12.9898f;
                float x = camX + hash(seed) * radius;
                float z = camZ + hash(seed + 31.7f) * radius;
                float fallSpeed = 3.5f + hash(seed + 13.2f) * 2.0f;
                float y = top - ((precipitationTime * fallSpeed + hash(seed + 7.3f) * 30f) % 30f);
                glVertex3f(x, y, z);
            }
            glEnd();
        } else {
            glDisable(GL_TEXTURE_2D);
            glColor4f(0.72f, 0.82f, 0.95f, 0.7f * precipitationStrength);
            glBegin(GL_LINES);
            for (int i = 0; i < particles; i++) {
                float seed = i * 7.1352f;
                float x = camX + hash(seed) * radius;
                float z = camZ + hash(seed + 19.1f) * radius;
                float fallSpeed = 18f + hash(seed + 4.6f) * 8f;
                float y = top - ((precipitationTime * fallSpeed + hash(seed + 2.4f) * 30f) % 30f);
                glVertex3f(x, y, z);
                glVertex3f(x, y - 1.3f, z);
            }
            glEnd();
        }

        glDisable(GL_BLEND);
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
