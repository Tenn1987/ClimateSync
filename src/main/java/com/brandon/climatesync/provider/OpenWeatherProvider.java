package com.brandon.climatesync.provider;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public class OpenWeatherProvider implements WeatherProvider {

    private final Logger log;
    private final String baseUrl;
    private final String apiKey;
    private final String units;
    private final HttpClient client;

    public OpenWeatherProvider(Logger log, String baseUrl, String apiKey, String units) {
        this.log = log;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.units = (units == null || units.isBlank()) ? "metric" : units;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public CompletableFuture<Observed> fetch(double lat, double lon) {
        if (apiKey == null || apiKey.isBlank() || apiKey.equals("PUT_YOUR_KEY_HERE")) {
            return CompletableFuture.failedFuture(new IllegalStateException("OpenWeather apiKey is not set in config.yml"));
        }

        String url = baseUrl
                + "?lat=" + String.format(Locale.US, "%.6f", lat)
                + "&lon=" + String.format(Locale.US, "%.6f", lon)
                + "&appid=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
                + "&units=" + URLEncoder.encode(units, StandardCharsets.UTF_8);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .header("Accept", "application/json")
                .build();

        return client.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) {
                        throw new RuntimeException("OpenWeather HTTP " + resp.statusCode() + ": " + resp.body());
                    }

                    JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();

                    double tempC = root.getAsJsonObject("main").get("temp").getAsDouble();

                    String weatherMain = "Unknown";
                    if (root.has("weather") && root.get("weather").isJsonArray() && root.getAsJsonArray("weather").size() > 0) {
                        JsonObject w0 = root.getAsJsonArray("weather").get(0).getAsJsonObject();
                        if (w0.has("main")) weatherMain = w0.get("main").getAsString();
                    }

                    // Pull rain/snow (OpenWeather uses mm over 1h or 3h windows)
                    PrecipWindow rain = readPrecip(root, "rain");
                    PrecipWindow snow = readPrecip(root, "snow");

                    // Choose best window: prefer 3h if any precip is reported there; otherwise 1h; otherwise 0
                    int windowHours = 0;
                    if (rain.windowHours == 3 || snow.windowHours == 3) windowHours = 3;
                    else if (rain.windowHours == 1 || snow.windowHours == 1) windowHours = 1;

                    double rainMm = rain.mm;
                    double snowMm = snow.mm;

                    String wm = weatherMain.toLowerCase(Locale.ROOT);
                    boolean thunder = wm.contains("thunder");

                    // Wet is now driven primarily by precipitation volume, with a fallback to "rain/snow/drizzle/thunder" main types.
                    boolean wet = thunder
                            || (rainMm > 0.0)
                            || (snowMm > 0.0)
                            || wm.contains("rain")
                            || wm.contains("drizzle")
                            || wm.contains("snow");

                    return new Observed(tempC, weatherMain, thunder, wet, rainMm, snowMm, windowHours);
                });
    }

    private static class PrecipWindow {
        final double mm;
        final int windowHours;
        PrecipWindow(double mm, int windowHours) {
            this.mm = mm;
            this.windowHours = windowHours;
        }
    }

    /**
     * Reads OpenWeather precip object: { "1h": <mm> } or { "3h": <mm> }.
     * Returns (0,0) if missing.
     */
    private static PrecipWindow readPrecip(JsonObject root, String key) {
        if (!root.has(key) || !root.get(key).isJsonObject()) {
            return new PrecipWindow(0.0, 0);
        }

        JsonObject obj = root.getAsJsonObject(key);
        // Prefer 3h if present
        if (obj.has("3h")) {
            return new PrecipWindow(safeDouble(obj.get("3h")), 3);
        }
        if (obj.has("1h")) {
            return new PrecipWindow(safeDouble(obj.get("1h")), 1);
        }
        return new PrecipWindow(0.0, 0);
    }

    private static double safeDouble(JsonElement e) {
        try {
            return e == null ? 0.0 : e.getAsDouble();
        } catch (Exception ignored) {
            return 0.0;
        }
    }
}
