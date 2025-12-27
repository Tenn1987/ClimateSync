package com.brandon.climatesync.service;

import com.brandon.climatesync.model.Zone;
import org.bukkit.plugin.Plugin;

import java.util.*;

public class ZoneRegistry {

    private final Plugin plugin;
    private final Map<String, Zone> zones = new LinkedHashMap<>();

    public ZoneRegistry(Plugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        zones.clear();

        List<Map<?, ?>> list = plugin.getConfig().getMapList("zones");
        if (list == null || list.isEmpty()) {
            plugin.getLogger().warning("No zones found in config.yml (path: zones).");
            return;
        }

        Set<String> seen = new HashSet<>();
        for (Map<?, ?> entry : list) {
            Object nObj = entry.get("n");
            Object idObj = entry.get("id");
            Object latObj = entry.get("lat");
            Object lonObj = entry.get("lon");
            if (nObj == null || idObj == null || latObj == null || lonObj == null) {
                plugin.getLogger().warning("Skipping invalid zone entry (missing n/id/lat/lon): " + entry);
                continue;
            }

            int n = Integer.parseInt(String.valueOf(nObj));
            String id = String.valueOf(idObj).trim().toLowerCase(Locale.ROOT);
            double lat = Double.parseDouble(String.valueOf(latObj));
            double lon = Double.parseDouble(String.valueOf(lonObj));

            if (!seen.add(id)) {
                plugin.getLogger().warning("Duplicate zone id in config.yml: " + id + " (skipping duplicate)");
                continue;
            }

            zones.put(id, new Zone(id, lat, lon, n));
        }
    }

    public int size() { return zones.size(); }
    public Optional<Zone> get(String id) { return Optional.ofNullable(zones.get(id.toLowerCase(Locale.ROOT))); }
    public Collection<Zone> all() { return Collections.unmodifiableCollection(zones.values()); }
    public Collection<String> ids() { return Collections.unmodifiableCollection(zones.keySet()); }
}
