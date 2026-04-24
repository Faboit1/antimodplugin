package dev.antimod;

import dev.antimod.alert.AlertManager;
import dev.antimod.check.ChannelRegistrationCheck;
import dev.antimod.check.ClientBrandCheck;
import dev.antimod.check.SignTranslationCheck;
import dev.antimod.command.AntiModCommand;
import dev.antimod.config.ConfigManager;
import dev.antimod.detection.DetectionManager;
import dev.antimod.listener.AntiModPacketListener;
import dev.antimod.listener.PlayerJoinListener;
import dev.antimod.strike.StrikeManager;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Main entry point for AntiModDetect.
 *
 * <p>Initialises all subsystems on enable and tears them down cleanly
 * on disable:
 * <ul>
 *   <li>{@link ConfigManager}        – typed config access</li>
 *   <li>{@link AlertManager}         – staff alerts + file logging</li>
 *   <li>{@link StrikeManager}        – per-player strike counter</li>
 *   <li>{@link SignTranslationCheck} – sign-based translation-key detection</li>
 *   <li>{@link ClientBrandCheck}     – client brand string detection</li>
 *   <li>{@link ChannelRegistrationCheck} – plugin channel detection</li>
 *   <li>{@link DetectionManager}     – dispatches results to actions</li>
 *   <li>{@link PlayerJoinListener}   – ties everything together on join</li>
 *   <li>{@link AntiModPacketListener}– ProtocolLib deep-packet listener (optional)</li>
 *   <li>{@link AntiModCommand}       – /amd command handler</li>
 * </ul>
 */
public final class AntiModDetect extends JavaPlugin {

    // ── Subsystems ───────────────────────────────────────────────────────
    private ConfigManager configManager;
    private AlertManager alertManager;
    private StrikeManager strikeManager;
    private SignTranslationCheck signCheck;
    private ClientBrandCheck brandCheck;
    private ChannelRegistrationCheck channelCheck;
    private DetectionManager detectionManager;
    private PlayerJoinListener joinListener;

    // ====================================================================
    //  Enable
    // ====================================================================

    @Override
    public void onEnable() {
        // 1. Config
        configManager = new ConfigManager(this);

        // 2. Alert manager (staff chat + log file)
        alertManager = new AlertManager(this, configManager);

        // 3. Strike tracker
        strikeManager = new StrikeManager(this);

        // 4. Check classes
        signCheck    = new SignTranslationCheck(this, configManager);
        brandCheck   = new ClientBrandCheck(configManager);
        channelCheck = new ChannelRegistrationCheck(configManager);

        // 5. Central detection dispatcher
        detectionManager = new DetectionManager(this, configManager, alertManager, strikeManager);

        // 6. Main event listener (join, quit, sign change, plugin messages)
        joinListener = new PlayerJoinListener(
                this, configManager,
                signCheck, brandCheck, channelCheck,
                detectionManager);
        getServer().getPluginManager().registerEvents(joinListener, this);

        // 7. Register Bukkit Messenger for brand + channel (fallback path
        //    when ProtocolLib is not installed)
        registerBukkitMessenger();

        // 8. ProtocolLib deep-packet listener (if available)
        if (getServer().getPluginManager().getPlugin("ProtocolLib") != null) {
            try {
                com.comphenix.protocol.ProtocolManager pm =
                        com.comphenix.protocol.ProtocolLibrary.getProtocolManager();
                new AntiModPacketListener(this, configManager,
                        brandCheck, channelCheck, pm,
                        detectionManager::handle);
                getLogger().info("ProtocolLib found – deep packet detection enabled.");
            } catch (Exception e) {
                getLogger().warning("ProtocolLib hook failed: " + e.getMessage()
                        + " – falling back to Bukkit Messenger.");
            }
        } else {
            getLogger().warning(
                    "ProtocolLib not found – brand and channel detection running in "
                  + "Bukkit Messenger fallback mode. Install ProtocolLib for full coverage.");
        }

        // 9. Register command
        AntiModCommand commandHandler = new AntiModCommand(
                this, configManager, strikeManager, detectionManager, joinListener, signCheck);
        PluginCommand cmd = getCommand("antimoddetect");
        if (cmd != null) {
            cmd.setExecutor(commandHandler);
            cmd.setTabCompleter(commandHandler);
        }

        getLogger().info("AntiModDetect v" + getDescription().getVersion() + " enabled. "
                + "Keys: " + configManager.getTranslationKeys().size()
                + "  Brands: " + configManager.getBrandEntries().size()
                + "  Channels: " + configManager.getChannelEntries().size());
    }

    // ====================================================================
    //  Disable
    // ====================================================================

    @Override
    public void onDisable() {
        // Clean up any active sign check sessions (restores placed blocks)
        if (signCheck != null) {
            getServer().getOnlinePlayers()
                    .forEach(p -> signCheck.cleanupSession(p.getUniqueId()));
        }

        // Unregister Bukkit Messenger channels
        try {
            getServer().getMessenger()
                    .unregisterIncomingPluginChannel(this, "minecraft:brand");
            getServer().getMessenger()
                    .unregisterIncomingPluginChannel(this, "minecraft:register");
        } catch (Exception ignored) {}

        getLogger().info("AntiModDetect disabled.");
    }

    // ====================================================================
    //  Bukkit Messenger registration
    // ====================================================================

    /**
     * Registers the vanilla plugin channels we need to sniff.
     *
     * <p>In some Bukkit/Paper builds, registering {@code minecraft:}
     * namespaced incoming channels is allowed for listening (read-only).
     * If the server rejects them, the ProtocolLib listener (if available)
     * will cover these channels anyway.
     */
    private void registerBukkitMessenger() {
        try {
            getServer().getMessenger()
                    .registerIncomingPluginChannel(this, "minecraft:brand", joinListener);
            getServer().getMessenger()
                    .registerIncomingPluginChannel(this, "minecraft:register", joinListener);
        } catch (Exception e) {
            getLogger().fine(
                    "Could not register Bukkit Messenger for minecraft: channels "
                  + "(this is expected on some builds): " + e.getMessage());
        }
    }

    // ====================================================================
    //  Accessors (used by command handler)
    // ====================================================================

    public ConfigManager getConfigManager() { return configManager; }
    public AlertManager  getAlertManager()  { return alertManager; }
    public StrikeManager getStrikeManager() { return strikeManager; }
}
