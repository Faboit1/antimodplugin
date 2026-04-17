package dev.antimod.check;

import dev.antimod.config.ConfigManager;
import org.bukkit.block.BlockState;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Holds the mutable state for one in-progress sign translation check
 * for a single player.
 *
 * <p>The check is split into "batches" – each batch tests up to 4
 * translation keys on one sign (a sign only has 4 lines). Sessions
 * are created by {@link SignTranslationCheck} and destroyed when all
 * batches complete or when the player disconnects.
 */
public final class SignCheckSession {

    // ── Player info ──────────────────────────────────────────────────────
    private final UUID playerUuid;
    private final String playerName;
    private final String playerIp;

    // ── Batch state ──────────────────────────────────────────────────────
    /** All translation keys fetched from the config at session start. */
    private final List<ConfigManager.TranslationKeyEntry> allKeys;

    /** Index of the first key in the current batch (0, 4, 8, …). */
    private int currentBatchStart = 0;

    /** True once every batch has been processed. */
    private boolean completed = false;

    // ── Sign block ───────────────────────────────────────────────────────
    /** Location of the temporary sign block (null before first batch). */
    private org.bukkit.Location signLocation;

    /** Saved state of the block that was replaced by the sign. */
    private BlockState savedBlockState;

    // ── Timing / scheduling ──────────────────────────────────────────────
    /** The timeout task for the current batch (cancelled on result). */
    private BukkitTask timeoutTask;

    // ── Retry state ─────────────────────────────────────────────────────
    /** Number of times the current batch has been retried (sign re-sent). */
    private int retryCount = 0;

    // ── Accumulated detections across all batches ────────────────────────
    private final Set<String> firedDedupeKeys = new HashSet<>();

    // ====================================================================

    public SignCheckSession(UUID playerUuid,
                            String playerName,
                            String playerIp,
                            List<ConfigManager.TranslationKeyEntry> allKeys) {
        this.playerUuid  = playerUuid;
        this.playerName  = playerName;
        this.playerIp    = playerIp;
        this.allKeys     = new ArrayList<>(allKeys);
    }

    // ── Batch helpers ────────────────────────────────────────────────────

    /**
     * Returns the keys for the current batch (up to 4 entries starting
     * at {@link #currentBatchStart}).
     */
    public List<ConfigManager.TranslationKeyEntry> currentBatchKeys() {
        int end = Math.min(currentBatchStart + 4, allKeys.size());
        return allKeys.subList(currentBatchStart, end);
    }

    /** Advances the batch pointer by 4 and returns true if more remain. */
    public boolean advanceBatch() {
        currentBatchStart += 4;
        return currentBatchStart < allKeys.size();
    }

    public boolean hasMoreBatches() {
        return currentBatchStart < allKeys.size();
    }

    public int getTotalBatches() {
        return (int) Math.ceil(allKeys.size() / 4.0);
    }

    public int getCurrentBatchIndex() {
        return currentBatchStart / 4;
    }

    // ── Sign location ────────────────────────────────────────────────────

    public void setSignLocation(org.bukkit.Location loc) { this.signLocation = loc; }
    public org.bukkit.Location getSignLocation()          { return signLocation; }

    public void setSavedBlockState(BlockState state)      { this.savedBlockState = state; }
    public BlockState getSavedBlockState()                { return savedBlockState; }

    // ── Retry ─────────────────────────────────────────────────────────────

    public int getRetryCount()        { return retryCount; }
    public void incrementRetryCount() { retryCount++; }
    public void resetRetryCount()     { retryCount = 0; }

    // ── Timeout task ─────────────────────────────────────────────────────

    public void setTimeoutTask(BukkitTask task)           { this.timeoutTask = task; }

    public void cancelTimeout() {
        if (timeoutTask != null && !timeoutTask.isCancelled()) {
            timeoutTask.cancel();
            timeoutTask = null;
        }
    }

    // ── Deduplication ────────────────────────────────────────────────────

    /** Returns true if this deduplication key has NOT been seen yet,
     *  and marks it as seen. */
    public boolean markIfNew(String dedupeKey) {
        return firedDedupeKeys.add(dedupeKey);
    }

    // ── Completion ───────────────────────────────────────────────────────

    public boolean isCompleted()    { return completed; }
    public void markCompleted()     { completed = true; }

    // ── Getters ──────────────────────────────────────────────────────────

    public UUID getPlayerUuid()     { return playerUuid; }
    public String getPlayerName()   { return playerName; }
    public String getPlayerIp()     { return playerIp; }
    public int getCurrentBatchStart() { return currentBatchStart; }
}
