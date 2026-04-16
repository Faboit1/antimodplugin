package dev.antimod.check;

import dev.antimod.config.ConfigManager;
import dev.antimod.detection.DetectionResult;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Check #1 – Sign Translation-Key Detection.
 *
 * <p><b>How it works:</b><br>
 * When a sign editor opens in the client, the client renders the
 * current sign text by resolving all Adventure/Minecraft components.
 * If a line contains a translatable component
 * ({@code {"translate":"key.category.meteor-client.meteor-client"}}),
 * the client substitutes the key with the translated value from its
 * loaded language files.
 *
 * <ol>
 *   <li>We temporarily place a sign block above the player.</li>
 *   <li>We write the sign lines as translatable Adventure components
 *       (one translation key per line, up to 4 per sign = one "batch").</li>
 *   <li>We force-open the sign editor for the player via
 *       {@link Player#openSign(Sign, Side)}, then <em>immediately</em>
 *       send a block-change to AIR at the sign location. This forces
 *       the client to close the editor straight away and send back a
 *       {@code SignChangeEvent} with the rendered text — no manual
 *       player interaction required.</li>
 *   <li>We compare each line against the "vanilla responses" list from
 *       config.yml. Any text that is NOT in that list means the client
 *       resolved the translation key – indicating the mod is present.</li>
 *   <li>We restore the original block immediately after reading the
 *       result (or on timeout fallback).</li>
 * </ol>
 *
 * <p><b>Multiple batches:</b><br>
 * Because a sign only has 4 lines, we split the configured translation
 * keys into groups of 4 and run each group sequentially with a
 * configurable delay between them. This supports an unlimited number
 * of translation keys.
 *
 * <p><b>Reliability:</b><br>
 * The technique is reliable for mods whose translation keys do not
 * exist in vanilla Minecraft lang files. Vanilla clients fall back to
 * rendering the raw key string (e.g.
 * {@code "key.category.meteor-client.meteor-client"}), which is in
 * the vanilla-responses list and therefore NOT flagged.
 *
 * <p><b>False positives:</b><br>
 * Mods that use the same key name as a vanilla key, or mods that
 * translate a key to the same string as the raw key, could cause
 * false negatives (missed detections), not false positives.
 * A translation key producing different text is always mod-specific.
 *
 * <p><b>Extending:</b><br>
 * Add entries to {@code sign-detection.keys} in config.yml. Each
 * entry needs the translation key, a mod name, a confidence level,
 * and the list of vanilla responses (at minimum: the raw key and
 * an empty string).
 */
public final class SignTranslationCheck {

    private final JavaPlugin plugin;
    private final ConfigManager config;
    private final Logger log;

    /** Active sessions, keyed by player UUID. */
    private final Map<UUID, SignCheckSession> sessions = new ConcurrentHashMap<>();

    public SignTranslationCheck(JavaPlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
        this.log    = plugin.getLogger();
    }

    // ====================================================================
    //  Public API
    // ====================================================================

    /**
     * Starts the full sign-translation check sequence for the given player.
     * All results are forwarded to {@code resultSink} when each batch
     * completes.
     *
     * <p>Must be called on the main server thread.
     *
     * @param player     the player to check
     * @param resultSink callback that receives every {@link DetectionResult}
     */
    public void startChecks(Player player, Consumer<DetectionResult> resultSink) {
        if (!config.isSignDetectionEnabled()) return;

        List<ConfigManager.TranslationKeyEntry> keys = config.getTranslationKeys();
        if (keys.isEmpty()) return;

        // Prevent duplicate/concurrent sessions – clean up any in-progress check
        if (config.isConcurrentCheckPrevention() && sessions.containsKey(player.getUniqueId())) {
            if (config.isDebug()) {
                log.info("[AMD-DEBUG] Cleaning up existing sign session for "
                        + player.getName() + " before starting new check");
            }
            cleanupSession(player.getUniqueId());
        }

        // Cap batches if configured
        int maxBatches = config.getMaxBatchesPerCheck();
        if (maxBatches > 0) {
            int maxKeys = maxBatches * 4;
            if (keys.size() > maxKeys) {
                keys = keys.subList(0, maxKeys);
            }
        }

        String ip = player.getAddress() != null
                ? player.getAddress().getAddress().getHostAddress()
                : "unknown";

        SignCheckSession session = new SignCheckSession(
                player.getUniqueId(), player.getName(), ip, keys);
        sessions.put(player.getUniqueId(), session);

        // Save the inventory the player currently has open so it can be
        // restored after all sign batches complete.  The player's own
        // crafting/survival inventory (InventoryType.CRAFTING) is the
        // default "nothing open" state and does not need to be restored.
        InventoryType openType = player.getOpenInventory().getType();
        if (openType != InventoryType.CRAFTING && openType != InventoryType.PLAYER) {
            session.setSavedOpenInventory(player.getOpenInventory().getTopInventory());
        }

        if (config.isDebug()) {
            log.info("[AMD-DEBUG] Starting sign check for " + player.getName()
                    + " – " + keys.size() + " keys in "
                    + session.getTotalBatches() + " batch(es)");
        }

        runBatch(player, session, resultSink);
    }

    /**
     * Called by {@link dev.antimod.listener.PlayerJoinListener} when a
     * {@code SignChangeEvent} is fired that matches an active session.
     *
     * @param player    the player who edited the sign
     * @param lines     the 4 Adventure components from the event
     * @param resultSink callback to forward any detections
     * @return true if this event was consumed (and should be cancelled)
     */
    public boolean handleSignChange(Player player,
                                    Component[] lines,
                                    Consumer<DetectionResult> resultSink) {
        SignCheckSession session = sessions.get(player.getUniqueId());
        if (session == null || session.isCompleted()) return false;

        session.cancelTimeout();

        if (config.isLogRawSignLines() || config.isDebug()) {
            for (int i = 0; i < lines.length; i++) {
                String plain = PlainTextComponentSerializer.plainText().serialize(lines[i]);
                log.info("[AMD-DEBUG] " + player.getName()
                        + " sign line[" + i + "]: '" + plain + "'");
            }
        }

        // Evaluate this batch
        List<ConfigManager.TranslationKeyEntry> batch = session.currentBatchKeys();
        for (int i = 0; i < batch.size(); i++) {
            ConfigManager.TranslationKeyEntry entry = batch.get(i);
            String received = PlainTextComponentSerializer.plainText().serialize(lines[i]);

            boolean isVanilla = entry.vanillaResponses().contains(received);
            if (!isVanilla) {
                // Non-vanilla response: the client resolved the translation key
                DetectionResult result = new DetectionResult(
                        session.getPlayerUuid(),
                        session.getPlayerName(),
                        entry.modName(),
                        entry.confidence(),
                        DetectionResult.CheckType.SIGN_TRANSLATION,
                        session.getPlayerIp());

                if (config.isDebug()) {
                    log.info("[AMD-DEBUG] DETECTION for " + player.getName()
                            + " mod=" + entry.modName()
                            + " key=" + entry.key()
                            + " received='" + received + "'");
                }

                if (!config.isDeduplicatePerSession()
                        || session.markIfNew(result.dedupeKey())) {
                    resultSink.accept(result);
                }
            }
        }

        // Restore the sign block
        restoreBlock(session);

        // Advance to next batch
        boolean hasMore = session.advanceBatch();
        if (hasMore) {
            // Schedule the next batch after the configured delay
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> {
                        Player p = Bukkit.getPlayer(session.getPlayerUuid());
                        if (p == null) {
                            cleanupSession(session.getPlayerUuid());
                            return;
                        }
                        // Verify this session is still the active one
                        if (!isCurrentSession(session.getPlayerUuid(), session)) return;

                        runBatch(p, session, resultSink);
                    },
                    config.getBatchDelayTicks());
        } else {
            // All batches done
            session.markCompleted();
            sessions.remove(player.getUniqueId());
            reopenSavedInventory(player, session);
        }

        return true; // consumed – caller should cancel the SignChangeEvent
    }

    /**
     * Checks whether the given block location belongs to an active sign
     * session for the given player.
     */
    public boolean isTestSign(Player player, Location loc) {
        SignCheckSession session = sessions.get(player.getUniqueId());
        if (session == null) return false;
        Location signLoc = session.getSignLocation();
        return signLoc != null && signLoc.getBlock().equals(loc.getBlock());
    }

    /**
     * Cleans up the session for a player (e.g. on disconnect or plugin
     * disable). Restores the sign block immediately and force-closes the
     * sign editor if the player is online.
     */
    public void cleanupSession(UUID playerUuid) {
        SignCheckSession session = sessions.remove(playerUuid);
        if (session != null) {
            session.cancelTimeout();

            // Force-close the editor for online players
            Player player = Bukkit.getPlayer(playerUuid);
            if (player != null && player.isOnline()) {
                forceCloseSignEditor(player, session);
            }

            restoreBlock(session);
        }
    }

    /** Returns true if a session is currently active for this player. */
    public boolean hasActiveSession(UUID playerUuid) {
        return sessions.containsKey(playerUuid);
    }

    /**
     * Returns true if the given session is still the current active session
     * for its player. Used to detect stale sessions whose timeout or next-batch
     * task fires after the session was replaced or cleaned up.
     */
    private boolean isCurrentSession(UUID playerUuid, SignCheckSession session) {
        return sessions.get(playerUuid) == session;
    }

    // ====================================================================
    //  Internal batch logic
    // ====================================================================

    /**
     * Executes one sign batch:
     * <ol>
     *   <li>Find a placement location above the player.</li>
     *   <li>Save the original block.</li>
     *   <li>Place the sign with translatable components.</li>
     *   <li>Force-open the sign editor.</li>
     *   <li>Schedule a timeout to handle unresponsive clients.</li>
     * </ol>
     */
    private void runBatch(Player player,
                          SignCheckSession session,
                          Consumer<DetectionResult> resultSink) {

        if (!player.isOnline()) {
            cleanupSession(player.getUniqueId());
            return;
        }

        List<ConfigManager.TranslationKeyEntry> batch = session.currentBatchKeys();
        if (batch.isEmpty()) {
            session.markCompleted();
            sessions.remove(player.getUniqueId());
            return;
        }

        // Find where to place the sign
        Location placeLoc = resolveSignLocation(player);
        if (placeLoc == null) {
            log.warning("[AntiModDetect] Could not find a placement location for "
                    + player.getName() + " – skipping sign batch "
                    + session.getCurrentBatchIndex());
            // Move to next batch
            boolean hasMore = session.advanceBatch();
            if (hasMore) {
                Bukkit.getScheduler().runTaskLater(plugin,
                        () -> runBatch(player, session, resultSink),
                        config.getBatchDelayTicks());
            } else {
                session.markCompleted();
                sessions.remove(player.getUniqueId());
            }
            return;
        }

        // Safety check: don't overwrite non-replaceable blocks if configured
        Block targetBlock = placeLoc.getBlock();
        if (config.isSkipIfBlockOccupied()
                && !targetBlock.getType().isAir()
                && targetBlock.getType().isSolid()) {
            if (config.isDebug()) {
                log.info("[AMD-DEBUG] Skipping occupied block at " + placeLoc
                        + " for " + player.getName());
            }
            // Try to find a different location next time
            boolean hasMore = session.advanceBatch();
            if (hasMore) {
                Bukkit.getScheduler().runTaskLater(plugin,
                        () -> runBatch(player, session, resultSink),
                        config.getBatchDelayTicks());
            } else {
                session.markCompleted();
                sessions.remove(player.getUniqueId());
            }
            return;
        }

        // Save original block state
        BlockState originalState = targetBlock.getState();
        session.setSavedBlockState(originalState);
        session.setSignLocation(placeLoc);

        // Place the sign (no physics – prevents the sign from falling)
        Material signMaterial = parseMaterial(config.getSignMaterial(), Material.OAK_SIGN);
        targetBlock.setType(signMaterial, /* applyPhysics = */ false);

        // Write translatable components to the sign lines
        BlockState newState = targetBlock.getState();
        if (!(newState instanceof Sign sign)) {
            log.warning("[AntiModDetect] Block at " + placeLoc
                    + " is not a Sign after placement – aborting batch.");
            originalState.update(true, false);
            session.setSignLocation(null);
            return;
        }

        Side side = config.isSignUseFrontSide() ? Side.FRONT : Side.BACK;
        SignSide signSide = sign.getSide(side);

        for (int i = 0; i < 4; i++) {
            if (i < batch.size()) {
                // Translatable component: the client resolves the key
                signSide.line(i, Component.translatable(batch.get(i).key()));
            } else {
                // Pad empty lines for unused slots
                signSide.line(i, Component.empty());
            }
        }
        sign.update(true, /* applyPhysics = */ false);

        if (config.isDebug()) {
            log.info("[AMD-DEBUG] Placed sign for " + player.getName()
                    + " at " + placeLoc + " batch=" + session.getCurrentBatchIndex()
                    + " keys=" + batch.stream()
                    .map(ConfigManager.TranslationKeyEntry::key)
                    .toList());
        }

        // Delay between placing/updating the sign and opening the editor.
        // This ensures the sign data packet reaches the client before the
        // open-editor packet, preventing the "empty sign" issue.
        int openDelay = config.getEditorOpenDelayTicks();

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // Validate the session is still active and the player is online
            if (!player.isOnline()) {
                cleanupSession(player.getUniqueId());
                return;
            }
            if (!isCurrentSession(player.getUniqueId(), session)) {
                // Session was replaced or removed – don't open the editor
                return;
            }

            // Open the sign editor for the player
            // Paper API: Player#openSign(Sign, Side) forces the editor open
            BlockState freshState = targetBlock.getState();
            if (freshState instanceof Sign freshSign) {
                player.openSign(freshSign, side);
            }

            if (config.isDebug()) {
                log.info("[AMD-DEBUG] Sign editor sent to " + player.getName()
                        + " – force-closing immediately to capture translated response");
            }

            // Immediately force-close the sign editor so the client sends back
            // the rendered (possibly translated) text right away, without waiting
            // for the player to manually click Done.  Signs fire SignChangeEvent
            // as soon as the editor closes, so this is instant.
            forceCloseSignEditor(player, session);

            // Schedule a short fallback timeout in case the client does not
            // respond to the force-close (e.g. high-latency or non-standard clients).
            session.setTimeoutTask(
                    Bukkit.getScheduler().runTaskLater(plugin,
                            () -> onTimeout(player, session, resultSink),
                            config.getCheckTimeoutTicks()));
        }, openDelay);
    }

    /**
     * Called when the client does not respond within the timeout window.
     *
     * <p>This can happen when:
     * <ul>
     *   <li>The player is on a hack client that suppresses the sign editor.</li>
     *   <li>The player has high latency.</li>
     *   <li>The player disconnected between the batch start and the timeout.</li>
     * </ul>
     *
     * <p>We do NOT trigger a detection here – silence is not proof of guilt.
     * We simply restore the block and move to the next batch.
     */
    private void onTimeout(Player player,
                           SignCheckSession session,
                           Consumer<DetectionResult> resultSink) {
        // Verify this session is still the active one for this player.
        // If it was replaced (e.g. by a forcecheck), don't act on the stale session.
        if (!isCurrentSession(player.getUniqueId(), session)) {
            if (config.isDebug()) {
                log.info("[AMD-DEBUG] Timeout fired for stale session of "
                        + player.getName() + " – ignoring");
            }
            return;
        }

        if (config.isDebug()) {
            log.info("[AMD-DEBUG] Sign check timed out for "
                    + player.getName() + " batch=" + session.getCurrentBatchIndex());
        }

        // The sign editor was already force-closed immediately after it was
        // opened (see runBatch).  This second call is a safety-net for clients
        // that may still have the editor open (e.g. if the first close was lost
        // due to high latency).
        if (config.isCloseEditorOnTimeout() && player.isOnline()) {
            forceCloseSignEditor(player, session);
        }

        restoreBlock(session);

        if (!player.isOnline()) {
            cleanupSession(player.getUniqueId());
            return;
        }

        boolean hasMore = session.advanceBatch();
        if (hasMore) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (!player.isOnline()) {
                            cleanupSession(player.getUniqueId());
                            return;
                        }
                        // Verify this session is still the active one
                        if (!isCurrentSession(player.getUniqueId(), session)) return;

                        runBatch(player, session, resultSink);
                    },
                    config.getBatchDelayTicks());
        } else {
            session.markCompleted();
            sessions.remove(player.getUniqueId());
            reopenSavedInventory(player, session);
        }
    }

    // ====================================================================
    //  Sign editor close helper
    // ====================================================================

    /**
     * Sends a block change to the player at the sign location, changing
     * it to AIR. This forces the client to close any open sign editor
     * for that block location and immediately send back a
     * {@code SignChangeEvent} with the rendered (possibly translated)
     * sign text.
     *
     * <p>Called right after {@link Player#openSign} so the check
     * completes without requiring any manual player interaction.
     * Also called as a safety-net from the timeout handler.
     */
    private void forceCloseSignEditor(Player player, SignCheckSession session) {
        Location signLoc = session.getSignLocation();
        if (signLoc == null || !player.isOnline()) return;

        // Send AIR to force the client to close the sign editor
        player.sendBlockChange(signLoc, Material.AIR.createBlockData());

        if (config.isDebug()) {
            log.info("[AMD-DEBUG] Force-closed sign editor for " + player.getName());
        }
    }

    /**
     * Reopens the inventory the player had open before the sign check began,
     * if one was saved. Scheduled a short time after the last batch completes
     * so the client has finished processing the sign editor close before the
     * inventory open packet arrives.
     */
    private void reopenSavedInventory(Player player, SignCheckSession session) {
        Inventory saved = session.getSavedOpenInventory();
        if (saved == null || !player.isOnline()) return;

        // Delay slightly so the sign-editor-close and block-restore packets
        // are processed by the client first.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                player.openInventory(saved);
                if (config.isDebug()) {
                    log.info("[AMD-DEBUG] Restored open inventory for " + player.getName());
                }
            }
        }, config.getBlockRestoreDelayTicks() + 1L);
    }

    // ====================================================================
    //  Block placement helpers
    // ====================================================================

    /**
     * Finds a suitable location for the temporary sign block.
     *
     * <p>In AUTO mode: scans upward from the player's eye level and returns
     * the first non-solid block within the configured range.
     * In FIXED mode: returns the configured world/x/y/z coordinates.
     *
     * @return a valid {@link Location}, or {@code null} if none was found
     */
    private Location resolveSignLocation(Player player) {
        if ("FIXED".equals(config.getSignPlacementMode())) {
            World world = Bukkit.getWorld(config.getSignFixedWorld());
            if (world == null) {
                log.warning("[AntiModDetect] Fixed sign world '"
                        + config.getSignFixedWorld() + "' not found.");
                return null;
            }
            return new Location(world,
                    config.getSignFixedX(),
                    config.getSignFixedY(),
                    config.getSignFixedZ());
        }

        // AUTO mode
        World world = player.getWorld();
        int maxY    = world.getMaxHeight() - 1;
        int eyeY    = player.getEyeLocation().getBlockY();
        int startY  = eyeY + config.getSignStartYOffset();
        int endY    = eyeY + config.getSignMaxYOffset();

        for (int y = startY; y <= Math.min(endY, maxY); y++) {
            Location loc = new Location(world,
                    player.getLocation().getBlockX(),
                    y,
                    player.getLocation().getBlockZ());
            Block block = world.getBlockAt(loc);
            if (block.getType().isAir() || !block.getType().isSolid()) {
                return loc;
            }
        }

        // Fallback: use the configured Y offset above the player
        int fallbackY = Math.min(eyeY + config.getSignFallbackYOffset(), maxY);
        return new Location(world,
                player.getLocation().getBlockX(),
                fallbackY,
                player.getLocation().getBlockZ());
    }

    /** Restores the block that was temporarily replaced by the sign. */
    private void restoreBlock(SignCheckSession session) {
        Location signLoc = session.getSignLocation();
        if (signLoc == null) return;

        BlockState saved = session.getSavedBlockState();
        if (saved == null) {
            signLoc.getBlock().setType(Material.AIR, false);
        } else {
            // Schedule the restore a few ticks later to avoid race conditions
            // with the sign editor close packet
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                // Only restore if the block is still a sign block
                // (another plugin might have already changed it)
                if (isSignMaterial(signLoc.getBlock().getType())) {
                    saved.update(true, false);
                }
            }, config.getBlockRestoreDelayTicks());
        }

        session.setSignLocation(null);
        session.setSavedBlockState(null);
    }

    // ====================================================================
    //  Utility
    // ====================================================================

    private static Material parseMaterial(String name, Material fallback) {
        try {
            Material m = Material.valueOf(name.toUpperCase(java.util.Locale.ROOT));
            return isSignMaterial(m) ? m : fallback;
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /**
     * Returns true if the given material is a (standing or wall) sign.
     *
     * <p>Uses {@code BlockState} instanceof check to avoid brittle string
     * matching: any sign material will produce a {@link Sign} BlockState.
     */
    private static boolean isSignMaterial(Material material) {
        if (material == null || !material.isBlock()) return false;
        // A zero-coordinate location in any loaded world gives us a test block.
        // We use a cleaner approach: check the BlockState class from the material.
        try {
            return material.createBlockData() != null
                    && org.bukkit.block.data.type.Sign.class
                            .isAssignableFrom(material.createBlockData().getClass())
                    || org.bukkit.block.data.type.WallSign.class
                            .isAssignableFrom(material.createBlockData().getClass());
        } catch (Exception e) {
            // Fall back to name-based check only as last resort
            String n = material.name();
            return n.endsWith("_SIGN") || n.endsWith("_WALL_SIGN");
        }
    }
}
