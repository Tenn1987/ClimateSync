package com.brandon.climatesync.service;

import com.brandon.climatesync.model.Zone;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.logging.Logger;

public final class ZoneRegistry {

    private final Plugin plugin;
    private final Logger log;
    private final Map<String, Zone> zonesById = new LinkedHashMap<>();

    public ZoneRegistry(Plugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    public void reload() {
        zonesById.clear();

        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("zones");
        if (sec == null) {
            log.warning("No zones found in config.yml (path: zones).");
            return;
        }

        // supports either list under zones: - {id,lat,lon,n} OR keyed sections
        // Your config uses list form, so we read that:
        List<Map<?, ?>> list = plugin.getConfig().getMapList("zones");
        if (list == null || list.isEmpty()) {
            // fallback: keyed nodes
            for (String key : sec.getKeys(false)) {
                ConfigurationSection z = sec.getConfigurationSection(key);
                if (z == null) continue;
                String id = z.getString("id", key);
                double lat = z.getDouble("lat");
                double lon = z.getDouble("lon");
                int n = z.getInt("n", 0);
                zonesById.put(id, new Zone(id, lat, lon, n));
            }
        } else {
            for (Map<?, ?> m : list) {
                String id = Objects.toString(m.get("id"), "").trim();
                if (id.isEmpty()) continue;
                double lat = asDouble(m.get("lat"));
                double lon = asDouble(m.get("lon"));
                int n = asInt(m.get("n"), 0);
                zonesById.put(id, new Zone(id, lat, lon, n));
            }
        }

        log.info("Zones loaded: " + zonesById.size());
    }

    public int size() {
        return zonesById.size();
    }

    public Set<String> ids() {
        return Collections.unmodifiableSet(zonesById.keySet());
    }

    public Optional<Zone> get(String id) {
        return Optional.ofNullable(zonesById.get(id));
    }

    private static double asDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0.0; }
    }

    private static int asInt(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return def; }
    }
}
