package com.brandon.climatesync.service;

import com.brandon.climatesync.model.ClimateSnapshot;
import com.brandon.climatesync.model.Zone;
import com.brandon.climatesync.provider.WeatherProvider;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class PollService {

    private final Plugin plugin;
    private final ZoneRegistry zones;
    private final WeatherProvider provider;
    private final ClimateClassifier classifier;
    private final long pollIntervalSeconds;
    private final long minSecondsBetweenSameZoneCalls;

    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ClimateSync-Poller");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<String, ClimateSnapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<String, Long> lastCallEpoch = new ConcurrentHashMap<>();
    private ScheduledFuture<?> future;

    public PollService(Plugin plugin,
                       ZoneRegistry zones,
                       WeatherProvider provider,
                       ClimateClassifier classifier,
                       long pollIntervalSeconds,
                       long minSecondsBetweenSameZoneCalls) {
        this.plugin = plugin;
        this.zones = zones;
        this.provider = provider;
        this.classifier = classifier;
        this.pollIntervalSeconds = pollIntervalSeconds;
        this.minSecondsBetweenSameZoneCalls = minSecondsBetweenSameZoneCalls;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        future = exec.scheduleAtFixedRate(this::pollAll, 10, pollIntervalSeconds, TimeUnit.SECONDS);
    }

    public void stop() {
        running.set(false);
        if (future != null) future.cancel(false);
        exec.shutdownNow();
    }

    public boolean isRunning() { return running.get(); }
    public int snapshotCount() { return snapshots.size(); }
    public Optional<ClimateSnapshot> snapshot(String zoneId) { return Optional.ofNullable(snapshots.get(zoneId.toLowerCase())); }

    public void refreshAllNow() { exec.submit(this::pollAll); }
    public void refreshZoneNow(String zoneId) { zones.get(zoneId).ifPresent(z -> exec.submit(() -> pollOne(z, true))); }

    private void pollAll() {
        if (!running.get()) return;
        plugin.getLogger().info("ClimateSync poll started. Zones=" + zones.size());
        for (Zone z : zones.all()) pollOne(z, false);
        plugin.getLogger().info("ClimateSync poll finished. Snapshots=" + snapshots.size());
    }

    private void pollOne(Zone z, boolean force) {
        long now = Instant.now().getEpochSecond();
        long last = lastCallEpoch.getOrDefault(z.id(), 0L);

        if (!force && minSecondsBetweenSameZoneCalls > 0 && (now - last) < minSecondsBetweenSameZoneCalls) {
            return;
        }
        lastCallEpoch.put(z.id(), now);

        provider.fetch(z.lat(), z.lon())
                .thenAccept(obs -> snapshots.put(z.id(), new ClimateSnapshot(
                        z.id(),
                        classifier.classify(obs),
                        obs.thunder(),
                        obs.wet(),
                        obs.tempC(),
                        obs.weatherMain(),
                        obs.rainMm(),
                        obs.snowMm(),
                        obs.windowHours(),
                        Instant.now().getEpochSecond()
                )))
                .exceptionally(ex -> {
                    plugin.getLogger().warning("Fetch failed for zone " + z.id() + ": " + ex.getMessage());
                    return null;
                })
                .join();
    }
}
