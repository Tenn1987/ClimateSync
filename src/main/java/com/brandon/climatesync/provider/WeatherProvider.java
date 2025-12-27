package com.brandon.climatesync.provider;

import java.util.concurrent.CompletableFuture;

public interface WeatherProvider {

    /**
     * rainMm / snowMm are the reported precipitation in millimeters over the windowHours period.
     * windowHours will typically be 3 or 1 (OpenWeather provides rain.3h / rain.1h, snow.3h / snow.1h).
     */
    record Observed(
            double tempC,
            String weatherMain,
            boolean thunder,
            boolean wet,
            double rainMm,
            double snowMm,
            int windowHours
    ) {}

    CompletableFuture<Observed> fetch(double lat, double lon);
}
