package dev.antimod.alert;

import dev.antimod.config.ConfigManager;
import dev.antimod.detection.Confidence;
import dev.antimod.detection.DetectionResult;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Logger;

/**
 * Handles all outbound communication for a detection event:
 * <ul>
 *   <li>In-game staff alert (broadcast to antimoddetect.notify)</li>
 *   <li>Server console message</li>
 *   <li>Persistent log file ({@code plugins/AntiModDetect/detections.log})</li>
 * </ul>
 *
 * <p>All three channels are independently configurable in config.yml.
 */
public final class AlertManager {

    private final JavaPlugin plugin;
    private final ConfigManager config;
    private final Logger log;

    /** Resolved path to the log file. */
    private Path logFilePath;

    public AlertManager(JavaPlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
        this.log    = plugin.getLogger();
        resolveLogFile();
    }

    /** Call after a config reload to pick up new file-path setting. */
    public void reload() {
        resolveLogFile();
    }

    // ====================================================================
    //  Public API
    // ====================================================================

    /**
     * Dispatches a detection result to all configured channels:
     * staff alert, console, and/or log file.
     */
    public void dispatch(DetectionResult result, boolean alertStaff,
                         boolean logConsole, boolean logFile) {

        // Respect the configured minimum log confidence
        if (result.getConfidence() == Confidence.HEURISTIC
                && config.getMinLogConfidence() == Confidence.CONFIRMED) {
            // Skip heuristic result when admin only wants CONFIRMED logged
            if (!alertStaff) return;
        }

        if (alertStaff) alertStaff(result);
        if (logConsole) logToConsole(result);
        if (logFile)    logToFile(result);
    }

    // ====================================================================
    //  Private helpers
    // ====================================================================

    /** Broadcasts a coloured message to all online players with the notify permission. */
    private void alertStaff(DetectionResult result) {
        String raw = config.getMessage("staff-alert");
        raw = applyPlaceholders(raw, result);
        Component msg = LegacyComponentSerializer.legacyAmpersand().deserialize(raw);
        String notifyPerm = "antimoddetect.notify";

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission(notifyPerm)) {
                p.sendMessage(msg);
            }
        }
    }

    /** Logs a plain-text summary to the server console. */
    private void logToConsole(DetectionResult result) {
        String raw = config.getMessage("console-log");
        log.warning(stripColours(applyPlaceholders(raw, result)));
    }

    /** Appends a line to the detection log file, rolling if needed. */
    private void logToFile(DetectionResult result) {
        if (logFilePath == null) return;

        String timestamp = new SimpleDateFormat(config.getLogTimestampFormat())
                .format(Date.from(result.getTimestamp()));
        String raw = config.getMessage("file-log");
        raw = applyPlaceholders(raw, result);
        raw = raw.replace("{timestamp}", timestamp);
        raw = stripColours(raw);

        try {
            // Roll if the file exceeds the configured size limit
            if (config.getLogMaxSizeMb() > 0 && Files.exists(logFilePath)) {
                long sizeBytes = Files.size(logFilePath);
                long limitBytes = (long) config.getLogMaxSizeMb() * 1024 * 1024;
                if (sizeBytes >= limitBytes) {
                    rollLogFile();
                }
            }
            // Append the entry
            try (BufferedWriter writer = Files.newBufferedWriter(
                    logFilePath,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND)) {
                writer.write(raw);
                writer.newLine();
            }
        } catch (IOException e) {
            log.warning("AntiModDetect: could not write to detection log: " + e.getMessage());
        }
    }

    /** Renames the current log file to a timestamped backup. */
    private void rollLogFile() {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        Path backup = logFilePath.getParent().resolve(
                logFilePath.getFileName().toString().replace(".log", "_" + ts + ".log"));
        try {
            Files.move(logFilePath, backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warning("AntiModDetect: could not roll log file: " + e.getMessage());
        }
    }

    private void resolveLogFile() {
        String relPath = config.getLogFilePath();
        logFilePath = plugin.getDataFolder().toPath().resolve(relPath);
        // Ensure parent directories exist
        try {
            Files.createDirectories(logFilePath.getParent());
        } catch (IOException e) {
            log.warning("AntiModDetect: could not create log directory: " + e.getMessage());
            logFilePath = null;
        }
    }

    // ====================================================================
    //  Placeholder substitution
    // ====================================================================

    public String applyPlaceholders(String template, DetectionResult result) {
        String ip = config.isLogIp() ? result.getPlayerIp() : "[hidden]";
        String time = new java.text.SimpleDateFormat(config.getLogTimestampFormat())
                .format(java.util.Date.from(result.getTimestamp()));
        return template
                .replace("{player}",     result.getPlayerName())
                .replace("{uuid}",       result.getPlayerUuid().toString())
                .replace("{mod}",        result.getModName())
                .replace("{check-type}", result.getCheckType().name())
                .replace("{confidence}", result.getConfidence().name())
                .replace("{ip}",         ip)
                .replace("{info}",       result.getAdditionalInfo())
                .replace("{time}",       time)
                .replace("{prefix}",     config.getPrefix());
    }

    /** Strips {@code &x} colour codes from a string. */
    public static String stripColours(String s) {
        return s.replaceAll("&[0-9a-fA-Fk-oK-OrR]", "");
    }
}
