package com.brandon.climatesync;

import com.brandon.climatesync.command.ClimateSyncCommand;
import com.brandon.climatesync.provider.OpenWeatherProvider;
import com.brandon.climatesync.provider.WeatherProvider;
import com.brandon.climatesync.service.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public class ClimateSyncPlugin extends JavaPlugin {

    private ZoneRegistry zoneRegistry;
    private PollService pollService;
    private WorldWeatherDriver weatherDriver;
    private SnowAccumulationService snowService;


    @Override
    public void onEnable() {
        saveDefaultConfig();

        zoneRegistry = new ZoneRegistry(this);
        zoneRegistry.reload();

        String baseUrl = getConfig().getString("settings.openWeather.baseUrl", "https://api.openweathermap.org/data/2.5/weather");
        String apiKey = getConfig().getString("settings.openWeather.apiKey", "").trim();
        String units = getConfig().getString("settings.openWeather.units", "metric");

        WeatherProvider provider = new OpenWeatherProvider(getLogger(), baseUrl, apiKey, units);

        double coldMax = getConfig().getDouble("settings.thresholds.coldMaxC", 0.0);
        double hotMin = getConfig().getDouble("settings.thresholds.hotMinC", 30.0);
        ClimateClassifier classifier = new ClimateClassifier(coldMax, hotMin);

        long pollIntervalSeconds = Math.max(300L, getConfig().getLong("settings.openWeather.pollIntervalSeconds", 10800L));
        long minBetweenSameZone = Math.max(0L, getConfig().getLong("settings.openWeather.minSecondsBetweenSameZoneCalls", 3600L));

        pollService = new PollService(this, zoneRegistry, provider, classifier, pollIntervalSeconds, minBetweenSameZone);
        pollService.start();

        snowService = new SnowAccumulationService(this, pollService);
        snowService.start();


        ClimateSyncCommand cmd = new ClimateSyncCommand(
                zoneRegistry,
                pollService,
                () -> getConfig().getBoolean("settings.debug.showPrecipInZoneCommand", false)
        );

        Objects.requireNonNull(getCommand("climatesync")).setExecutor(cmd);
        Objects.requireNonNull(getCommand("climatesync")).setTabCompleter(cmd);

        // Weather driver (world-global)
        weatherDriver = new WorldWeatherDriver(this, pollService);
        weatherDriver.start();

        getLogger().info("ClimateSync enabled. Zones loaded: " + zoneRegistry.size());
        getLogger().info("SAFETY: This plugin does NOT modify entities/biomes/datapacks by default. Weather driver toggles only.");
    }

    @Override
    public void onDisable() {
        if (weatherDriver != null) weatherDriver.stop();
        if (pollService != null) pollService.stop();
        if (snowService != null) snowService.stop();
    }
}
