package dev.antimod.config;

import dev.antimod.detection.Confidence;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Typed, validated wrapper around the plugin's {@code config.yml}.
 *
 * <p>Call {@link #reload()} after {@link JavaPlugin#reloadConfig()} to
 * re-parse every value. All callers should use this class rather than
 * accessing the raw {@link FileConfiguration} directly.
 */
public final class ConfigManager {

    // ── Translation-key entry ────────────────────────────────────────────
    public record TranslationKeyEntry(
            String key,
            String modName,
            Confidence confidence,
            List<String> vanillaResponses) {}

    // ── Brand entry ──────────────────────────────────────────────────────
    public enum MatchType { CONTAINS, EQUALS, STARTS_WITH, ENDS_WITH, REGEX }

    public record BrandEntry(
            String brand,
            String modName,
            Confidence confidence,
            MatchType matchType) {

        /** Returns true if the given (lower-cased) clientBrand matches this entry. */
        public boolean matches(String clientBrand) {
            String lc = clientBrand.toLowerCase(Locale.ROOT);
            String b  = brand.toLowerCase(Locale.ROOT);
            return switch (matchType) {
                case CONTAINS    -> lc.contains(b);
                case EQUALS      -> lc.equals(b);
                case STARTS_WITH -> lc.startsWith(b);
                case ENDS_WITH   -> lc.endsWith(b);
                case REGEX       -> clientBrand.matches(brand); // raw-case regex
            };
        }
    }

    // ── Channel entry ────────────────────────────────────────────────────
    public record ChannelEntry(
            String channel,
            String modName,
            Confidence confidence) {}

    // ── Action config ────────────────────────────────────────────────────
    public record ActionConfig(
            boolean alertStaff,
            boolean logConsole,
            boolean logFile,
            boolean kickEnabled,
            String kickMessage,
            boolean banEnabled,
            String banDuration,
            String banReason,
            boolean runCommandsEnabled,
            int commandDelayTicks,
            List<String> commands,
            boolean notifyPlayer,
            String notifyPlayerMessage) {}

    // ── Strike threshold action ──────────────────────────────────────────
    public record StrikeThresholdAction(
            boolean banEnabled,
            String banDuration,
            String banReason,
            boolean kickEnabled,
            String kickMessage,
            boolean runCommandsEnabled,
            int commandDelayTicks,
            List<String> commands) {}

    // ====================================================================
    //  Fields
    // ====================================================================

    private final JavaPlugin plugin;
    private final Logger log;

    // Parsed lists (rebuilt on every reload)
    private List<TranslationKeyEntry> translationKeys = new ArrayList<>();
    private List<BrandEntry> brandEntries = new ArrayList<>();
    private List<ChannelEntry> channelEntries = new ArrayList<>();

    // Action configs
    private ActionConfig confirmedAction;
    private ActionConfig heuristicAction;

    // Strike
    private boolean strikesEnabled;
    private int strikeThreshold;
    private boolean strikeResetOnDisconnect;
    private StrikeThresholdAction strikeThresholdAction;

    // Logging
    private String logFilePath;
    private int logMaxSizeMb;
    private String logTimestampFormat;
    private boolean logIp;
    private Confidence minLogConfidence;

    // General
    private boolean debug;
    private String prefix;
    private String bypassPermission;
    private boolean checkOnJoin;
    private int joinCheckDelayTicks;
    private int checkTimeoutTicks;
    private int batchDelayTicks;
    private int blockRestoreDelayTicks;
    private int recheckCooldownSeconds;

    // Sign detection
    private boolean signDetectionEnabled;
    private String signPlacementMode;
    private String signMaterial;
    private boolean signUseFrontSide;
    private int signStartYOffset;
    private int signMaxYOffset;
    private int signFallbackYOffset;
    private String signFixedWorld;
    private int signFixedX, signFixedY, signFixedZ;
    private int editorOpenDelayTicks;
    private boolean closeEditorOnTimeout;

    // Brand / channel detection
    private boolean brandDetectionEnabled;
    private boolean channelDetectionEnabled;

    // Whitelist
    private final Set<String> whitelist = new HashSet<>();

    // Exemptions
    private Set<String> exemptGamemodes = new HashSet<>();
    private Set<String> exemptWorlds = new HashSet<>();

    // Deduplication
    private boolean deduplicatePerSession;

    // Advanced
    private int maxBatchesPerCheck;
    private boolean skipIfBlockOccupied;
    private boolean logRawSignLines;

    // Messages (raw strings with placeholders)
    private Map<String, String> messages = new HashMap<>();

    // ====================================================================
    //  Constructor / reload
    // ====================================================================

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.log    = plugin.getLogger();
        plugin.saveDefaultConfig();
        reload();
    }

    /** Re-reads and parses every value from the current config. */
    public void reload() {
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();

        // ── General ─────────────────────────────────────────────────────
        debug                   = cfg.getBoolean("general.debug", false);
        prefix                  = cfg.getString("general.prefix", "&8[&6AntiModDetect&8] ");
        bypassPermission        = cfg.getString("general.bypass-permission", "antimoddetect.bypass");
        checkOnJoin             = cfg.getBoolean("general.check-on-join", true);
        joinCheckDelayTicks     = cfg.getInt("general.join-check-delay-ticks", 60);
        checkTimeoutTicks       = cfg.getInt("general.check-timeout-ticks", 100);
        batchDelayTicks         = cfg.getInt("general.batch-delay-ticks", 20);
        blockRestoreDelayTicks  = cfg.getInt("general.block-restore-delay-ticks", 5);
        recheckCooldownSeconds  = cfg.getInt("general.recheck-cooldown-seconds", 300);

        // ── Sign detection ───────────────────────────────────────────────
        ConfigurationSection sd = cfg.getConfigurationSection("sign-detection");
        if (sd != null) {
            signDetectionEnabled    = sd.getBoolean("enabled", true);
            signPlacementMode       = sd.getString("placement-mode", "AUTO").toUpperCase(Locale.ROOT);
            signMaterial            = sd.getString("sign-material", "OAK_SIGN").toUpperCase(Locale.ROOT);
            signUseFrontSide        = sd.getBoolean("use-front-side", true);
            editorOpenDelayTicks    = sd.getInt("editor-open-delay-ticks", 2);
            closeEditorOnTimeout    = sd.getBoolean("close-editor-on-timeout", true);

            ConfigurationSection auto = sd.getConfigurationSection("auto-placement");
            if (auto != null) {
                signStartYOffset    = auto.getInt("start-y-offset", 2);
                signMaxYOffset      = auto.getInt("max-y-offset", 15);
                signFallbackYOffset = auto.getInt("fallback-y-offset", 3);
            } else {
                signStartYOffset = 2; signMaxYOffset = 15; signFallbackYOffset = 3;
            }

            ConfigurationSection fl = sd.getConfigurationSection("fixed-location");
            if (fl != null) {
                signFixedWorld = fl.getString("world", "world");
                signFixedX     = fl.getInt("x", 0);
                signFixedY     = fl.getInt("y", 100);
                signFixedZ     = fl.getInt("z", 0);
            }
        } else {
            signDetectionEnabled = true;
            editorOpenDelayTicks = 2;
            closeEditorOnTimeout = true;
        }

        // Parse translation key list
        translationKeys = parseTranslationKeys(cfg);

        // ── Brand / channel detection ────────────────────────────────────
        brandDetectionEnabled   = cfg.getBoolean("brand-detection.enabled", true);
        channelDetectionEnabled = cfg.getBoolean("channel-detection.enabled", true);
        brandEntries            = parseBrandEntries(cfg);
        channelEntries          = parseChannelEntries(cfg);

        // ── Actions ──────────────────────────────────────────────────────
        confirmedAction = parseActionConfig(cfg, "actions.confirmed");
        heuristicAction = parseActionConfig(cfg, "actions.heuristic");
        deduplicatePerSession = cfg.getBoolean("actions.deduplicate-per-session", true);

        // ── Strikes ──────────────────────────────────────────────────────
        strikesEnabled           = cfg.getBoolean("strikes.enabled", true);
        strikeThreshold          = cfg.getInt("strikes.threshold", 3);
        strikeResetOnDisconnect  = cfg.getBoolean("strikes.reset-on-disconnect", false);
        strikeThresholdAction    = parseStrikeThresholdAction(cfg);

        // ── Logging ──────────────────────────────────────────────────────
        logFilePath         = cfg.getString("logging.file-path", "detections.log");
        logMaxSizeMb        = cfg.getInt("logging.max-size-mb", 10);
        logTimestampFormat  = cfg.getString("logging.timestamp-format", "yyyy-MM-dd HH:mm:ss");
        logIp               = cfg.getBoolean("logging.log-ip", true);
        String minConf      = cfg.getString("logging.min-log-confidence", "HEURISTIC");
        try { minLogConfidence = Confidence.valueOf(minConf.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { minLogConfidence = Confidence.HEURISTIC; }

        // ── Whitelist ────────────────────────────────────────────────────
        whitelist.clear();
        List<String> wl = cfg.getStringList("whitelist.players");
        for (String entry : wl) {
            if (entry != null && !entry.isBlank()) {
                whitelist.add(entry.toLowerCase(Locale.ROOT));
            }
        }

        // ── Exemptions ──────────────────────────────────────────────────
        exemptGamemodes = new HashSet<>();
        for (String gm : cfg.getStringList("general.exempt-gamemodes")) {
            if (gm != null && !gm.isBlank()) {
                exemptGamemodes.add(gm.trim().toUpperCase(Locale.ROOT));
            }
        }
        exemptWorlds = new HashSet<>();
        for (String w : cfg.getStringList("general.exempt-worlds")) {
            if (w != null && !w.isBlank()) {
                exemptWorlds.add(w.trim());
            }
        }

        // ── Messages ─────────────────────────────────────────────────────
        messages = new HashMap<>();
        ConfigurationSection msgSec = cfg.getConfigurationSection("messages");
        if (msgSec != null) {
            for (String key : msgSec.getKeys(false)) {
                messages.put(key, msgSec.getString(key, ""));
            }
        }

        // ── Advanced ─────────────────────────────────────────────────────
        maxBatchesPerCheck   = cfg.getInt("advanced.max-batches-per-check", 0);
        skipIfBlockOccupied  = cfg.getBoolean("advanced.skip-if-block-occupied", false);
        logRawSignLines      = cfg.getBoolean("advanced.log-raw-sign-lines", false);
    }

    // ====================================================================
    //  Parsing helpers
    // ====================================================================

    private List<TranslationKeyEntry> parseTranslationKeys(FileConfiguration cfg) {
        List<TranslationKeyEntry> result = new ArrayList<>();
        List<?> keyList = cfg.getList("sign-detection.keys");
        if (keyList == null) return result;
        for (Object obj : keyList) {
            if (!(obj instanceof Map<?, ?> map)) continue;
            String key      = str(map, "key");
            String modName  = str(map, "mod-name");
            String confStr  = str(map, "confidence");
            if (key.isBlank() || modName.isBlank()) continue;
            Confidence conf = safeConf(confStr);
            List<String> vr = toStringList(map.get("vanilla-responses"));
            result.add(new TranslationKeyEntry(key, modName, conf, vr));
        }
        return result;
    }

    private List<BrandEntry> parseBrandEntries(FileConfiguration cfg) {
        List<BrandEntry> result = new ArrayList<>();
        List<?> list = cfg.getList("brand-detection.brands");
        if (list == null) return result;
        for (Object obj : list) {
            if (!(obj instanceof Map<?, ?> map)) continue;
            String brand   = str(map, "brand");
            String modName = str(map, "mod-name");
            String confStr = str(map, "confidence");
            String mtStr   = str(map, "match-type");
            if (brand.isBlank() || modName.isBlank()) continue;
            MatchType mt;
            try { mt = MatchType.valueOf(mtStr.toUpperCase(Locale.ROOT)); }
            catch (Exception e) { mt = MatchType.CONTAINS; }
            // Validate regex if needed
            if (mt == MatchType.REGEX) {
                try { Pattern.compile(brand); }
                catch (PatternSyntaxException ex) {
                    log.warning("Invalid REGEX brand pattern '" + brand + "': " + ex.getMessage());
                    continue;
                }
            }
            result.add(new BrandEntry(brand, modName, safeConf(confStr), mt));
        }
        return result;
    }

    private List<ChannelEntry> parseChannelEntries(FileConfiguration cfg) {
        List<ChannelEntry> result = new ArrayList<>();
        List<?> list = cfg.getList("channel-detection.channels");
        if (list == null) return result;
        for (Object obj : list) {
            if (!(obj instanceof Map<?, ?> map)) continue;
            String channel = str(map, "channel");
            String modName = str(map, "mod-name");
            String confStr = str(map, "confidence");
            if (channel.isBlank() || modName.isBlank()) continue;
            result.add(new ChannelEntry(channel, modName, safeConf(confStr)));
        }
        return result;
    }

    private ActionConfig parseActionConfig(FileConfiguration cfg, String basePath) {
        boolean alertStaff   = cfg.getBoolean(basePath + ".alert-staff", true);
        boolean logConsole   = cfg.getBoolean(basePath + ".log-console", true);
        boolean logFile      = cfg.getBoolean(basePath + ".log-file", true);
        boolean kickEnabled  = cfg.getBoolean(basePath + ".kick.enabled", false);
        String kickMsg       = cfg.getString(basePath + ".kick.message", "");
        boolean banEnabled   = cfg.getBoolean(basePath + ".ban.enabled", false);
        String banDuration   = cfg.getString(basePath + ".ban.duration", "7d");
        String banReason     = cfg.getString(basePath + ".ban.reason", "Unauthorized mod");
        boolean runCmds      = cfg.getBoolean(basePath + ".run-commands.enabled", false);
        int cmdDelay         = cfg.getInt(basePath + ".run-commands.delay-ticks", 0);
        List<String> cmds    = cfg.getStringList(basePath + ".run-commands.commands");
        boolean notifyPlayer = cfg.getBoolean(basePath + ".notify-player.enabled", false);
        String notifyMsg     = cfg.getString(basePath + ".notify-player.message", "");
        return new ActionConfig(alertStaff, logConsole, logFile,
                kickEnabled, kickMsg, banEnabled, banDuration, banReason,
                runCmds, cmdDelay, cmds, notifyPlayer, notifyMsg);
    }

    private StrikeThresholdAction parseStrikeThresholdAction(FileConfiguration cfg) {
        String base = "strikes.threshold-action";
        boolean banEnabled  = cfg.getBoolean(base + ".ban.enabled", true);
        String banDur       = cfg.getString(base + ".ban.duration", "14d");
        String banReason    = cfg.getString(base + ".ban.reason", "Multiple mod detections");
        boolean kickEnabled = cfg.getBoolean(base + ".kick.enabled", false);
        String kickMsg      = cfg.getString(base + ".kick.message", "");
        boolean runCmds     = cfg.getBoolean(base + ".run-commands.enabled", false);
        int cmdDelay        = cfg.getInt(base + ".run-commands.delay-ticks", 0);
        List<String> cmds   = cfg.getStringList(base + ".run-commands.commands");
        return new StrikeThresholdAction(banEnabled, banDur, banReason,
                kickEnabled, kickMsg, runCmds, cmdDelay, cmds);
    }

    // ── Small helpers ────────────────────────────────────────────────────

    private static String str(Map<?, ?> map, String key) {
        Object v = map.get(key);
        return v == null ? "" : v.toString().trim();
    }

    private static List<String> toStringList(Object obj) {
        if (obj instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) if (o != null) out.add(o.toString());
            return out;
        }
        return new ArrayList<>();
    }

    private static Confidence safeConf(String s) {
        try { return Confidence.valueOf(s.toUpperCase(Locale.ROOT)); }
        catch (Exception e) { return Confidence.HEURISTIC; }
    }

    // ====================================================================
    //  Whitelist persistence helpers
    // ====================================================================

    /** Add a player name/UUID to the whitelist (also saves to disk). */
    public void addToWhitelist(String nameOrUuid) {
        whitelist.add(nameOrUuid.toLowerCase(Locale.ROOT));
        saveWhitelistToDisk();
    }

    /** Remove a player name/UUID from the whitelist (also saves to disk). */
    public boolean removeFromWhitelist(String nameOrUuid) {
        boolean removed = whitelist.remove(nameOrUuid.toLowerCase(Locale.ROOT));
        if (removed) saveWhitelistToDisk();
        return removed;
    }

    public boolean isWhitelisted(String nameOrUuid) {
        return whitelist.contains(nameOrUuid.toLowerCase(Locale.ROOT));
    }

    public Set<String> getWhitelistEntries() {
        return Collections.unmodifiableSet(whitelist);
    }

    private void saveWhitelistToDisk() {
        plugin.getConfig().set("whitelist.players", new ArrayList<>(whitelist));
        plugin.saveConfig();
    }

    // ====================================================================
    //  Message helper
    // ====================================================================

    /**
     * Returns a message from the messages section with the given key,
     * with the prefix placeholder pre-substituted.
     */
    public String getMessage(String key) {
        String raw = messages.getOrDefault(key, "&cMissing message: " + key);
        return raw.replace("{prefix}", prefix);
    }

    // ====================================================================
    //  Getters
    // ====================================================================

    public boolean isDebug()                                { return debug; }
    public String getPrefix()                               { return prefix; }
    public String getBypassPermission()                     { return bypassPermission; }
    public boolean isCheckOnJoin()                          { return checkOnJoin; }
    public int getJoinCheckDelayTicks()                     { return joinCheckDelayTicks; }
    public int getCheckTimeoutTicks()                       { return checkTimeoutTicks; }
    public int getBatchDelayTicks()                         { return batchDelayTicks; }
    public int getBlockRestoreDelayTicks()                  { return blockRestoreDelayTicks; }
    public int getRecheckCooldownSeconds()                  { return recheckCooldownSeconds; }

    public boolean isSignDetectionEnabled()                 { return signDetectionEnabled; }
    public String getSignPlacementMode()                    { return signPlacementMode; }
    public String getSignMaterial()                         { return signMaterial; }
    public boolean isSignUseFrontSide()                     { return signUseFrontSide; }
    public int getSignStartYOffset()                        { return signStartYOffset; }
    public int getSignMaxYOffset()                          { return signMaxYOffset; }
    public int getSignFallbackYOffset()                     { return signFallbackYOffset; }
    public String getSignFixedWorld()                       { return signFixedWorld; }
    public int getSignFixedX()                              { return signFixedX; }
    public int getSignFixedY()                              { return signFixedY; }
    public int getSignFixedZ()                              { return signFixedZ; }
    public int getEditorOpenDelayTicks()                    { return editorOpenDelayTicks; }
    public boolean isCloseEditorOnTimeout()                 { return closeEditorOnTimeout; }

    public List<TranslationKeyEntry> getTranslationKeys()   { return Collections.unmodifiableList(translationKeys); }

    public boolean isBrandDetectionEnabled()                { return brandDetectionEnabled; }
    public boolean isChannelDetectionEnabled()              { return channelDetectionEnabled; }
    public List<BrandEntry> getBrandEntries()               { return Collections.unmodifiableList(brandEntries); }
    public List<ChannelEntry> getChannelEntries()           { return Collections.unmodifiableList(channelEntries); }

    public ActionConfig getConfirmedAction()                { return confirmedAction; }
    public ActionConfig getHeuristicAction()                { return heuristicAction; }
    public boolean isDeduplicatePerSession()                { return deduplicatePerSession; }

    public boolean isStrikesEnabled()                       { return strikesEnabled; }
    public int getStrikeThreshold()                         { return strikeThreshold; }
    public boolean isStrikeResetOnDisconnect()              { return strikeResetOnDisconnect; }
    public StrikeThresholdAction getStrikeThresholdAction() { return strikeThresholdAction; }

    public String getLogFilePath()                          { return logFilePath; }
    public int getLogMaxSizeMb()                            { return logMaxSizeMb; }
    public String getLogTimestampFormat()                   { return logTimestampFormat; }
    public boolean isLogIp()                                { return logIp; }
    public Confidence getMinLogConfidence()                 { return minLogConfidence; }

    public int getMaxBatchesPerCheck()                      { return maxBatchesPerCheck; }
    public boolean isSkipIfBlockOccupied()                  { return skipIfBlockOccupied; }
    public boolean isLogRawSignLines()                      { return logRawSignLines; }

    public Set<String> getExemptGamemodes()                 { return Collections.unmodifiableSet(exemptGamemodes); }
    public Set<String> getExemptWorlds()                    { return Collections.unmodifiableSet(exemptWorlds); }
}
