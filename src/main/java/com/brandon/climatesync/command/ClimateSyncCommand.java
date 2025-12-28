package com.brandon.climatesync.command;

import com.brandon.climatesync.model.ClimateSnapshot;
import com.brandon.climatesync.service.PollService;
import com.brandon.climatesync.service.ZoneRegistry;
import org.bukkit.command.*;

import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

public class ClimateSyncCommand implements CommandExecutor, TabCompleter {

    private final ZoneRegistry zones;
    private final PollService poll;
    private final BooleanSupplier showPrecip;

    public ClimateSyncCommand(ZoneRegistry zones, PollService poll, BooleanSupplier showPrecip) {
        this.zones = zones;
        this.poll = poll;
        this.showPrecip = (showPrecip == null ? () -> false : showPrecip);
    }

    private boolean perm(CommandSender sender) {
        return sender.hasPermission("climatesync.admin");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!perm(sender)) { sender.sendMessage("§cNo permission."); return true; }

        if (args.length == 0) {
            sender.sendMessage("§eClimateSync:");
            sender.sendMessage("§7/cs status");
            sender.sendMessage("§7/cs zone <id>");
            sender.sendMessage("§7/cs refresh [id|all]");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> {
                sender.sendMessage("§eZones: §f" + zones.size());
                sender.sendMessage("§ePolling: §f" + (poll.isRunning() ? "RUNNING" : "STOPPED"));
                sender.sendMessage("§eSnapshots: §f" + poll.snapshotCount());
                return true;
            }
            case "zone" -> {
                if (args.length < 2) { sender.sendMessage("§cUsage: /cs zone <id>"); return true; }
                String id = args[1].toLowerCase(Locale.ROOT);

                Optional<ClimateSnapshot> snap = poll.snapshot(id);
                if (snap.isEmpty()) {
                    sender.sendMessage("§cNo snapshot yet. Try: §f/cs refresh " + id);
                    return true;
                }

                ClimateSnapshot s = snap.get();
                sender.sendMessage("§eZone: §f" + id);
                sender.sendMessage("§eState: §f" + s.state() + (s.thunder() ? " §e(THUNDER)" : ""));
                sender.sendMessage("§eTempC: §f" + String.format(Locale.US, "%.2f", s.tempC()));
                sender.sendMessage("§eWet: §f" + s.wet());
                sender.sendMessage("§eObserved: §f" + s.observedMain());
                sender.sendMessage("§eUpdated: §f" + Instant.ofEpochSecond(s.updatedEpochSeconds()));

                if (showPrecip.getAsBoolean()) {
                    sender.sendMessage("§ePrecip (" + s.windowHours() + "h): §fRain="
                            + String.format(Locale.US, "%.2f", s.rainMm())
                            + "mm  Snow=" + String.format(Locale.US, "%.2f", s.snowMm()) + "mm");
                }
                return true;
            }
            case "refresh" -> {
                if (args.length == 1 || args[1].equalsIgnoreCase("all")) {
                    sender.sendMessage("§aRefreshing all zones.");
                    poll.refreshAllNow();
                    return true;
                }
                String id = args[1].toLowerCase(Locale.ROOT);
                sender.sendMessage("§aRefreshing zone §f" + id + "§a.");
                poll.refreshZoneNow(id);
                return true;
            }
            default -> {
                sender.sendMessage("§cUnknown subcommand.");
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!perm(sender)) return Collections.emptyList();

        if (args.length == 1) {
            return List.of("status", "zone", "refresh").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("zone") || args[0].equalsIgnoreCase("refresh"))) {
            String p = args[1].toLowerCase(Locale.ROOT);
            List<String> base = new ArrayList<>();
            if (args[0].equalsIgnoreCase("refresh")) base.add("all");
            base.addAll(zones.ids());
            return base.stream().filter(x -> x.startsWith(p)).limit(25).collect(Collectors.toList());
        }

        return Collections.emptyList();
    }
}
