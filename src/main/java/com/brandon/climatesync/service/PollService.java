package com.brandon.climatesync.service;

import com.brandon.climatesync.model.ClimateSnapshot;
import com.brandon.climatesync.model.ClimateState;
import com.brandon.climatesync.model.Zone;
import com.brandon.climatesync.provider.WeatherProvider;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PollService {

    private final Plugin plugin;
    private final ZoneRegistry zoneRegistry;
    private final WeatherProvider provider;
    private final ClimateClassifier classifier;
    private final long pollIntervalSeconds;
    private final long minSecondsBetweenSameZoneCalls;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private int taskId = -1;

    private final Map<String, ClimateSnapshot> snapshotsByZoneId = new ConcurrentHashMap<>();
    private final Map<String, Long> lastCallEpochSec = new ConcurrentHashMap<>();

    public PollService(
            Plugin plugin,
            ZoneRegistry zoneRegistry,
            WeatherProvider provider,
            ClimateClassifier classifier,
            long pollIntervalSeconds,
            long minSecondsBetweenSameZoneCalls
    ) {
        this.plugin = plugin;
        this.zoneRegistry = zoneRegistry;
        this.provider = provider;
        this.classifier = classifier;
        this.pollIntervalSeconds = pollIntervalSeconds;
        this.minSecondsBetweenSameZoneCalls = minSecondsBetweenSameZoneCalls;
    }

    public void start() {
        if (running.getAndSet(true)) return;

        long ticks = Math.max(20L, pollIntervalSeconds * 20L);
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::pollAllZones, 40L, ticks);
        plugin.getLogger().info("ClimateSync poll started. Zones=" + zoneRegistry.size());
    }

    public void stop() {
        running.set(false);
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public int snapshotsCount() {
        return snapshotsByZoneId.size();
    }

    public Optional<ClimateSnapshot> snapshot(String zoneId) {
        return Optional.ofNullable(snapshotsByZoneId.get(zoneId));
    }

    public void refreshAllNow() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::pollAllZones);
    }

    public void refreshZoneNow(String zoneId) {
        zoneRegistry.get(zoneId).ifPresent(z ->
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> pollOneZone(z, true))
        );
    }

    public Optional<ClimateSnapshot> getSnapshotForPlayer(Player p) {
        // WorldMapping phase uses anchors; but if you don't have mapping wired into PollService yet,
        // default to your configured driver zone (WORLD_GLOBAL behavior).
        // For now: use the zone from weather.driverZonePerWorld if present; else nearest by registry order.
        String worldName = p.getWorld().getName();
        String driverZone = plugin.getConfig().getString("weather.driverZonePerWorld." + worldName, null);
        if (driverZone != null) {
            return snapshot(driverZone);
        }

        // fallback: first zone in registry
        for (String id : zoneRegistry.ids()) {
            return snapshot(id);
        }
        return Optional.empty();
    }

    public Optional<Zone> getZoneForLocation(Location loc) {
        // If you later add real “worldMapping anchors -> nearest zoneId”, wire it here.
        // For now: same as above (driverZone per world fallback).
        String worldName = loc.getWorld() != null ? loc.getWorld().getName() : "world";
        String driverZone = plugin.getConfig().getString("weather.driverZonePerWorld." + worldName, null);
        if (driverZone != null) return zoneRegistry.get(driverZone);

        for (String id : zoneRegistry.ids()) {
            return zoneRegistry.get(id);
        }
        return Optional.empty();
    }

    private void pollAllZones() {
        if (!running.get()) return;

        for (String id : zoneRegistry.ids()) {
            zoneRegistry.get(id).ifPresent(z -> pollOneZone(z, false));
        }
    }

    private void pollOneZone(Zone z, boolean force) {
        long now = System.currentTimeMillis() / 1000L;

        if (!force) {
            Long last = lastCallEpochSec.get(z.id());
            if (last != null && (now - last) < minSecondsBetweenSameZoneCalls) {
                return;
            }
        }

        lastCallEpochSec.put(z.id(), now);

        try {
            // WeatherProvider.fetch(...) can be sync or async depending on your interface.
            // We handle BOTH patterns below safely.

            Object result = provider.fetch(z.lat(), z.lon());

            WeatherProvider.Observed obs;
            if (result instanceof WeatherProvider.Observed o) {
                obs = o;
            } else if (result instanceof java.util.concurrent.CompletableFuture<?> cf) {
                Object joined = ((java.util.concurrent.CompletableFuture<?>) cf).join();
                obs = (WeatherProvider.Observed) joined;
            } else {
                plugin.getLogger().warning("Unknown provider.fetch return type: " + result);
                return;
            }

            double tempC = obs.tempC();
            boolean wet = obs.wet();
            boolean thunder = obs.thunder();

            ClimateState state = classifier.classify(tempC, wet, thunder);

            ClimateSnapshot snap = new ClimateSnapshot(
                    z.id(),
                    state,
                    tempC,
                    wet,
                    thunder,
                    obs.weatherMain(), // <-- IMPORTANT: not observedMain()
                    now,
                    obs.windowHours(),
                    obs.rainMm(),
                    obs.snowMm()
            );

            snapshotsByZoneId.put(z.id(), snap);

        } catch (Exception e) {
            plugin.getLogger().warning("Poll failed for zone " + z.id() + ": " + e.getMessage());
        }
    }
}
