package com.brandon.climatesync.service;

import com.brandon.climatesync.model.ClimateSnapshot;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Snow;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.Tag;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Snow Accumulation Service (SAS)
 *
 * Places/removes snow layers around players based on mapped-zone (anchor-nearest) temperature and wetness.
 * - Does NOT modify biomes.
 * - Does NOT modify entities.
 * - Only modifies blocks if settings.safety.modifyBlocks = true.
 *
 * Designed for Paper 1.21.x.
 */
public final class SnowAccumulationService {

    private enum Source { MAPPED_ZONE, DRIVER_ZONE }
    private enum Mode { AROUND_PLAYERS }

    private final Plugin plugin;
    private final PollService pollService;

    private BukkitTask task;

    // Config
    private boolean enabled;
    private Source source;
    private Mode mode;

    private double snowAtOrBelowC;
    private double blizzardAtOrBelowC;
    private double meltAtOrAboveC;

    private int normalMaxLayers;     // 1..8
    private int blizzardMaxLayers;   // 1..8
    private boolean blizzardRequiresWet;

    private int radiusBlocks;
    private int maxBlocksPerRun;
    private int tickIntervalSeconds;

    private boolean placeSnowLayers;
    private boolean freezeWater;
    private boolean meltIce;

    // Mapping
    private String mappingWorldName;
    private List<Anchor> anchors = List.of();

    // Driver zones per world
    private final Map<String, String> driverZonePerWorld = new HashMap<>();

    // Minor throttles
    private final Map<UUID, Long> lastPlayerRunMs = new ConcurrentHashMap<>();
    private long minPlayerRunMs = 5_000L;

    private static final class Anchor {
        final String zoneId;
        final int x;
        final int z;
        Anchor(String zoneId, int x, int z) {
            this.zoneId = zoneId;
            this.x = x;
            this.z = z;
        }
    }

    public SnowAccumulationService(Plugin plugin, PollService pollService) {
        this.plugin = plugin;
        this.pollService = pollService;
    }

    public void start() {
        stop();
        reloadFromConfig();

        if (!enabled) {
            plugin.getLogger().info("SnowAccumulationService disabled in config.");
            return;
        }

        if (!plugin.getConfig().getBoolean("settings.safety.modifyBlocks", false)) {
            plugin.getLogger().warning("SnowAccumulationService is enabled, but settings.safety.modifyBlocks=false. Snow will NOT be placed/removed.");
        }

        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, Math.max(20L, tickIntervalSeconds * 20L));
        plugin.getLogger().info("SnowAccumulationService started. source=" + source + " mode=" + mode
                + " radius=" + radiusBlocks + " maxBlocksPerRun=" + maxBlocksPerRun);
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
    }

    public void reloadFromConfig() {
        enabled = plugin.getConfig().getBoolean("snow.enabled", false);

        String src = plugin.getConfig().getString("snow.source", "MAPPED_ZONE");
        source = "DRIVER_ZONE".equalsIgnoreCase(src) ? Source.DRIVER_ZONE : Source.MAPPED_ZONE;

        String m = plugin.getConfig().getString("snow.mode", "AROUND_PLAYERS");
        mode = Mode.AROUND_PLAYERS;

        snowAtOrBelowC = plugin.getConfig().getDouble("snow.thresholds.snowAtOrBelowC", 0.0);
        blizzardAtOrBelowC = plugin.getConfig().getDouble("snow.thresholds.blizzardAtOrBelowC", -5.0);
        meltAtOrAboveC = plugin.getConfig().getDouble("snow.thresholds.meltAtOrAboveC", 1.5);

        normalMaxLayers = clampInt(plugin.getConfig().getInt("snow.accumulation.normalMaxLayers", 3), 1, 8);
        blizzardMaxLayers = clampInt(plugin.getConfig().getInt("snow.accumulation.blizzardMaxLayers", 8), 1, 8);
        blizzardRequiresWet = plugin.getConfig().getBoolean("snow.accumulation.blizzardRequiresWet", true);

        radiusBlocks = clampInt(plugin.getConfig().getInt("snow.radiusBlocks", 96), 16, 256);
        maxBlocksPerRun = clampInt(plugin.getConfig().getInt("snow.maxBlocksPerRun", 4000), 200, 50_000);
        tickIntervalSeconds = clampInt(plugin.getConfig().getInt("snow.tickIntervalSeconds", 20), 5, 300);

        placeSnowLayers = plugin.getConfig().getBoolean("snow.placeSnowLayers", true);
        freezeWater = plugin.getConfig().getBoolean("snow.freezeWater", false);
        meltIce = plugin.getConfig().getBoolean("snow.meltIce", false);

        mappingWorldName = plugin.getConfig().getString("worldMapping.world", "world");

        // Load anchors
        // Load anchors
        anchors = new ArrayList<>();
        var list = plugin.getConfig().getMapList("worldMapping.anchors");
        for (Map<?, ?> raw : list) {
            Object zidObj = raw.get("zoneId");
            if (zidObj == null) continue;

            String zoneId = String.valueOf(zidObj).trim().toLowerCase(Locale.ROOT);
            if (zoneId.isEmpty()) continue;

            int x = parseInt(raw.get("mcX"), 0);
            int z = parseInt(raw.get("mcZ"), 0);

            anchors.add(new Anchor(zoneId, x, z));
        }


        // Load driver zone per world (for DRIVER_ZONE source or fallback)
        driverZonePerWorld.clear();
        if (plugin.getConfig().isConfigurationSection("weather.driverZonePerWorld")) {
            var sec = plugin.getConfig().getConfigurationSection("weather.driverZonePerWorld");
            for (String key : sec.getKeys(false)) {
                String zid = String.valueOf(sec.getString(key, "")).trim().toLowerCase(Locale.ROOT);
                if (!zid.isEmpty()) driverZonePerWorld.put(key, zid);
            }
        }
    }

    private void tick() {
        if (!enabled) return;

        boolean allowBlocks = plugin.getConfig().getBoolean("settings.safety.modifyBlocks", false);
        if (!allowBlocks) return;

        World mappingWorld = Bukkit.getWorld(mappingWorldName);
        if (mappingWorld == null) return;

        int budget = maxBlocksPerRun;

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.isOnline()) continue;
            if (!p.getWorld().equals(mappingWorld)) continue;

            long now = System.currentTimeMillis();
            long last = lastPlayerRunMs.getOrDefault(p.getUniqueId(), 0L);
            if (now - last < minPlayerRunMs) continue;
            lastPlayerRunMs.put(p.getUniqueId(), now);

            String zoneId = resolveZoneIdForPlayer(p);
            Optional<ClimateSnapshot> snapOpt = pollService.snapshot(zoneId);
            if (snapOpt.isEmpty()) continue;

            ClimateSnapshot s = snapOpt.get();

            double tempC = s.tempC();
            boolean wet = s.wet();

            boolean shouldAccumulate = placeSnowLayers && wet && tempC <= snowAtOrBelowC;
            boolean shouldMelt = tempC >= meltAtOrAboveC;

            int maxLayers = normalMaxLayers;
            boolean blizzard = tempC <= blizzardAtOrBelowC && (!blizzardRequiresWet || wet);
            if (blizzard) maxLayers = blizzardMaxLayers;

            if (plugin.getConfig().getBoolean("settings.debug.logSnowPass", false)) {
                plugin.getLogger().info("[SAS] p=" + p.getName() + " zone=" + zoneId + " tempC=" + String.format(Locale.US, "%.2f", tempC)
                        + " wet=" + wet + " blizzard=" + blizzard + " maxLayers=" + maxLayers + " melt=" + shouldMelt);
            }

            budget = applyAroundPlayer(p, shouldAccumulate, shouldMelt, maxLayers, budget);
            if (budget <= 0) break;
        }
    }

    private String resolveZoneIdForPlayer(Player p) {
        // If MAPPED_ZONE: nearest anchor by MC distance
        if (source == Source.MAPPED_ZONE && !anchors.isEmpty()) {
            Location loc = p.getLocation();
            int px = loc.getBlockX();
            int pz = loc.getBlockZ();

            Anchor best = null;
            long bestD2 = Long.MAX_VALUE;
            for (Anchor a : anchors) {
                long dx = (long) px - a.x;
                long dz = (long) pz - a.z;
                long d2 = dx * dx + dz * dz;
                if (d2 < bestD2) {
                    bestD2 = d2;
                    best = a;
                }
            }
            if (best != null) return best.zoneId;
        }

        // Fallback: driver zone for the player's world
        String w = p.getWorld().getName();
        return driverZonePerWorld.getOrDefault(w, "na_versailles_mo");
    }

    /**
     * Applies snow/ice changes around a player using a conservative scan with a budget.
     * Uses getHighestBlockYAt per column, so it’s much cheaper than full volume scan.
     */
    private int applyAroundPlayer(Player p, boolean accumulate, boolean melt, int maxLayers, int budget) {
        World w = p.getWorld();
        Location c = p.getLocation();
        int cx = c.getBlockX();
        int cz = c.getBlockZ();

        // Step size: scanning every block is too much; this still looks natural.
        final int step = 2;

        int r = radiusBlocks;
        int changed = 0;

        // Simple expanding square scan; stops when budget is used.
        for (int dz = -r; dz <= r && budget > 0; dz += step) {
            for (int dx = -r; dx <= r && budget > 0; dx += step) {
                int x = cx + dx;
                int z = cz + dz;

                // Mild circular-ish filter (optional)
                if ((dx * dx + dz * dz) > (r * r)) continue;

                int yTop = w.getHighestBlockYAt(x, z);
                if (yTop <= w.getMinHeight() + 2) continue;

                Block above = w.getBlockAt(x, yTop, z);
                Block ground = w.getBlockAt(x, yTop - 1, z);

                // Accumulate snow
                if (accumulate) {
                    if (tryPlaceOrBuildSnow(ground, above, maxLayers)) {
                        budget--;
                        changed++;
                    }
                }

                // Melt snow
                if (melt) {
                    if (tryMeltSnowOrIce(ground, above)) {
                        budget--;
                        changed++;
                    }
                }

                // Optional water freeze/melt can be added later, but guarded here:
                // (Keeping conservative: only affects surface block if enabled)
                if (!accumulate && freezeWater) {
                    // intentionally not implemented here (kept off by default)
                }
                if (meltIce) {
                    // intentionally not implemented here (kept off by default)
                }
            }
        }

        return budget;
    }

    private boolean tryPlaceOrBuildSnow(Block ground, Block above, int maxLayers) {
        if (!above.getType().isAir()) {
            // If it's already snow, we can increase layers if allowed
            if (above.getType() == Material.SNOW) {
                BlockData bd = above.getBlockData();
                if (bd instanceof Snow snow) {
                    int layers = snow.getLayers();
                    if (layers < maxLayers) {
                        snow.setLayers(Math.min(maxLayers, layers + 1));
                        above.setBlockData(snow, false);
                        return true;
                    }
                }
            }
            return false;
        }

        // Must be a reasonable surface to receive snow
        if (!isSnowableSurface(ground)) return false;

        // Place 1 layer
        above.setType(Material.SNOW, false);
        BlockData bd = above.getBlockData();
        if (bd instanceof Snow snow) {
            snow.setLayers(1);
            above.setBlockData(snow, false);
        }
        return true;
    }

    private boolean tryMeltSnowOrIce(Block ground, Block above) {
        // Melt snow above ground
        if (above.getType() == Material.SNOW) {
            BlockData bd = above.getBlockData();
            if (bd instanceof Snow snow) {
                int layers = snow.getLayers();
                if (layers > 1) {
                    snow.setLayers(layers - 1);
                    above.setBlockData(snow, false);
                } else {
                    above.setType(Material.AIR, false);
                }
                return true;
            }
            above.setType(Material.AIR, false);
            return true;
        }

        // Conservative: only melt top ice blocks if enabled (handled elsewhere if you want)
        return false;
    }

    private boolean isSnowableSurface(Block ground) {
        Material m = ground.getType();

        if (m.isAir()) return false;
        if (!m.isSolid()) return false;

        // Don’t place snow on blocks that look dumb/break gameplay
        if (m == Material.MAGMA_BLOCK) return false;
        if (m == Material.CAMPFIRE || m == Material.SOUL_CAMPFIRE) return false;
        if (m == Material.ICE || m == Material.PACKED_ICE || m == Material.BLUE_ICE) return false;

        // Avoid leaves
        if (Tag.LEAVES.isTagged(m)) return false;

        // Avoid common thin/partial blocks (many aren’t solid anyway, but belt+suspenders)
        String name = m.name();
        if (name.contains("SLAB") || name.contains("STAIRS") || name.contains("WALL") || name.contains("FENCE") ||
                name.contains("CARPET") || name.contains("PRESSURE_PLATE") || name.contains("BUTTON")) {
            return false;
        }

        return true;
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int parseInt(Object o, int def) {
        if (o == null) return def;
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (Exception ignored) {
            return def;
        }
    }
}