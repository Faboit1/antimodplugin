package dev.antimod.detection;

import dev.antimod.alert.AlertManager;
import dev.antimod.config.ConfigManager;
import dev.antimod.config.ConfigManager.ActionConfig;
import dev.antimod.strike.StrikeManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.BanList;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Central dispatcher for all detection events.
 *
 * <p>Receives {@link DetectionResult}s from the three check classes
 * ({@code SignTranslationCheck}, {@code ClientBrandCheck},
 * {@code ChannelRegistrationCheck}) and:
 * <ol>
 *   <li>Looks up the per-confidence {@link ActionConfig}.</li>
 *   <li>Dispatches alerts / log entries via {@link AlertManager}.</li>
 *   <li>Kicks and/or bans the player if configured.</li>
 *   <li>Runs any configured console commands.</li>
 *   <li>Records a strike via {@link StrikeManager} and checks the
 *       threshold.</li>
 * </ol>
 */
public final class DetectionManager {

    private final JavaPlugin plugin;
    private final ConfigManager config;
    private final AlertManager alertManager;
    private final StrikeManager strikeManager;
    private final Logger log;

    /** Per-session deduplication: UUID → set of already-fired dedupe keys. */
    private final Map<UUID, Set<String>> sessionDedupeKeys = new ConcurrentHashMap<>();

    public DetectionManager(JavaPlugin plugin,
                            ConfigManager config,
                            AlertManager alertManager,
                            StrikeManager strikeManager) {
        this.plugin        = plugin;
        this.config        = config;
        this.alertManager  = alertManager;
        this.strikeManager = strikeManager;
        this.log           = plugin.getLogger();
    }

    // ====================================================================
    //  Public API
    // ====================================================================

    /**
     * Handles a single detection result.
     * Must be called on the main server thread.
     */
    public void handle(DetectionResult result) {
        // Per-session deduplication (across all check types)
        if (config.isDeduplicatePerSession()) {
            Set<String> seen = sessionDedupeKeys.computeIfAbsent(
                    result.getPlayerUuid(), k -> new HashSet<>());
            if (!seen.add(result.dedupeKey())) {
                if (config.isDebug()) {
                    log.info("[AMD-DEBUG] Deduplicated " + result.dedupeKey());
                }
                return;
            }
        }

        ActionConfig action = result.getConfidence() == Confidence.CONFIRMED
                ? config.getConfirmedAction()
                : config.getHeuristicAction();

        // 1. Dispatch alerts / logs
        alertManager.dispatch(result, action.alertStaff(), action.logConsole(), action.logFile());

        // 1b. Notify the checked player (if configured)
        if (action.notifyPlayer()) {
            Player player = Bukkit.getPlayer(result.getPlayerUuid());
            if (player != null && player.isOnline()
                    && action.notifyPlayerMessage() != null
                    && !action.notifyPlayerMessage().isBlank()) {
                String msg = applyPlaceholders(action.notifyPlayerMessage(), result, 0);
                Component notifyMsg = LegacyComponentSerializer.legacyAmpersand()
                        .deserialize(msg);
                player.sendMessage(notifyMsg);
            }
        }

        // 2. Strike system
        int strikes = 0;
        if (config.isStrikesEnabled()) {
            strikes = strikeManager.addStrike(result.getPlayerUuid());
            checkStrikeThreshold(result, strikes);
        }

        // 3. Kick (if player is still online after strike check)
        if (action.kickEnabled()) {
            Player player = Bukkit.getPlayer(result.getPlayerUuid());
            if (player != null && player.isOnline()) {
                String msg = applyPlaceholders(action.kickMessage(), result, strikes);
                applyKick(player, msg, action.kickCommand(), result, strikes);
            }
        }

        // 4. Ban
        if (action.banEnabled()) {
            Player player = Bukkit.getPlayer(result.getPlayerUuid());
            applyBan(result.getPlayerName(),
                    applyPlaceholders(action.banReason(), result, strikes),
                    action.banDuration(),
                    action.banCommand(),
                    result,
                    strikes,
                    player);
        }

        // 5. Custom commands
        if (action.runCommandsEnabled() && !action.commands().isEmpty()) {
            int delay = Math.max(0, action.commandDelayTicks());
            // strikes is not effectively final after the conditional assignment above,
            // so capture it in an immutable snapshot for use inside the lambda.
            final int strikesSnapshot = strikes;
            Bukkit.getScheduler().runTaskLater(plugin, () ->
                    runCommands(action.commands(), result, strikesSnapshot), delay);
        }
    }

    // ====================================================================
    //  Session lifecycle
    // ====================================================================

    /** Call when a player leaves to free session deduplication state. */
    public void onPlayerLeave(UUID playerUuid) {
        sessionDedupeKeys.remove(playerUuid);
    }

    /** Resets strike counter AND session dedup keys for a player. */
    public void resetPlayer(UUID playerUuid) {
        sessionDedupeKeys.remove(playerUuid);
        strikeManager.resetStrikes(playerUuid);
    }

    // ====================================================================
    //  Strike threshold
    // ====================================================================

    private void checkStrikeThreshold(DetectionResult result, int currentStrikes) {
        int threshold = config.getStrikeThreshold();
        if (currentStrikes < threshold) return;

        // Threshold reached – reset counter and take action
        strikeManager.resetStrikes(result.getPlayerUuid());
        ConfigManager.StrikeThresholdAction ta = config.getStrikeThresholdAction();

        log.warning("[AntiModDetect] Strike threshold (" + threshold
                + ") reached for " + result.getPlayerName() + " – applying threshold action.");

        Player player = Bukkit.getPlayer(result.getPlayerUuid());

        if (ta.banEnabled()) {
            applyBan(result.getPlayerName(),
                    applyPlaceholders(ta.banReason(), result, currentStrikes)
                            .replace("{count}", String.valueOf(currentStrikes)),
                    ta.banDuration(),
                    ta.banCommand(),
                    result,
                    currentStrikes,
                    player);
        }

        if (ta.kickEnabled() && player != null && player.isOnline()) {
            String msg = ta.kickMessage().replace("{count}", String.valueOf(currentStrikes));
            applyKick(player, msg, ta.kickCommand(), result, currentStrikes);
        }

        if (ta.runCommandsEnabled() && !ta.commands().isEmpty()) {
            Bukkit.getScheduler().runTaskLater(plugin, () ->
                    runCommands(ta.commands(), result, currentStrikes), ta.commandDelayTicks());
        }
    }

    // ====================================================================
    //  Helpers
    // ====================================================================

    /**
     * Kicks a player using either a custom console command or the native API.
     *
     * <p>If {@code kickCommand} is non-blank it is dispatched as a console
     * command (useful for ban-plugin integrations such as LiteBans or
     * AdvancedBan). Placeholders: {@code {player}}, {@code {uuid}},
     * {@code {mod}}, {@code {check-type}}, {@code {confidence}},
     * {@code {count}}, and {@code {message}} (the resolved kick message).
     *
     * <p>If {@code kickCommand} is blank the player is kicked via the Paper
     * API instead.
     */
    private void applyKick(Player player,
                           String resolvedMessage,
                           String kickCommand,
                           DetectionResult result,
                           int strikes) {
        if (kickCommand != null && !kickCommand.isBlank()) {
            String cmd = applyPlaceholders(kickCommand, result, strikes)
                    .replace("{message}", resolvedMessage);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        } else {
            Component kickMsg = LegacyComponentSerializer.legacyAmpersand()
                    .deserialize(resolvedMessage);
            player.kick(kickMsg);
        }
    }

    /**
     * Bans a player using either a custom console command or the native API.
     *
     * <p>If {@code banCommand} is non-blank it is dispatched as a console
     * command. Placeholders: {@code {player}}, {@code {uuid}}, {@code {mod}},
     * {@code {check-type}}, {@code {confidence}}, {@code {count}},
     * {@code {duration}}, and {@code {reason}}.
     *
     * <p>If {@code banCommand} is blank the Paper {@link BanList} API is used,
     * falling back to a plain {@code /ban} console command if the profile API
     * is unavailable.
     */
    private void applyBan(String playerName,
                          String reason,
                          String duration,
                          String banCommand,
                          DetectionResult result,
                          int strikes,
                          Player player) {
        if (banCommand != null && !banCommand.isBlank()) {
            String cmd = applyPlaceholders(banCommand, result, strikes)
                    .replace("{duration}", duration != null ? duration : "perm")
                    .replace("{reason}", reason);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            return;
        }

        // Native ban API (Paper BanList with fallback)
        try {
            java.util.Date expiry = parseDuration(duration);
            org.bukkit.profile.PlayerProfile profile;
            if (player != null) {
                profile = player.getPlayerProfile();
            } else {
                @SuppressWarnings("deprecation")
                org.bukkit.OfflinePlayer offline = Bukkit.getOfflinePlayer(playerName);
                UUID knownUuid = offline.hasPlayedBefore()
                        ? offline.getUniqueId()
                        : null;
                profile = knownUuid != null
                        ? Bukkit.createPlayerProfile(knownUuid, playerName)
                        : Bukkit.createPlayerProfile(playerName);
            }
            @SuppressWarnings("unchecked")
            BanList<org.bukkit.profile.PlayerProfile> banList =
                    (BanList<org.bukkit.profile.PlayerProfile>)
                            Bukkit.getBanList(BanList.Type.PROFILE);
            banList.addBan(profile, reason, expiry, "AntiModDetect");
        } catch (Exception e) {
            // Fallback: console /ban command (works with vanilla + EssentialsX)
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ban " + playerName + " " + reason);
        }

        if (player != null && player.isOnline()) {
            player.kick(LegacyComponentSerializer.legacyAmpersand()
                    .deserialize("&cYou have been banned: " + reason));
        }
    }

    /** Parses duration strings like "14d", "12h", "30m". Returns null = permanent. */
    private static java.util.Date parseDuration(String duration) {
        if (duration == null || duration.isBlank()
                || duration.equalsIgnoreCase("perm")
                || duration.equals("0")) {
            return null; // permanent
        }
        long millis = 0;
        String s = duration.toLowerCase(Locale.ROOT).trim();
        // Support multiple segments: "1d12h30m"
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)([dhms])")
                .matcher(s);
        while (m.find()) {
            long val = Long.parseLong(m.group(1));
            millis += switch (m.group(2)) {
                case "d"  -> val * 86_400_000L;
                case "h"  -> val * 3_600_000L;
                case "m"  -> val * 60_000L;
                case "s"  -> val * 1_000L;
                default   -> 0L;
            };
        }
        if (millis == 0) return null;
        return new java.util.Date(System.currentTimeMillis() + millis);
    }

    private void runCommands(List<String> commands,
                             DetectionResult result,
                             int strikes) {
        for (String cmd : commands) {
            String resolved = applyPlaceholders(cmd, result, strikes);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved);
        }
    }

    private String applyPlaceholders(String template,
                                     DetectionResult result,
                                     int strikes) {
        return alertManager.applyPlaceholders(template, result)
                .replace("{count}", String.valueOf(strikes));
    }
}
