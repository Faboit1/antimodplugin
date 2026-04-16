package dev.antimod.check;

import dev.antimod.config.ConfigManager;
import dev.antimod.detection.DetectionResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Check #3 – Plugin Channel Registration Detection.
 *
 * <p><b>How it works:</b><br>
 * After login, clients send a {@code minecraft:register} plugin
 * message containing a null-byte-delimited list of channel names
 * that the client wants to communicate on. Many mods register
 * mod-specific channels (e.g. {@code "meteor-client:main"},
 * {@code "wurst:main"}).
 *
 * <p>We intercept this packet, split on {@code \0}, and compare
 * each channel against the list defined in config.yml.
 *
 * <p><b>Reliability:</b><br>
 * Channel names are sent by the client and can be spoofed, but most
 * clients do not bother to hide them. This is a reliable secondary
 * indicator.
 *
 * <p><b>False positives:</b><br>
 * Mods that share the same channel namespace could cause false
 * positives. Use HEURISTIC for ambiguous channel names.
 *
 * <p><b>Extending:</b><br>
 * Add new entries to {@code channel-detection.channels} in config.yml.
 */
public final class ChannelRegistrationCheck {

    private final ConfigManager config;

    public ChannelRegistrationCheck(ConfigManager config) {
        this.config = config;
    }

    /**
     * Checks a list of registered channel names against configured signatures.
     *
     * @param playerUuid player UUID
     * @param playerName player name
     * @param playerIp   player IP
     * @param channels   channel names received from the client
     * @param sink       list to add any {@link DetectionResult}s to
     */
    public void check(UUID playerUuid,
                      String playerName,
                      String playerIp,
                      String[] channels,
                      List<DetectionResult> sink) {

        if (!config.isChannelDetectionEnabled()) return;
        if (channels == null || channels.length == 0) return;

        for (String channel : channels) {
            if (channel == null || channel.isBlank()) continue;
            String lc = channel.trim().toLowerCase(java.util.Locale.ROOT);
            for (ConfigManager.ChannelEntry entry : config.getChannelEntries()) {
                if (lc.equals(entry.channel().toLowerCase(java.util.Locale.ROOT))) {
                    sink.add(new DetectionResult(
                            playerUuid, playerName,
                            entry.modName(),
                            entry.confidence(),
                            DetectionResult.CheckType.CHANNEL_REGISTER,
                            playerIp));
                }
            }
        }
    }

    /**
     * Parses a raw {@code minecraft:register} payload into an array of
     * channel name strings. The format is null-byte ({@code \0}) delimited.
     *
     * @param data raw bytes from the plugin message
     * @return array of channel names (never null, may be empty)
     */
    public static String[] parseChannelList(byte[] data) {
        if (data == null || data.length == 0) return new String[0];
        String raw = new String(data, StandardCharsets.UTF_8);
        // Split on null byte, filter blanks
        return java.util.Arrays.stream(raw.split("\0"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toArray(String[]::new);
    }
}
