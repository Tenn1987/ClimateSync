package com.brandon.climatesync.service;

import com.brandon.climatesync.model.ClimateSnapshot;
import com.brandon.climatesync.model.ClimateState;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class WeatherApplierService {

    public enum Mode { WORLD_GLOBAL }

    private final Plugin plugin;
    private final PollService pollService;

    private boolean enabled = false;
    private Mode mode = Mode.WORLD_GLOBAL;

    private List<String> targetWorlds = List.of("world");
    private Map<String, String> driverZonePerWorld = new HashMap<>();

    private long durationTicks = 216000L;
    private boolean thunderInHotWet = true;
    private double thunderChance = 0.35;

    private BukkitTask task;

    // anti-spam: don’t re-apply too often
    private final Map<String, Long> lastAppliedMs = new ConcurrentHashMap<>();
    private long minApplyMs = 30_000L;

    public WeatherApplierService(Plugin plugin, PollService pollService) {
        this.plugin = plugin;
        this.pollService = pollService;
    }

    public void reloadFromConfig() {
        enabled = plugin.getConfig().getBoolean("weather.enabled", false);

        String m = plugin.getConfig().getString("weather.mode", "WORLD_GLOBAL");
        mode = "WORLD_GLOBAL".equalsIgnoreCase(m) ? Mode.WORLD_GLOBAL : Mode.WORLD_GLOBAL;

        List<String> worlds = plugin.getConfig().getStringList("weather.targetWorlds");
        targetWorlds = (worlds == null || worlds.isEmpty()) ? List.of("world") : worlds;

        durationTicks = plugin.getConfig().getLong("weather.durationTicks", 216000L);

        thunderInHotWet = plugin.getConfig().getBoolean("weather.thunderInHotWet", true);
        thunderChance = plugin.getConfig().getDouble("weather.thunderChance", 0.35);
        thunderChance = Math.max(0.0, Math.min(1.0, thunderChance));

        driverZonePerWorld = new HashMap<>();
        if (plugin.getConfig().isConfigurationSection("weather.driverZonePerWorld")) {
            var sec = plugin.getConfig().getConfigurationSection("weather.driverZonePerWorld");
            for (String key : sec.getKeys(false)) {
                String zid = String.valueOf(sec.getString(key, "")).trim().toLowerCase(Locale.ROOT);
                if (!zid.isEmpty()) driverZonePerWorld.put(key, zid);
            }
        }
    }

    public void start() {
        stop();
        reloadFromConfig();

        if (!enabled) {
            plugin.getLogger().info("WeatherApplierService disabled in config.");
            return;
        }

        // Apply shortly after start, then every minute (cheap + stable)
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 1200L);
        plugin.getLogger().info("WeatherApplierService started. mode=" + mode + ", worlds=" + targetWorlds.size());
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

            Optional<ClimateSnapshot> snapOpt = pollService.snapshot(zoneId);
            if (snapOpt.isEmpty()) continue;

            applyWorldWeather(w, snapOpt.get(), zoneId);
        }
    }

    private void applyWorldWeather(World w, ClimateSnapshot s, String zoneId) {
        long now = System.currentTimeMillis();
        long last = lastAppliedMs.getOrDefault(w.getName(), 0L);
        if (now - last < minApplyMs) return;
        lastAppliedMs.put(w.getName(), now);

        boolean wet = s.wet();
        boolean thunder = s.thunder();

        // Optional: HOT_WET can sometimes thunder even if provider didn’t say thunder
        ClimateState state = s.state();
        if (!thunder && thunderInHotWet && state == ClimateState.HOT_WET) {
            if (Math.random() < thunderChance) thunder = true;
        }

        // Apply weather
        w.setStorm(wet);
        w.setThundering(wet && thunder);

        int dur = (int) Math.max(200L, Math.min(Integer.MAX_VALUE, durationTicks));
        w.setWeatherDuration(dur);
        w.setThunderDuration(dur);

        if (!wet) w.setThundering(false);

        // Optional log for sanity (debug only if you want)
        if (plugin.getConfig().getBoolean("settings.debug.logWeatherApplies", false)) {
            plugin.getLogger().info("[WeatherApply] world=" + w.getName()
                    + " zone=" + zoneId
                    + " state=" + state
                    + " wet=" + wet
                    + " thunder=" + thunder
                    + " obs=" + s.observedMain()
                    + " updated=" + Instant.ofEpochSecond(s.updatedEpochSeconds()));
        }
    }
}
