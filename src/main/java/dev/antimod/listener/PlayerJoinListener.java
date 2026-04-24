package dev.antimod.listener;

import dev.antimod.AntiModDetect;
import dev.antimod.check.ClientBrandCheck;
import dev.antimod.check.ChannelRegistrationCheck;
import dev.antimod.check.SignTranslationCheck;
import dev.antimod.config.ConfigManager;
import dev.antimod.detection.DetectionManager;
import dev.antimod.detection.DetectionResult;
import net.kyori.adventure.text.Component;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Core event listener that:
 * <ol>
 *   <li>Triggers detection checks after a player joins.</li>
 *   <li>Intercepts {@link SignChangeEvent} to process sign-translation results.</li>
 *   <li>Handles brand and channel plugin messages via {@link PluginMessageListener}.</li>
 *   <li>Cleans up sessions when a player disconnects.</li>
 * </ol>
 */
public final class PlayerJoinListener implements Listener, PluginMessageListener {

    private final AntiModDetect plugin;
    private final ConfigManager config;
    private final SignTranslationCheck signCheck;
    private final ClientBrandCheck brandCheck;
    private final ChannelRegistrationCheck channelCheck;
    private final DetectionManager detectionManager;
    private final Logger log;

    /** Tracks the last check timestamp to enforce the recheck cooldown. */
    private final Map<UUID, Long> lastCheckTime = new ConcurrentHashMap<>();

    public PlayerJoinListener(AntiModDetect plugin,
                              ConfigManager config,
                              SignTranslationCheck signCheck,
                              ClientBrandCheck brandCheck,
                              ChannelRegistrationCheck channelCheck,
                              DetectionManager detectionManager) {
        this.plugin           = plugin;
        this.config           = config;
        this.signCheck        = signCheck;
        this.brandCheck       = brandCheck;
        this.channelCheck     = channelCheck;
        this.detectionManager = detectionManager;
        this.log              = plugin.getLogger();
    }

    // ====================================================================
    //  Player join – trigger checks
    // ====================================================================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        if (config.isDebug()) {
            log.info("[AMD-DEBUG] PlayerJoinEvent fired for " + player.getName()
                    + " (" + player.getUniqueId() + ")"
                    + " world=" + player.getWorld().getName()
                    + " gamemode=" + player.getGameMode()
                    + " addr=" + (player.getAddress() != null
                            ? player.getAddress().getAddress().getHostAddress() : "null"));
        }

        // Honour the check-on-join config toggle
        if (!config.isCheckOnJoin()) {
            if (config.isDebug()) {
                log.info("[AMD-DEBUG] check-on-join is disabled; skipping auto-check for "
                        + player.getName());
            }
            return;
        }

        if (isExempt(player)) return;

        // Enforce recheck cooldown
        long now = System.currentTimeMillis();
        Long lastCheck = lastCheckTime.get(player.getUniqueId());
        int cooldownMs = config.getRecheckCooldownSeconds() * 1000;
        if (cooldownMs > 0 && lastCheck != null && (now - lastCheck) < cooldownMs) {
            if (config.isDebug()) {
                long remainingSecs = ((cooldownMs - (now - lastCheck)) / 1000);
                log.info("[AMD-DEBUG] Skipping checks for " + player.getName()
                        + " (recheck cooldown active, " + remainingSecs + "s remaining).");
            }
            return;
        }

        lastCheckTime.put(player.getUniqueId(), now);

        int delayTicks = config.getJoinCheckDelayTicks();
        if (config.isDebug()) {
            log.info("[AMD-DEBUG] Scheduling checks for " + player.getName()
                    + " in " + delayTicks + " tick(s) ("
                    + String.format("%.1f", delayTicks / 20.0) + "s)");
        }

        // Schedule checks after the configured delay (allows auth plugins
        // like AuthMe to finish processing before we start)
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                if (config.isDebug()) {
                    log.info("[AMD-DEBUG] " + player.getName()
                            + " went offline before check could start.");
                }
                return;
            }
            triggerChecks(player);
        }, delayTicks);
    }

    // ====================================================================
    //  Player quit – cleanup
    // ====================================================================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        signCheck.cleanupSession(uuid);
        detectionManager.onPlayerLeave(uuid);
        // Clear the recheck cooldown so the player is always re-checked on their
        // next join (e.g. after being kicked for an illegal mod).
        lastCheckTime.remove(uuid);

        if (config.isStrikeResetOnDisconnect()) {
            detectionManager.resetPlayer(uuid);
        }
    }

    // ====================================================================
    //  Sign change – process sign translation results
    // ====================================================================

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();

        // Check if this sign belongs to an active detection session
        Block block = event.getBlock();
        if (!signCheck.isTestSign(player, block.getLocation())) return;

        // Gather the 4 lines as Adventure Components
        Component[] lines = new Component[4];
        for (int i = 0; i < 4; i++) {
            Component line = event.line(i);
            lines[i] = line != null ? line : Component.empty();
        }

        // Hand off to the sign check; it returns true if it consumed the event
        boolean consumed = signCheck.handleSignChange(
                player, lines, this::handleDetection);

        if (consumed) {
            // Cancel so the sign content is never written to the actual block
            event.setCancelled(true);
        }
    }

    // ====================================================================
    //  Plugin message handler – brand and channel detection
    // ====================================================================

    /**
     * Handles incoming plugin messages from clients.
     *
     * <p>Called for the {@code minecraft:brand} and {@code minecraft:register}
     * channels (registered in {@link AntiModDetect#onEnable()}).
     *
     * <p>This fallback is used when ProtocolLib is NOT installed. When
     * ProtocolLib IS installed, {@link AntiModPacketListener} intercepts
     * these at the packet level before they reach this handler.
     */
    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (player == null || !player.isOnline()) return;
        if (isExempt(player)) return;

        String ip = player.getAddress() != null
                ? player.getAddress().getAddress().getHostAddress()
                : "unknown";

        List<DetectionResult> results = new ArrayList<>();

        if ("minecraft:brand".equals(channel)) {
            String brand = ClientBrandCheck.parseVarIntPrefixedString(message);
            if (config.isDebug()) {
                log.info("[AMD-DEBUG] Brand from " + player.getName()
                        + ": '" + brand + "'");
            }
            brandCheck.check(player.getUniqueId(), player.getName(), ip, brand, results);

        } else if ("minecraft:register".equals(channel)) {
            String[] channels = ChannelRegistrationCheck.parseChannelList(message);
            if (config.isDebug()) {
                log.info("[AMD-DEBUG] Registered channels from " + player.getName()
                        + ": " + Arrays.toString(channels));
            }
            channelCheck.check(player.getUniqueId(), player.getName(), ip, channels, results);
        }

        results.forEach(this::handleDetection);
    }

    // ====================================================================
    //  Internal helpers
    // ====================================================================

    /** Starts all detection checks for a player (sign + scheduled). */
    public void triggerChecks(Player player) {
        if (config.isDebug()) {
            log.info("[AMD-DEBUG] Triggering checks for " + player.getName());
        }
        // Sign translation check (primary, most reliable)
        if (config.isSignDetectionEnabled()) {
            signCheck.startChecks(player, this::handleDetection);
        }
        // Brand/channel checks happen via onPluginMessageReceived or
        // AntiModPacketListener – no additional scheduling needed here.
    }

    /** Routes a detection result to the detection manager. */
    private void handleDetection(DetectionResult result) {
        detectionManager.handle(result);
    }

    /**
     * Checks whether a player is exempt from detection checks.
     * Evaluates bypass permission, whitelist, exempt gamemodes, and exempt worlds.
     *
     * @return true if the player should be skipped
     */
    private boolean isExempt(Player player) {
        // Check bypass permission
        if (player.hasPermission(config.getBypassPermission())) {
            if (config.isDebug()) {
                log.info("[AMD-DEBUG] Skipping checks for " + player.getName()
                        + " (has bypass permission).");
            }
            return true;
        }

        // Check whitelist (by name and UUID)
        if (config.isWhitelisted(player.getName())
                || config.isWhitelisted(player.getUniqueId().toString())) {
            if (config.isDebug()) {
                log.info("[AMD-DEBUG] Skipping checks for " + player.getName()
                        + " (whitelisted).");
            }
            return true;
        }

        // Check exempt gamemodes
        if (config.getExemptGamemodes().contains(player.getGameMode().name())) {
            if (config.isDebug()) {
                log.info("[AMD-DEBUG] Skipping checks for " + player.getName()
                        + " (exempt gamemode: " + player.getGameMode() + ").");
            }
            return true;
        }

        // Check exempt worlds
        if (config.getExemptWorlds().contains(player.getWorld().getName())) {
            if (config.isDebug()) {
                log.info("[AMD-DEBUG] Skipping checks for " + player.getName()
                        + " (exempt world: " + player.getWorld().getName() + ").");
            }
            return true;
        }

        return false;
    }

    /** Returns the last-check timestamp map (for manual checks). */
    public void clearCooldown(UUID playerId) {
        lastCheckTime.remove(playerId);
    }

    /** Returns the last check timestamp for the given player, or {@code null} if never checked. */
    public Long getLastCheckTime(UUID playerId) {
        return lastCheckTime.get(playerId);
    }

    /**
     * Force-checks a player: clears the recheck cooldown, cleans up any
     * existing sign session, and immediately schedules all checks.
     *
     * <p>Unlike {@link #triggerChecks(Player)}, this bypasses both the
     * cooldown guard and the {@code check-on-join} flag, so it works even
     * when auto-checking is disabled.
     */
    public void forceCheckPlayer(Player player) {
        UUID uuid = player.getUniqueId();
        // Clear cooldown so the check always runs
        lastCheckTime.remove(uuid);
        // Abort any in-progress sign check session for this player to avoid
        // duplicate signs or state confusion
        signCheck.cleanupSession(uuid);

        if (config.isDebug()) {
            log.info("[AMD-DEBUG] Force-checking " + player.getName());
        }

        // Schedule checks on the next tick so callers can log first
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            triggerChecks(player);
        }, 1L);
    }
}
