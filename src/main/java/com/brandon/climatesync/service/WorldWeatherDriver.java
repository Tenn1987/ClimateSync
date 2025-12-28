package com.brandon.climatesync.service;

import com.brandon.climatesync.model.ClimateSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class WorldWeatherDriver {

    public enum Mode { WORLD_GLOBAL }

    private final Plugin plugin;
    private final PollService poll;

    private boolean enabled;
    private Mode mode;
    private List<String> targetWorlds;
    private Map<String, String> driverZonePerWorld;

    private long durationTicks;
    private boolean thunderInHotWet;
    private double thunderChance;

    private BukkitTask task;

    // optional: prevent spam updates every tick
    private final Map<String, Long> lastAppliedMs = new ConcurrentHashMap<>();
    private long minApplyMs = 30_000L;

    public WorldWeatherDriver(Plugin plugin, PollService poll) {
        this.plugin = plugin;
        this.poll = poll;
    }

    public void reloadFromConfig() {
        enabled = plugin.getConfig().getBoolean("weather.enabled", false);
        String m = plugin.getConfig().getString("weather.mode", "WORLD_GLOBAL");
        mode = "WORLD_GLOBAL".equalsIgnoreCase(m) ? Mode.WORLD_GLOBAL : Mode.WORLD_GLOBAL;

        targetWorlds = plugin.getConfig().getStringList("weather.targetWorlds");
        if (targetWorlds == null || targetWorlds.isEmpty()) targetWorlds = List.of("world");

        durationTicks = plugin.getConfig().getLong("weather.durationTicks", 216000L);

        thunderInHotWet = plugin.getConfig().getBoolean("weather.thunderInHotWet", true);
        thunderChance = plugin.getConfig().getDouble("weather.thunderChance", 0.35);
        if (thunderChance < 0) thunderChance = 0;
        if (thunderChance > 1) thunderChance = 1;

        driverZonePerWorld = new HashMap<>();
        if (plugin.getConfig().isConfigurationSection("weather.driverZonePerWorld")) {
            var sec = plugin.getConfig().getConfigurationSection("weather.driverZonePerWorld");
            for (String key : sec.getKeys(false)) {
                driverZonePerWorld.put(key, String.valueOf(sec.getString(key, "")).toLowerCase(Locale.ROOT));
            }
        }
    }

    public void start() {
        stop();
        reloadFromConfig();
        if (!enabled) {
            plugin.getLogger().info("WorldWeatherDriver disabled in config.");
            return;
        }

        // apply shortly after enable, then every minute (cheap)
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 1200L);
        plugin.getLogger().info("WorldWeatherDriver started. mode=" + mode + ", targetWorlds=" + targetWorlds.size());
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
    }

    private void tick() {
        if (!enabled) return;

        for (String worldName : targetWorlds) {
            World w = Bukkit.getWorld(worldName);
            if (w == null) continue;

            String zoneId = driverZonePerWorld.getOrDefault(worldName, "");
            if (zoneId.isEmpty()) continue;

            Optional<ClimateSnapshot> snapOpt = poll.snapshot(zoneId);
            if (snapOpt.isEmpty()) continue;

            applyWorldWeather(w, snapOpt.get());
        }
    }

    private void applyWorldWeather(World w, ClimateSnapshot s) {
        long now = System.currentTimeMillis();
        long last = lastAppliedMs.getOrDefault(w.getName(), 0L);
        if (now - last < minApplyMs) return;
        lastAppliedMs.put(w.getName(), now);

        boolean wet = s.wet();
        boolean thunder = s.thunder();

        // Optional: let HOT_WET roll thunder sometimes
        if (!thunder && thunderInHotWet && "HOT_WET".equalsIgnoreCase(String.valueOf(s.state()))) {
            if (Math.random() < thunderChance) thunder = true;
        }

        // Apply
        w.setStorm(wet);
        w.setThundering(wet && thunder);

        int dur = (int) Math.max(200L, Math.min(Integer.MAX_VALUE, durationTicks));
        w.setWeatherDuration(dur);
        w.setThunderDuration(dur);

        // If dry, ensure thunder off
        if (!wet) {
            w.setThundering(false);
        }
    }
}
