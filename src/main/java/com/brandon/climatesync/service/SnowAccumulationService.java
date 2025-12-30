package com.brandon.climatesync.service;

import com.brandon.climatesync.model.ClimateSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Snow;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Optional;
import java.util.Random;

/**
 * Snow Accumulation Service (SAS)
 * Places/removes snow layers around players based on mapped-zone temperature & wetness.
 *
 * NOTE: This service requires settings.safety.modifyBlocks=true in config.yml.
 */
public final class SnowAccumulationService {

    private final Plugin plugin;
    private final PollService pollService;
    private final Random rng = new Random();

    private BukkitTask task;

    public SnowAccumulationService(Plugin plugin, PollService pollService) {
        this.plugin = plugin;
        this.pollService = pollService;
    }

    public void start() {
        if (task != null) return;

        boolean enabled = plugin.getConfig().getBoolean("snow.enabled", false);
        if (!enabled) {
            plugin.getLogger().info("SnowAccumulationService disabled in config.");
            return;
        }

        int intervalSeconds = Math.max(5, plugin.getConfig().getInt("snow.tickIntervalSeconds", 20));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, intervalSeconds * 20L);

        String source = plugin.getConfig().getString("snow.source", "MAPPED_ZONE");
        String mode = plugin.getConfig().getString("snow.mode", "AROUND_PLAYERS");
        int radius = plugin.getConfig().getInt("snow.radiusBlocks", 96);
        int maxBlocks = plugin.getConfig().getInt("snow.maxBlocksPerRun", 4000);

        plugin.getLogger().info("SnowAccumulationService started. source=" + source
                + " mode=" + mode + " radius=" + radius + " maxBlocksPerRun=" + maxBlocks);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        // Safety gate
        boolean modifyBlocks = plugin.getConfig().getBoolean("safety.modifyBlocks", false)
                || plugin.getConfig().getBoolean("settings.safety.modifyBlocks", false);
        if (!modifyBlocks) {
            plugin.getLogger().warning("SnowAccumulationService is enabled, but settings.safety.modifyBlocks=false. Snow will NOT be placed/removed.");
            return;
        }

        boolean placeSnow = plugin.getConfig().getBoolean("snow.placeSnowLayers", true);
        boolean freezeWater = plugin.getConfig().getBoolean("snow.freezeWater", false);
        boolean meltIce = plugin.getConfig().getBoolean("snow.meltIce", false);

        double snowAtOrBelow = plugin.getConfig().getDouble("snow.thresholds.snowAtOrBelowC", 0.0);
        double blizzardAtOrBelow = plugin.getConfig().getDouble("snow.thresholds.blizzardAtOrBelowC", -5.0);
        double meltAtOrAbove = plugin.getConfig().getDouble("snow.thresholds.meltAtOrAboveC", 1.5);

        int normalMaxLayers = clamp(plugin.getConfig().getInt("snow.accumulation.normalMaxLayers", 3), 1, 8);
        int blizzardMaxLayers = clamp(plugin.getConfig().getInt("snow.accumulation.blizzardMaxLayers", 8), 1, 8);
        boolean blizzardRequiresWet = plugin.getConfig().getBoolean("snow.accumulation.blizzardRequiresWet", true);

        int radius = Math.max(8, plugin.getConfig().getInt("snow.radiusBlocks", 96));
        int maxBlocksPerRun = Math.max(100, plugin.getConfig().getInt("snow.maxBlocksPerRun", 4000));

        int budget = maxBlocksPerRun;

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (budget <= 0) break;
            if (!player.isOnline()) continue;

            // >>> FIX #1: Optional unwrap <<<
            Optional<ClimateSnapshot> opt = pollService.getSnapshotForPlayer(player);
            ClimateSnapshot snapshot = opt.orElse(null);
            if (snapshot == null) continue;

            World world = player.getWorld();

            double tempC = snapshot.tempC();
            boolean wet = snapshot.wet();

            boolean shouldSnow = tempC <= snowAtOrBelow;
            boolean shouldMelt = tempC >= meltAtOrAbove;
            boolean blizzard = tempC <= blizzardAtOrBelow && (!blizzardRequiresWet || wet);

            int targetMaxLayers = blizzard ? blizzardMaxLayers : normalMaxLayers;

            // Work a limited number of random columns around the player per tick
            int tries = Math.min(600, budget); // keep cheap
            int px = player.getLocation().getBlockX();
            int pz = player.getLocation().getBlockZ();

            for (int i = 0; i < tries && budget > 0; i++) {
                int dx = rng.nextInt(radius * 2 + 1) - radius;
                int dz = rng.nextInt(radius * 2 + 1) - radius;

                int x = px + dx;
                int z = pz + dz;

                // Find top solid block at this column
                int y = world.getHighestBlockYAt(x, z) - 1;
                if (y < world.getMinHeight()) continue;

                Block top = world.getBlockAt(x, y, z);
                Block above = top.getRelative(0, 1, 0);

                // Freeze water/ice logic (optional extras)
                if (freezeWater && shouldSnow) {
                    if (top.getType() == Material.WATER) {
                        top.setType(Material.ICE, false);
                        budget--;
                        continue;
                    }
                }
                if (meltIce && shouldMelt) {
                    if (top.getType() == Material.ICE) {
                        top.setType(Material.WATER, false);
                        budget--;
                        continue;
                    }
                }

                // Snow placement/removal
                if (placeSnow && shouldSnow) {
                    budget -= tryAddSnowLayer(above, targetMaxLayers);
                } else if (shouldMelt) {
                    budget -= tryMeltSnowLayer(above);
                }
            }
        }
    }

    private int tryAddSnowLayer(Block above, int maxLayers) {
        // Must be air or snow
        Material t = above.getType();
        if (t == Material.AIR) {
            above.setType(Material.SNOW, false);
            Snow snow = (Snow) above.getBlockData();
            snow.setLayers(1);
            above.setBlockData(snow, false);
            return 1;
        }

        if (t == Material.SNOW) {
            Snow snow = (Snow) above.getBlockData();
            int layers = snow.getLayers();
            if (layers < maxLayers) {
                snow.setLayers(layers + 1);
                above.setBlockData(snow, false);
                return 1;
            }
        }

        return 0;
    }

    private int tryMeltSnowLayer(Block above) {
        if (above.getType() != Material.SNOW) return 0;

        Snow snow = (Snow) above.getBlockData();
        int layers = snow.getLayers();
        if (layers <= 1) {
            above.setType(Material.AIR, false);
            return 1;
        } else {
            snow.setLayers(layers - 1);
            above.setBlockData(snow, false);
            return 1;
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
