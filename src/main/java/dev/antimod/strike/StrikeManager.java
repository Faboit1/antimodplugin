package dev.antimod.strike;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks per-player strike counts across sessions.
 *
 * <p>A "strike" is incremented whenever a CONFIRMED or HEURISTIC detection
 * fires (one strike per unique detection per session, when deduplication
 * is enabled). When the configured threshold is reached, the strike
 * threshold action is executed.
 *
 * <p>Thread-safe: uses {@link ConcurrentHashMap} internally.
 */
public final class StrikeManager {

    /** Internal counter store: UUID → strike count. */
    private final Map<UUID, Integer> strikes = new ConcurrentHashMap<>();

    private final JavaPlugin plugin;

    public StrikeManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Increments the strike count for the given player and returns
     * the new total.
     */
    public int addStrike(UUID playerId) {
        return strikes.merge(playerId, 1, Integer::sum);
    }

    /** Returns the current strike count for a player (0 if none). */
    public int getStrikes(UUID playerId) {
        return strikes.getOrDefault(playerId, 0);
    }

    /** Resets the strike count for a player to zero. */
    public void resetStrikes(UUID playerId) {
        strikes.remove(playerId);
    }

    /** Resets ALL players' strike counts (e.g. on plugin reload). */
    public void resetAll() {
        strikes.clear();
    }

    /** Returns a read-only snapshot of all strike entries. */
    public Map<UUID, Integer> getAllStrikes() {
        return Map.copyOf(strikes);
    }
}
