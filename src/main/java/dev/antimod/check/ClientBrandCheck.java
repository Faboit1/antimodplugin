package dev.antimod.check;

import dev.antimod.config.ConfigManager;
import dev.antimod.detection.Confidence;
import dev.antimod.detection.DetectionResult;

import java.util.List;

/**
 * Check #2 – Client Brand Detection.
 *
 * <p><b>How it works:</b><br>
 * Every Minecraft client sends a {@code minecraft:brand} plugin
 * message immediately after the login handshake completes. The
 * payload is a VarInt-prefixed UTF-8 string that identifies the
 * client implementation (e.g. {@code "vanilla"}, {@code "fabric"},
 * {@code "Meteor Client"}, {@code "Wurst 7.x"}).
 *
 * <p>This class compares the received brand against the signature
 * list in {@code config.yml} using configurable match strategies
 * (CONTAINS, EQUALS, STARTS_WITH, ENDS_WITH, REGEX).
 *
 * <p><b>Reliability:</b><br>
 * The brand string is provided by the client and can be spoofed.
 * Legitimate Fabric modpacks also expose a "fabric" brand, so many
 * entries use {@link Confidence#HEURISTIC} to reduce false positives.
 *
 * <p><b>False positives:</b><br>
 * Any Fabric-based client (including legit performance mods like
 * Sodium) will report a "fabric" brand. Use HEURISTIC for Fabric/
 * Forge entries and only apply gentle actions (alert, no kick).
 *
 * <p><b>Extending:</b><br>
 * Add new entries to {@code brand-detection.brands} in config.yml.
 * Supports REGEX for complex patterns.
 */
public final class ClientBrandCheck {

    private final ConfigManager config;

    public ClientBrandCheck(ConfigManager config) {
        this.config = config;
    }

    /**
     * Checks the given client brand against all configured brand signatures.
     *
     * @param playerUuid player UUID
     * @param playerName player name
     * @param playerIp   player IP address
     * @param brand      the raw brand string received from the client
     * @param sink       list to add any {@link DetectionResult}s to
     */
    public void check(java.util.UUID playerUuid,
                      String playerName,
                      String playerIp,
                      String brand,
                      List<DetectionResult> sink) {

        if (!config.isBrandDetectionEnabled()) return;
        if (brand == null || brand.isBlank()) return;

        for (ConfigManager.BrandEntry entry : config.getBrandEntries()) {
            if (entry.matches(brand)) {
                sink.add(new DetectionResult(
                        playerUuid, playerName,
                        entry.modName(),
                        entry.confidence(),
                        DetectionResult.CheckType.CLIENT_BRAND,
                        playerIp));
                // We keep checking – a client could match multiple patterns
            }
        }
    }

    // ====================================================================
    //  VarInt-prefixed string parser (used by the PluginMessageHandler)
    // ====================================================================

    /**
     * Parses a VarInt-prefixed UTF-8 string from a raw byte array.
     *
     * <p>The {@code minecraft:brand} plugin message uses exactly this
     * encoding: a VarInt declaring the length of the string, followed
     * by the raw UTF-8 bytes.
     *
     * @param data raw bytes from the plugin message
     * @return the decoded brand string, or {@code null} if the data is malformed
     */
    public static String parseVarIntPrefixedString(byte[] data) {
        if (data == null || data.length == 0) return null;
        try {
            int i = 0;
            int length = 0;
            int shift  = 0;
            byte b;
            do {
                if (i >= data.length) return null;
                b = data[i++];
                length |= (b & 0x7F) << shift;
                shift  += 7;
            } while ((b & 0x80) != 0 && shift < 35); // max 5 bytes for VarInt

            if (length <= 0 || i + length > data.length) {
                // Some clients omit the VarInt prefix; fall back to raw UTF-8
                return new String(data, java.nio.charset.StandardCharsets.UTF_8).trim();
            }
            return new String(data, i, length, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
