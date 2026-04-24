package dev.antimod.command;

import dev.antimod.AntiModDetect;
import dev.antimod.check.SignTranslationCheck;
import dev.antimod.config.ConfigManager;
import dev.antimod.detection.DetectionManager;
import dev.antimod.listener.PlayerJoinListener;
import dev.antimod.strike.StrikeManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Handles the {@code /antimoddetect} command (aliases: {@code /amd}, {@code /antimod}).
 *
 * <p>Sub-commands:
 * <ul>
 *   <li>{@code check <player>}              – manually trigger all checks</li>
 *   <li>{@code whitelist add <player>}      – add to bypass whitelist</li>
 *   <li>{@code whitelist remove <player>}   – remove from whitelist</li>
 *   <li>{@code whitelist list}              – list all whitelisted entries</li>
 *   <li>{@code strikes <player>}            – show strike count</li>
 *   <li>{@code strikes reset <player>}      – reset strike count</li>
 *   <li>{@code reload}                      – reload config</li>
 *   <li>{@code status}                      – show plugin status</li>
 *   <li>{@code debug [on|off]}              – toggle runtime debug mode</li>
 *   <li>{@code info <player>}               – detailed diagnostics for a player</li>
 * </ul>
 */
public final class AntiModCommand implements CommandExecutor, TabCompleter {

    private final AntiModDetect plugin;
    private final ConfigManager config;
    private final StrikeManager strikeManager;
    private final DetectionManager detectionManager;
    private final PlayerJoinListener joinListener;
    private final SignTranslationCheck signCheck;

    public AntiModCommand(AntiModDetect plugin,
                          ConfigManager config,
                          StrikeManager strikeManager,
                          DetectionManager detectionManager,
                          PlayerJoinListener joinListener,
                          SignTranslationCheck signCheck) {
        this.plugin           = plugin;
        this.config           = config;
        this.strikeManager    = strikeManager;
        this.detectionManager = detectionManager;
        this.joinListener     = joinListener;
        this.signCheck        = signCheck;
    }

    // ====================================================================
    //  Command dispatch
    // ====================================================================

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {

        if (!sender.hasPermission("antimoddetect.admin")) {
            send(sender, config.getMessage("no-permission"));
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "check"     -> cmdCheck(sender, args);
            case "forcecheck", "fc" -> cmdForceCheck(sender, args);
            case "whitelist", "wl" -> cmdWhitelist(sender, args);
            case "strikes"   -> cmdStrikes(sender, args);
            case "reload"    -> cmdReload(sender);
            case "status"    -> cmdStatus(sender);
            case "debug"     -> cmdDebug(sender, args);
            case "info"      -> cmdInfo(sender, args);
            default          -> { sendHelp(sender, label); yield true; }
        };
    }

    // ====================================================================
    //  Sub-commands
    // ====================================================================

    private boolean cmdCheck(CommandSender sender, String[] args) {
        if (!sender.hasPermission("antimoddetect.check")) {
            send(sender, config.getMessage("no-permission"));
            return true;
        }
        if (args.length < 2) {
            send(sender, "&cUsage: /amd check <player>");
            return true;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            send(sender, config.getMessage("player-offline")
                    .replace("{player}", args[1]));
            return true;
        }

        send(sender, config.getMessage("check-started")
                .replace("{player}", target.getName()));

        // Clear cooldown and clean up any existing sign session so we don't
        // get duplicate sign editors
        joinListener.clearCooldown(target.getUniqueId());
        joinListener.forceCheckPlayer(target);
        return true;
    }

    /**
     * {@code /amd forcecheck <player>} – identical to {@code check} but also
     * clears any in-progress sign session and ignores the check-on-join flag,
     * making it always fire regardless of config.
     */
    private boolean cmdForceCheck(CommandSender sender, String[] args) {
        if (!sender.hasPermission("antimoddetect.check")) {
            send(sender, config.getMessage("no-permission"));
            return true;
        }
        if (args.length < 2) {
            send(sender, "&cUsage: /amd forcecheck <player>");
            return true;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            send(sender, config.getMessage("player-offline")
                    .replace("{player}", args[1]));
            return true;
        }

        send(sender, config.getMessage("forcecheck-started")
                .replace("{player}", target.getName()));

        joinListener.forceCheckPlayer(target);
        return true;
    }

    private boolean cmdWhitelist(CommandSender sender, String[] args) {
        if (!sender.hasPermission("antimoddetect.whitelist")) {
            send(sender, config.getMessage("no-permission"));
            return true;
        }
        if (args.length < 2) {
            send(sender, "&cUsage: /amd whitelist <add|remove|list> [player]");
            return true;
        }

        return switch (args[1].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                if (args.length < 3) { send(sender, "&cUsage: /amd whitelist add <player>"); yield true; }
                String name = args[2];
                if (config.isWhitelisted(name) || config.isWhitelisted(getUuidString(name))) {
                    send(sender, config.getMessage("whitelist-already-added")
                            .replace("{player}", name));
                } else {
                    config.addToWhitelist(name);
                    send(sender, config.getMessage("whitelist-added")
                            .replace("{player}", name));
                }
                yield true;
            }
            case "remove" -> {
                if (args.length < 3) { send(sender, "&cUsage: /amd whitelist remove <player>"); yield true; }
                String name = args[2];
                boolean removed = config.removeFromWhitelist(name)
                        || config.removeFromWhitelist(getUuidString(name));
                if (removed) {
                    send(sender, config.getMessage("whitelist-removed")
                            .replace("{player}", name));
                } else {
                    send(sender, config.getMessage("whitelist-not-found")
                            .replace("{player}", name));
                }
                yield true;
            }
            case "list" -> {
                Set<String> entries = config.getWhitelistEntries();
                send(sender, config.getMessage("whitelist-list-header"));
                if (entries.isEmpty()) {
                    send(sender, config.getMessage("whitelist-list-empty"));
                } else {
                    for (String entry : entries) {
                        send(sender, config.getMessage("whitelist-list-entry")
                                .replace("{player}", entry)
                                .replace("{uuid}", entry));
                    }
                }
                yield true;
            }
            default -> {
                send(sender, "&cUsage: /amd whitelist <add|remove|list> [player]");
                yield true;
            }
        };
    }

    private boolean cmdStrikes(CommandSender sender, String[] args) {
        if (!sender.hasPermission("antimoddetect.admin")) {
            send(sender, config.getMessage("no-permission"));
            return true;
        }

        if (args.length < 2) {
            send(sender, "&cUsage: /amd strikes <player>  |  /amd strikes reset <player>");
            return true;
        }

        // /amd strikes reset <player>
        if (args[1].equalsIgnoreCase("reset")) {
            if (args.length < 3) {
                send(sender, "&cUsage: /amd strikes reset <player>");
                return true;
            }
            String name = args[2];
            UUID uuid = resolveUuid(name);
            if (uuid == null) {
                send(sender, config.getMessage("player-not-found")
                        .replace("{player}", name));
                return true;
            }
            strikeManager.resetStrikes(uuid);
            send(sender, config.getMessage("strikes-reset")
                    .replace("{player}", name));
            return true;
        }

        // /amd strikes <player>
        String name = args[1];
        UUID uuid = resolveUuid(name);
        if (uuid == null) {
            send(sender, config.getMessage("player-not-found")
                    .replace("{player}", name));
            return true;
        }
        int count = strikeManager.getStrikes(uuid);
        send(sender, config.getMessage("strikes-display")
                .replace("{player}", name)
                .replace("{count}", String.valueOf(count)));
        return true;
    }

    private boolean cmdReload(CommandSender sender) {
        if (!sender.hasPermission("antimoddetect.reload")) {
            send(sender, config.getMessage("no-permission"));
            return true;
        }
        try {
            config.reload();
            plugin.getAlertManager().reload();
            send(sender, config.getMessage("reload-success"));
        } catch (Exception e) {
            send(sender, config.getMessage("reload-failed"));
            plugin.getLogger().severe("Config reload failed: " + e.getMessage());
        }
        return true;
    }

    /**
     * {@code /amd debug [on|off]} – toggles runtime debug mode without
     * editing config.yml.  The override is cleared on server restart or
     * by calling {@code /amd debug off}.
     */
    private boolean cmdDebug(CommandSender sender, String[] args) {
        if (!sender.hasPermission("antimoddetect.admin")) {
            send(sender, config.getMessage("no-permission"));
            return true;
        }

        if (args.length >= 2) {
            boolean enable = args[1].equalsIgnoreCase("on");
            if (!enable && !args[1].equalsIgnoreCase("off")) {
                send(sender, "&cUsage: /amd debug [on|off]");
                return true;
            }
            config.setDebugOverride(enable);
            send(sender, config.getPrefix()
                    + (enable ? "&aDebug mode enabled &7(runtime override – not saved to config)."
                              : "&cDebug mode disabled &7(runtime override – not saved to config)."));
        } else {
            // Toggle
            boolean newState = !config.isDebug();
            config.setDebugOverride(newState);
            send(sender, config.getPrefix()
                    + (newState ? "&aDebug mode &nenabled&a. &7Check console for [AMD-DEBUG] output."
                                : "&cDebug mode &ndisabled&c."));
        }
        return true;
    }

    /**
     * {@code /amd info <player>} – shows a detailed diagnostic snapshot
     * for the specified player, helping to diagnose why checks may not
     * be triggering (e.g. through Velocity).
     */
    private boolean cmdInfo(CommandSender sender, String[] args) {
        if (!sender.hasPermission("antimoddetect.admin")) {
            send(sender, config.getMessage("no-permission"));
            return true;
        }
        if (args.length < 2) {
            send(sender, "&cUsage: /amd info <player>");
            return true;
        }

        Player target = Bukkit.getPlayer(args[1]);
        String name   = args[1];

        send(sender, "&8&m-----------&r &6AntiModDetect Info&8: &f" + name + " &8&m-----------");

        if (target == null) {
            send(sender, "  &cPlayer is not online.");
            return true;
        }

        // ── Online info ──────────────────────────────────────────────────
        send(sender, "  &7Name: &f" + target.getName()
                + "  &7UUID: &f" + target.getUniqueId());
        send(sender, "  &7World: &f" + target.getWorld().getName()
                + "  &7Gamemode: &f" + target.getGameMode()
                + "  &7Y: &f" + target.getLocation().getBlockY());
        String addr = target.getAddress() != null
                ? target.getAddress().getAddress().getHostAddress() : "&cnull (proxy?)";
        send(sender, "  &7Address: &f" + addr);

        // ── Exemption check ──────────────────────────────────────────────
        boolean hasBypass = target.hasPermission(config.getBypassPermission());
        boolean whitelisted = config.isWhitelisted(target.getName())
                || config.isWhitelisted(target.getUniqueId().toString());
        boolean exemptGM = config.getExemptGamemodes().contains(target.getGameMode().name());
        boolean exemptWorld = config.getExemptWorlds().contains(target.getWorld().getName());

        if (hasBypass || whitelisted || exemptGM || exemptWorld) {
            send(sender, "  &cPlayer is EXEMPT from checks:");
            if (hasBypass)   send(sender, "    &c- Has bypass permission (" + config.getBypassPermission() + ")");
            if (whitelisted) send(sender, "    &c- Is on the whitelist");
            if (exemptGM)    send(sender, "    &c- Exempt gamemode: " + target.getGameMode());
            if (exemptWorld) send(sender, "    &c- Exempt world: " + target.getWorld().getName());
        } else {
            send(sender, "  &aPlayer is NOT exempt. &7Checks can run.");
        }

        // ── Cooldown ─────────────────────────────────────────────────────
        Long lastCheck = joinListener.getLastCheckTime(target.getUniqueId());
        if (lastCheck == null) {
            send(sender, "  &7Cooldown: &anever checked this session");
        } else {
            long agoSecs = (System.currentTimeMillis() - lastCheck) / 1000;
            int cooldown = config.getRecheckCooldownSeconds();
            long remainSecs = Math.max(0, cooldown - agoSecs);
            if (remainSecs > 0) {
                send(sender, "  &7Cooldown: &clast check " + agoSecs + "s ago"
                        + ", &c" + remainSecs + "s remaining (use /amd forcecheck to bypass)");
            } else {
                send(sender, "  &7Cooldown: &alast check " + agoSecs + "s ago (cooldown expired)");
            }
        }

        // ── Strike count ─────────────────────────────────────────────────
        int strikes = strikeManager.getStrikes(target.getUniqueId());
        send(sender, "  &7Strikes: &e" + strikes + " &8/ " + config.getStrikeThreshold());

        // ── Sign check session ───────────────────────────────────────────
        String sessionState = signCheck.getSessionState(target.getUniqueId());
        send(sender, "  &7Sign session: &f" + sessionState);

        // ── Config summary ───────────────────────────────────────────────
        send(sender, "  &7check-on-join: " + boolState(config.isCheckOnJoin())
                + "  &7join-delay: &e" + config.getJoinCheckDelayTicks() + " ticks");
        send(sender, "  &7sign-detection: " + boolState(config.isSignDetectionEnabled())
                + "  &7keys: &e" + config.getTranslationKeys().size());
        boolean plAvailable = Bukkit.getPluginManager().getPlugin("ProtocolLib") != null;
        send(sender, "  &7ProtocolLib: " + (plAvailable ? "&aYes" : "&cNo (brand/channel via Bukkit Messenger only)"));
        send(sender, "  &7Debug mode: "
                + (config.isDebug() ? "&aON" : "&cOFF")
                + (config.isDebugOverrideSet() ? " &8(runtime override)" : " &8(from config)"));
        send(sender, "  &8Tip: run &e/amd debug on &8to enable verbose logging, then &e/amd forcecheck " + target.getName());
        send(sender, "&8&m--------------------------------------------------");
        return true;
    }

    private boolean cmdStatus(CommandSender sender) {
        boolean plAvailable = Bukkit.getPluginManager().getPlugin("ProtocolLib") != null;
        String note = plAvailable ? "" : config.getMessage("status-protocollib-note");

        send(sender, config.getMessage("status-header"));
        send(sender, config.getMessage("status-sign")
                .replace("{state}", boolState(config.isSignDetectionEnabled())));
        send(sender, config.getMessage("status-brand")
                .replace("{state}", boolState(config.isBrandDetectionEnabled()))
                .replace("{note}", plAvailable ? "" : note));
        send(sender, config.getMessage("status-channel")
                .replace("{state}", boolState(config.isChannelDetectionEnabled()))
                .replace("{note}", plAvailable ? "" : note));
        send(sender, config.getMessage("status-strikes")
                .replace("{state}", boolState(config.isStrikesEnabled())));

        int totalKeys = config.getTranslationKeys().size();
        int batches   = (int) Math.ceil(totalKeys / 4.0);
        send(sender, "  &7Translation keys: &e" + totalKeys
                + " &8(" + batches + " batch" + (batches == 1 ? "" : "es") + ")");
        send(sender, "  &7Brand signatures: &e" + config.getBrandEntries().size());
        send(sender, "  &7Channel signatures: &e" + config.getChannelEntries().size());
        send(sender, "  &7ProtocolLib: " + (plAvailable ? "&aInstalled" : "&cNot installed"));
        send(sender, "  &7Debug mode: "
                + (config.isDebug() ? "&aON" : "&cOFF")
                + (config.isDebugOverrideSet() ? " &8(runtime override)" : " &8(from config)"));
        return true;
    }

    // ====================================================================
    //  Help
    // ====================================================================

    private void sendHelp(CommandSender sender, String label) {
        send(sender, "&8&m              &r &6AntiModDetect &8&m              ");
        send(sender, "&e/" + label + " check <player>         &7– run checks (respects cooldown)");
        send(sender, "&e/" + label + " forcecheck <player>    &7– force-run all checks immediately");
        send(sender, "&e/" + label + " info <player>          &7– diagnostic info for a player");
        send(sender, "&e/" + label + " debug [on|off]         &7– toggle verbose debug output");
        send(sender, "&e/" + label + " whitelist <add|remove|list> [player]");
        send(sender, "&e/" + label + " strikes <player>");
        send(sender, "&e/" + label + " strikes reset <player>");
        send(sender, "&e/" + label + " reload");
        send(sender, "&e/" + label + " status");
        send(sender, "&8&m                                          ");
    }

    // ====================================================================
    //  Tab completion
    // ====================================================================

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        if (!sender.hasPermission("antimoddetect.admin")) return List.of();

        if (args.length == 1) {
            return filter(List.of("check", "forcecheck", "whitelist", "strikes", "reload", "status", "debug", "info"), args[0]);
        }
        if (args.length == 2) {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "check", "forcecheck", "fc", "strikes", "info" ->
                        Bukkit.getOnlinePlayers().stream()
                                .map(Player::getName)
                                .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                                .collect(Collectors.toList());
                case "whitelist", "wl" ->
                        filter(List.of("add", "remove", "list"), args[1]);
                case "debug" ->
                        filter(List.of("on", "off"), args[1]);
                default -> List.of();
            };
        }
        if (args.length == 3) {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "whitelist", "wl" ->
                        (args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("remove"))
                                ? Bukkit.getOnlinePlayers().stream()
                                .map(Player::getName)
                                .filter(n -> n.toLowerCase().startsWith(args[2].toLowerCase()))
                                .collect(Collectors.toList())
                                : List.of();
                case "strikes" ->
                        args[1].equalsIgnoreCase("reset")
                                ? Bukkit.getOnlinePlayers().stream()
                                .map(Player::getName)
                                .filter(n -> n.toLowerCase().startsWith(args[2].toLowerCase()))
                                .collect(Collectors.toList())
                                : List.of();
                default -> List.of();
            };
        }
        return List.of();
    }

    // ====================================================================
    //  Helpers
    // ====================================================================

    private void send(CommandSender sender, String message) {
        Component comp = LegacyComponentSerializer.legacyAmpersand()
                .deserialize(message.replace("{prefix}", config.getPrefix()));
        sender.sendMessage(comp);
    }

    private String boolState(boolean val) {
        return val ? config.getMessage("status-enabled")
                   : config.getMessage("status-disabled");
    }

    private static List<String> filter(List<String> options, String prefix) {
        return options.stream()
                .filter(s -> s.toLowerCase().startsWith(prefix.toLowerCase()))
                .collect(Collectors.toList());
    }

    @SuppressWarnings("deprecation")
    private static UUID resolveUuid(String name) {
        Player online = Bukkit.getPlayer(name);
        if (online != null) return online.getUniqueId();
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        return offline.hasPlayedBefore() ? offline.getUniqueId() : null;
    }

    @SuppressWarnings("deprecation")
    private static String getUuidString(String name) {
        Player online = Bukkit.getPlayer(name);
        if (online != null) return online.getUniqueId().toString();
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        return offline.hasPlayedBefore() ? offline.getUniqueId().toString() : "";
    }
}
