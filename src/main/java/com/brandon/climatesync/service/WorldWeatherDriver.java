package com.brandon.climatesync.service;

import com.brandon.climatesync.model.ClimateSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class WorldWeatherDriver {

    private final Plugin plugin;
    private final PollService poll;

    private BukkitTask task;

    public WorldWeatherDriver(Plugin plugin, PollService poll) {
        this.plugin = plugin;
        this.poll = poll;
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("weather.enabled", false)) {
            plugin.getLogger().info("WorldWeatherDriver disabled in config.");
            return;
        }

        long durationTicks = Math.max(200L, plugin.getConfig().getLong("weather.durationTicks", 216000L));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::applyOnce, 40L, durationTicks);

        plugin.getLogger().info("WorldWeatherDriver started. mode=WORLD_GLOBAL");
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
    }

    private void applyOnce() {
        List<String> worlds = plugin.getConfig().getStringList("weather.targetWorlds");
        if (worlds == null || worlds.isEmpty()) return;

        for (String wName : worlds) {
            World w = Bukkit.getWorld(wName);
            if (w == null) continue;

            String driverZone = plugin.getConfig().getString("weather.driverZonePerWorld." + wName, "").trim().toLowerCase(Locale.ROOT);
            if (driverZone.isEmpty()) continue;

            Optional<ClimateSnapshot> opt = poll.snapshot(driverZone);
            if (opt.isEmpty()) continue;

            ClimateSnapshot s = opt.get();

            // World-wide toggles only. Snow vs rain visuals are biome-driven.
            boolean storm = s.wet();
            boolean thunder = s.thunder();

            w.setStorm(storm);
            w.setThundering(storm && thunder);

            // keep some duration so it doesn't instantly clear (Paper uses its own counters too)
            w.setWeatherDuration((int) plugin.getConfig().getLong("weather.durationTicks", 216000L));
            w.setThunderDuration((int) plugin.getConfig().getLong("weather.durationTicks", 216000L));
        }
    }
}
