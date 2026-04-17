package dev.antimod.detection;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable record of a single mod-detection event.
 *
 * <p>Created by each check (sign translation, brand, channel) and
 * handed to {@link DetectionManager} for processing.
 */
public final class DetectionResult {

    /** The type of check that produced this result. */
    public enum CheckType {
        SIGN_TRANSLATION,
        CLIENT_BRAND,
        CHANNEL_REGISTER
    }

    private final UUID playerUuid;
    private final String playerName;
    private final String modName;
    private final Confidence confidence;
    private final CheckType checkType;
    private final String playerIp;
    private final Instant timestamp;
    private final String additionalInfo;

    public DetectionResult(UUID playerUuid,
                           String playerName,
                           String modName,
                           Confidence confidence,
                           CheckType checkType,
                           String playerIp) {
        this(playerUuid, playerName, modName, confidence, checkType, playerIp, "");
    }

    public DetectionResult(UUID playerUuid,
                           String playerName,
                           String modName,
                           Confidence confidence,
                           CheckType checkType,
                           String playerIp,
                           String additionalInfo) {
        this.playerUuid     = playerUuid;
        this.playerName     = playerName;
        this.modName        = modName;
        this.confidence     = confidence;
        this.checkType      = checkType;
        this.playerIp       = playerIp;
        this.timestamp      = Instant.now();
        this.additionalInfo = additionalInfo != null ? additionalInfo : "";
    }

    public UUID getPlayerUuid()       { return playerUuid; }
    public String getPlayerName()     { return playerName; }
    public String getModName()        { return modName; }
    public Confidence getConfidence() { return confidence; }
    public CheckType getCheckType()   { return checkType; }
    public String getPlayerIp()       { return playerIp; }
    public Instant getTimestamp()     { return timestamp; }
    public String getAdditionalInfo() { return additionalInfo; }

    /** Deduplication key: same player + same mod + same check type. */
    public String dedupeKey() {
        return playerUuid + ":" + modName + ":" + checkType;
    }

    @Override
    public String toString() {
        return "DetectionResult{player=" + playerName
                + ", uuid=" + playerUuid
                + ", mod=" + modName
                + ", confidence=" + confidence
                + ", check=" + checkType + "}";
    }
}
