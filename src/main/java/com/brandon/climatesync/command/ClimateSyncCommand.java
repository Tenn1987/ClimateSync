package com.brandon.climatesync.command;

import com.brandon.climatesync.model.ClimateSnapshot;
import com.brandon.climatesync.service.PollService;
import com.brandon.climatesync.service.ZoneRegistry;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;

public class ClimateSyncCommand implements CommandExecutor, TabCompleter {

    private final ZoneRegistry zones;
    private final PollService poll;
    private final BooleanSupplier showPrecip;

    public ClimateSyncCommand(ZoneRegistry zones, PollService poll, BooleanSupplier showPrecip) {
        this.zones = zones;
        this.poll = poll;
        this.showPrecip = showPrecip;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sender.sendMessage("Zones: " + zones.size());
            sender.sendMessage("Polling: " + (poll.isRunning() ? "RUNNING" : "STOPPED"));
            sender.sendMessage("Snapshots: " + zones.size()); // rough display; real snapshot count is internal
            return true;
        }

        if (args[0].equalsIgnoreCase("refresh")) {
            if (args.length == 1 || args[1].equalsIgnoreCase("all")) {
                poll.refreshAllNow();
                sender.sendMessage("Refreshing all zones now...");
                return true;
            }
            String zoneId = args[1].toLowerCase(Locale.ROOT);
            poll.refreshZoneNow(zoneId);
            sender.sendMessage("Refreshing zone now: " + zoneId);
            return true;
        }

        if (args[0].equalsIgnoreCase("zone")) {
            if (args.length < 2) {
                sender.sendMessage("Usage: /cs zone <zoneId>");
                return true;
            }
            String zoneId = args[1].toLowerCase(Locale.ROOT);
            Optional<ClimateSnapshot> opt = poll.snapshot(zoneId);
            if (opt.isEmpty()) {
                sender.sendMessage("No snapshot for zone: " + zoneId);
                return true;
            }

            ClimateSnapshot s = opt.get();
            sender.sendMessage("Zone: " + s.zoneId());
            sender.sendMessage("State: " + s.state() + (s.thunder() ? " (thunder)" : ""));
            sender.sendMessage(String.format(Locale.ROOT, "TempC: %.2f", s.tempC()));
            sender.sendMessage("Wet: " + s.wet());
            sender.sendMessage("Observed: " + s.observedMain());
            sender.sendMessage("Updated: " + Instant.ofEpochSecond(s.updatedEpochSeconds()));

            if (showPrecip.getAsBoolean()) {
                sender.sendMessage("PrecipWindowHours: " + s.windowHours());
                sender.sendMessage(String.format(Locale.ROOT, "RainMm: %.2f  SnowMm: %.2f", s.rainMm(), s.snowMm()));
            }
            return true;
        }

        sender.sendMessage("Unknown subcommand. Try: /cs status | /cs zone <id> | /cs refresh [all|<id>]");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("status", "zone", "refresh");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("zone")) {
            return new ArrayList<>(zones.ids());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("refresh")) {
            List<String> out = new ArrayList<>();
            out.add("all");
            out.addAll(zones.ids());
            return out;
        }
        return Collections.emptyList();
    }
}
