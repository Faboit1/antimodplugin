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

    /**
     * When true, ProtocolLib intercepts the {@code UPDATE_SIGN} (C→S) packet
     * directly, so we never need to retry: the client will respond exactly once
     * per batch (when it drains its packet queue after loading terrain).
     * Retrying would only queue extra {@code OPEN_SIGN_EDITOR} packets that fire
     * all at once when loading completes, causing the editor to flash repeatedly.
     */
    private boolean protocolLibMode = false;

    public SignTranslationCheck(JavaPlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
        this.log    = plugin.getLogger();
    }

    /**
     * Enables "packet mode": ProtocolLib intercepts the {@code UPDATE_SIGN}
     * response instead of relying on {@link org.bukkit.event.block.SignChangeEvent}.
     * In this mode the retry loop is disabled – only one editor packet is sent
     * per batch, and a single long timeout is used.  Call this once on enable
     * when ProtocolLib is detected.
     */
    public void setProtocolLibMode(boolean enabled) {
        this.protocolLibMode = enabled;
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

            // Skip version-gated entries if the server doesn't meet the minimum
            if (!entry.minMcVersion().isBlank()
                    && !config.meetsMinVersion(entry.minMcVersion())) {
                continue;
            }

            boolean matchesRawResponse = entry.vanillaResponses().contains(received);

            boolean detected;
            String info;
            if (entry.mustTranslate()) {
                // Inverted logic: this is a vanilla key that MUST be translated.
                // vanilla-responses lists the RAW (untranslated) values the client
                // would return if translation is blocked. If the received text
                // matches one of those raw values, the client did NOT translate
                // the key — indicating an anti-detection mod like ExploitPreventer.
                detected = matchesRawResponse;
                info = "Key '" + entry.key() + "' was not translated (returned '"
                        + received + "'). Expected a translated value. "
                        + "Possible anti-detection mod (ExploitPreventer or similar).";
            } else {
                // Normal logic: vanilla-responses lists expected vanilla outputs.
                // Flag if the response is NOT in the expected list.
                detected = !matchesRawResponse;
                info = "Key '" + entry.key() + "' resolved to '" + received + "'";
            }

            if (detected) {
                DetectionResult result = new DetectionResult(
                        session.getPlayerUuid(),
                        session.getPlayerName(),
                        entry.modName(),
                        entry.confidence(),
                        DetectionResult.CheckType.SIGN_TRANSLATION,
                        session.getPlayerIp(),
                        info);

                if (config.isDebug()) {
                    log.info("[AMD-DEBUG] DETECTION for " + player.getName()
                            + " mod=" + entry.modName()
                            + " key=" + entry.key()
                            + " received='" + received + "'"
                            + (entry.mustTranslate() ? " (must-translate check)" : ""));
                }

                if (!config.isDeduplicatePerSession()
                        || session.markIfNew(result.dedupeKey())) {
                    resultSink.accept(result);
                }
            }
        }

        // Response received — reset retry counter for next batch
        session.resetRetryCount();

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

            Player player = Bukkit.getPlayer(playerUuid);
            boolean online = player != null && player.isOnline();

            // Force-close the editor only for online players
            if (online) {
                forceCloseSignEditor(player, session);
            }

            // Restore the block immediately when the player is offline:
            // there is no sign-editor close packet to wait for, so a
            // synchronous restore prevents the sign from lingering in the
            // world after a disconnect or kick.
            restoreBlock(session, !online);
        }
    }

    /** Returns true if a session is currently active for this player. */
    public boolean hasActiveSession(UUID playerUuid) {
        return sessions.containsKey(playerUuid);
    }

    /**
     * Returns true if another active session (not owned by {@code excludeUuid})
     * has claimed the given block position as its current sign location.
     *
     * <p>This prevents two concurrent player sessions from using the same block
     * for their sign checks. If they shared a block, the second player's sign
     * placement would overwrite the first player's translatable text, causing
     * the first player's client to return the second player's translation results
     * and triggering a false positive.
     */
    private boolean isLocationOccupied(int bx, int by, int bz, World world, UUID excludeUuid) {
        for (Map.Entry<UUID, SignCheckSession> e : sessions.entrySet()) {
            if (e.getKey().equals(excludeUuid)) continue;
            Location loc = e.getValue().getSignLocation();
            if (loc != null
                    && world.equals(loc.getWorld())
                    && loc.getBlockX() == bx
                    && loc.getBlockY() == by
                    && loc.getBlockZ() == bz) {
                return true;
            }
        }
        return false;
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

        // Hide the temporary sign from OTHER players in the world.
        //
        // IMPORTANT: do NOT send this block-change to the checked player.
        // When a client receives a block-change to AIR for a position, it
        // removes the block entity (sign) from its local level cache.
        // If we clear the checked player's sign entity BEFORE openSign() is
        // called, the subsequent OPEN_SIGN_EDITOR packet finds no block entity
        // at that position (client calls level.getBlockEntity(pos) → null),
        // the editor never opens, no UPDATE_SIGN is ever sent, and the check
        // silently fails.  Instead we let the checked player's client retain
        // the sign entity (placed naturally when the block was set server-side)
        // so the editor can open and render the translatable components.
        // The checked player sees the sign for at most editor-open-delay-ticks
        // (default 2 ticks = 0.1 s) before forceCloseSignEditor() sends AIR
        // and makes it disappear — effectively invisible in practice.
        org.bukkit.block.data.BlockData originalBlockData = originalState.getBlockData();
        UUID checkedUuid = player.getUniqueId();
        for (Player other : placeLoc.getWorld().getPlayers()) {
            if (!other.getUniqueId().equals(checkedUuid)) {
                other.sendBlockChange(placeLoc, originalBlockData);
            }
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

            // Open the sign editor using the state captured at placement time.
            // Do NOT re-read the block from the world here: by the time this
            // delayed task fires another session may have placed a different sign
            // at the same location, which would cause the player's editor to show
            // that session's translation keys and trigger a false positive.
            player.openSign(sign, side);

            if (config.isDebug()) {
                log.info("[AMD-DEBUG] Sign editor sent to " + player.getName()
                        + " – force-closing immediately to capture translated response");
            }

            // Immediately force-close the sign editor so the client sends back
            // the rendered (possibly translated) text right away, without waiting
            // for the player to manually click Done.  Signs fire SignChangeEvent
            // as soon as the editor closes, so this is instant.
            forceCloseSignEditor(player, session);

            // Schedule a fallback timeout.
            //
            // Packet mode (ProtocolLib available):
            //   The UPDATE_SIGN response is intercepted directly, so we never
            //   retry.  Use the full check-timeout-ticks so the client has time
            //   to finish loading terrain and drain its packet queue.
            //
            // Fallback mode (no ProtocolLib, SignChangeEvent):
            //   Retry at the configured retry-interval until max-retries is
            //   reached, then fall back to the full timeout.
            int timeoutTicks = protocolLibMode
                    ? config.getCheckTimeoutTicks()
                    : (session.getRetryCount() < config.getSignMaxRetries()
                            ? config.getSignRetryIntervalTicks()
                            : config.getCheckTimeoutTicks());
            session.setTimeoutTask(
                    Bukkit.getScheduler().runTaskLater(plugin,
                            () -> onTimeout(player, session, resultSink),
                            timeoutTicks));
        }, openDelay);
    }

    /**
     * Called when the client does not respond within the timeout window.
     *
     * <p>In <em>packet mode</em> (ProtocolLib available): retries are disabled.
     * The client will respond via UPDATE_SIGN when it eventually drains its
     * incoming packet queue (e.g. after finishing terrain loading).  Sending
     * extra OPEN_SIGN_EDITOR packets would only build up a queue of editors
     * that all fire at once when loading completes, causing visible spam.
     * Instead we simply move on to the next batch if the timeout elapses
     * with no response.
     *
     * <p>In <em>fallback mode</em> (no ProtocolLib, SignChangeEvent): the
     * sign editor is re-opened at the same block location and immediately
     * force-closed again. Keeping the location stable is critical: a
     * different location per retry would cause {@link #isTestSign} to never
     * match the client's eventual {@code SignChangeEvent} (which always
     * targets the original location).  If all retries are exhausted, the
     * block is restored and we move to the next batch without triggering a
     * detection — silence is not proof of guilt.
     */
    private void onTimeout(Player player,
                           SignCheckSession session,
                           Consumer<DetectionResult> resultSink) {
        // Verify this session is still the active one for this player.
        if (!isCurrentSession(player.getUniqueId(), session)) {
            if (config.isDebug()) {
                log.info("[AMD-DEBUG] Timeout fired for stale session of "
                        + player.getName() + " – ignoring");
            }
            return;
        }

        if (!player.isOnline()) {
            cleanupSession(player.getUniqueId());
            return;
        }

        // ── Packet mode: never retry ─────────────────────────────────────
        // ProtocolLib intercepts UPDATE_SIGN directly, so the client will
        // respond on its own when ready.  Queuing more editor packets causes
        // the "spam" the user reported.  Just skip this batch and move on.
        if (protocolLibMode) {
            if (config.isDebug()) {
                log.info("[AMD-DEBUG] Sign check timed out (packet mode) for "
                        + player.getName() + " batch=" + session.getCurrentBatchIndex()
                        + " – skipping batch (client did not respond in time)");
            }
            if (config.isCloseEditorOnTimeout()) {
                forceCloseSignEditor(player, session);
            }
            restoreBlock(session);
            session.resetRetryCount();
            boolean hasMore = session.advanceBatch();
            if (hasMore) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (!player.isOnline()) { cleanupSession(player.getUniqueId()); return; }
                    if (!isCurrentSession(player.getUniqueId(), session)) return;
                    runBatch(player, session, resultSink);
                }, config.getBatchDelayTicks());
            } else {
                session.markCompleted();
                sessions.remove(player.getUniqueId());
            }
            return;
        }

        // ── Fallback mode: retry loop ────────────────────────────────────
        if (session.getRetryCount() < config.getSignMaxRetries()) {
            session.incrementRetryCount();
            if (config.isDebug()) {
                log.info("[AMD-DEBUG] Sign check retry " + session.getRetryCount()
                        + "/" + config.getSignMaxRetries()
                        + " for " + player.getName()
                        + " batch=" + session.getCurrentBatchIndex());
            }

            // Re-open the sign editor at the existing location rather than
            // restoring and re-placing the sign at a (potentially different)
            // location.  Calling restoreBlock() here would clear
            // session.signLocation; the subsequent runBatch() would then pick
            // a different Y position (due to the isSignMaterial guard in
            // resolveSignLocation), causing isTestSign() to never match the
            // client's SignChangeEvent which targets the original location.
            // Keeping signLocation stable throughout all retries ensures that
            // any late client response is still recognised correctly.
            Location retryLoc = session.getSignLocation();
            if (retryLoc != null) {
                BlockState bs = retryLoc.getBlock().getState();
                if (bs instanceof Sign retrySign) {
                    Side retrySide = config.isSignUseFrontSide() ? Side.FRONT : Side.BACK;
                    player.openSign(retrySign, retrySide);
                    forceCloseSignEditor(player, session);
                    int nextTimeout = session.getRetryCount() < config.getSignMaxRetries()
                            ? config.getSignRetryIntervalTicks()
                            : config.getCheckTimeoutTicks();
                    session.setTimeoutTask(Bukkit.getScheduler().runTaskLater(plugin,
                            () -> onTimeout(player, session, resultSink), nextTimeout));
                    return;
                }
            }

            // Fallback: sign location was lost or block is no longer a sign –
            // fall through to the original restore-and-rerun path.
            if (config.isCloseEditorOnTimeout()) {
                forceCloseSignEditor(player, session);
            }
            restoreBlock(session);

            // Re-run the same batch immediately (1 tick to let the close packet process)
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) {
                    cleanupSession(player.getUniqueId());
                    return;
                }
                if (!isCurrentSession(player.getUniqueId(), session)) return;
                runBatch(player, session, resultSink);
            }, 1L);
            return;
        }

        // All retries exhausted – give up on this batch
        if (config.isDebug()) {
            log.info("[AMD-DEBUG] Sign check timed out for "
                    + player.getName() + " batch=" + session.getCurrentBatchIndex()
                    + " after " + session.getRetryCount() + " retries");
        }

        if (config.isCloseEditorOnTimeout()) {
            forceCloseSignEditor(player, session);
        }

        restoreBlock(session);
        session.resetRetryCount();

        boolean hasMore = session.advanceBatch();
        if (hasMore) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (!player.isOnline()) {
                            cleanupSession(player.getUniqueId());
                            return;
                        }
                        if (!isCurrentSession(player.getUniqueId(), session)) return;

                        runBatch(player, session, resultSink);
                    },
                    config.getBatchDelayTicks());
        } else {
            session.markCompleted();
            sessions.remove(player.getUniqueId());
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
        World world  = player.getWorld();
        UUID uuid    = player.getUniqueId();
        int maxY     = world.getMaxHeight() - 1;
        int eyeY     = player.getEyeLocation().getBlockY();
        int startY   = eyeY + config.getSignStartYOffset();
        int endY     = eyeY + config.getSignMaxYOffset();
        int playerX  = player.getLocation().getBlockX();
        int playerZ  = player.getLocation().getBlockZ();

        for (int y = startY; y <= Math.min(endY, maxY); y++) {
            Block block = world.getBlockAt(playerX, y, playerZ);
            Material type = block.getType();
            // Skip solid blocks, sign-material blocks (could be a pending restore
            // from another batch of this player or a different concurrent session),
            // and blocks already claimed by another active session.
            if ((type.isAir() || !type.isSolid())
                    && !isSignMaterial(type)
                    && !isLocationOccupied(playerX, y, playerZ, world, uuid)) {
                return new Location(world, playerX, y, playerZ);
            }
        }

        // Fallback: use the configured Y offset above the player, but only if
        // that position is free from sign material and other active sessions.
        int fallbackY = Math.min(eyeY + config.getSignFallbackYOffset(), maxY);
        Material fallbackType = world.getBlockAt(playerX, fallbackY, playerZ).getType();
        if (isSignMaterial(fallbackType)
                || isLocationOccupied(playerX, fallbackY, playerZ, world, uuid)) {
            if (config.isDebug()) {
                log.info("[AMD-DEBUG] Fallback sign location ("
                        + playerX + "," + fallbackY + "," + playerZ
                        + ") is occupied – cannot find free placement for "
                        + player.getName());
            }
            return null;
        }
        return new Location(world, playerX, fallbackY, playerZ);
    }

    /** Restores the block that was temporarily replaced by the sign. */
    private void restoreBlock(SignCheckSession session) {
        restoreBlock(session, false);
    }

    /**
     * Restores the block that was temporarily replaced by the sign.
     *
     * @param immediate when {@code true} the restore is applied synchronously
     *                  on the current tick (used when the player is offline and
     *                  there is no sign-editor close packet to race against).
     */
    private void restoreBlock(SignCheckSession session, boolean immediate) {
        Location signLoc = session.getSignLocation();
        if (signLoc == null) return;

        BlockState saved = session.getSavedBlockState();

        // Clear location tracking immediately so that other sessions can reuse
        // this block position as soon as restoration is enqueued.
        session.setSignLocation(null);
        session.setSavedBlockState(null);

        if (saved == null) {
            signLoc.getBlock().setType(Material.AIR, false);
        } else if (immediate) {
            // Synchronous restore: safe when the player is offline and no
            // sign-editor close packet is in flight.
            if (isSignMaterial(signLoc.getBlock().getType())) {
                saved.update(true, false);
            }
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
