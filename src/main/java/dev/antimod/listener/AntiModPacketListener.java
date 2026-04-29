package dev.antimod.listener;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.*;
import com.comphenix.protocol.reflect.StructureModifier;
import dev.antimod.check.ClientBrandCheck;
import dev.antimod.check.ChannelRegistrationCheck;
import dev.antimod.check.SignTranslationCheck;
import dev.antimod.config.ConfigManager;
import dev.antimod.detection.DetectionResult;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

/**
 * ProtocolLib-based packet listener for deep brand and channel detection.
 *
 * <p>This class is only instantiated when ProtocolLib is present on the
 * server. It intercepts the raw {@code CUSTOM_PAYLOAD} (C→S) packet to
 * extract the client brand and registered channel list <em>before</em>
 * Bukkit's plugin messaging system processes them.
 *
 * <p>When ProtocolLib is absent, the same data is captured by
 * {@link PlayerJoinListener#onPluginMessageReceived} (Bukkit Messenger
 * fallback).
 *
 * <p><b>Why ProtocolLib here?</b><br>
 * Bukkit's Messenger API may filter or drop {@code minecraft:} namespaced
 * channels on some server implementations. ProtocolLib works at the Netty
 * pipeline level, guaranteeing we always see the packets.
 */
public final class AntiModPacketListener {

    private final JavaPlugin plugin;
    private final ConfigManager config;
    private final ClientBrandCheck brandCheck;
    private final ChannelRegistrationCheck channelCheck;
    private final SignTranslationCheck signCheck;
    private final ProtocolManager protocolManager;
    private final Logger log;

    /** Callback to forward detections to DetectionManager. */
    private final java.util.function.Consumer<DetectionResult> resultSink;

    public AntiModPacketListener(JavaPlugin plugin,
                                 ConfigManager config,
                                 ClientBrandCheck brandCheck,
                                 ChannelRegistrationCheck channelCheck,
                                 SignTranslationCheck signCheck,
                                 ProtocolManager protocolManager,
                                 java.util.function.Consumer<DetectionResult> resultSink) {
        this.plugin           = plugin;
        this.config           = config;
        this.brandCheck       = brandCheck;
        this.channelCheck     = channelCheck;
        this.signCheck        = signCheck;
        this.protocolManager  = protocolManager;
        this.resultSink       = resultSink;
        this.log              = plugin.getLogger();

        registerListeners();
    }

    private void registerListeners() {
        // Listen for the client custom payload packet (C→S)
        // This carries both minecraft:brand and minecraft:register messages.
        protocolManager.addPacketListener(new PacketAdapter(
                plugin,
                ListenerPriority.NORMAL,
                PacketType.Play.Client.CUSTOM_PAYLOAD) {

            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (event.isCancelled()) return;
                handleCustomPayload(event);
            }
        });

        // Intercept the sign-update packet (C→S) sent when a sign editor closes.
        // This is fired by the client whether the editor was closed by the player
        // manually or force-closed by our AIR block-change trick.
        // Intercepting here lets us:
        //  1. Process sign translation detections without waiting for SignChangeEvent.
        //  2. Cancel the packet so no SignChangeEvent fires and no world modification
        //     is attempted on the temporary sign block.
        // This path is only active when ProtocolLib is installed; the plugin falls
        // back to SignChangeEvent (PlayerJoinListener#onSignChange) without it.
        protocolManager.addPacketListener(new PacketAdapter(
                plugin,
                ListenerPriority.LOWEST,
                PacketType.Play.Client.UPDATE_SIGN) {

            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (event.isCancelled()) return;
                handleSignUpdate(event);
            }
        });
    }

    private void handleCustomPayload(PacketEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;
        if (player.hasPermission(config.getBypassPermission())) return;
        if (config.isWhitelisted(player.getName())
                || config.isWhitelisted(player.getUniqueId().toString())) return;
        if (config.getExemptGamemodes().contains(player.getGameMode().name())) return;
        if (config.getExemptWorlds().contains(player.getWorld().getName())) return;

        PacketContainer packet = event.getPacket();
        String ip = player.getAddress() != null
                ? player.getAddress().getAddress().getHostAddress()
                : "unknown";

        try {
            // In 1.21.x, field 0 of CUSTOM_PAYLOAD is the channel name (MinecraftKey)
            // We use ProtocolLib's MinecraftKey wrapper.
            String channel = readChannelName(packet);
            if (channel == null || channel.isBlank()) return;

            if (config.isDebug()) {
                log.info("[AMD-DEBUG][PL] Custom payload channel from "
                        + player.getName() + ": " + channel);
            }

            List<DetectionResult> results = new ArrayList<>();

            if (channel.equals("minecraft:brand")) {
                byte[] payload = readPayloadBytes(packet);
                String brand = ClientBrandCheck.parseVarIntPrefixedString(payload);
                if (config.isDebug()) {
                    log.info("[AMD-DEBUG][PL] Brand from "
                            + player.getName() + ": '" + brand + "'");
                }
                brandCheck.check(player.getUniqueId(), player.getName(),
                        ip, brand, results);

            } else if (channel.equals("minecraft:register")) {
                byte[] payload = readPayloadBytes(packet);
                String[] channels = ChannelRegistrationCheck.parseChannelList(payload);
                if (config.isDebug()) {
                    log.info("[AMD-DEBUG][PL] Channels from "
                            + player.getName() + ": " + Arrays.toString(channels));
                }
                channelCheck.check(player.getUniqueId(), player.getName(),
                        ip, channels, results);
            }

            results.forEach(resultSink);

        } catch (Exception e) {
            if (config.isDebug()) {
                log.warning("[AMD-DEBUG][PL] Error reading custom payload from "
                        + player.getName() + ": " + e.getMessage());
            }
        }
    }

    // ====================================================================
    //  Sign update handler
    // ====================================================================

    /**
     * Handles the {@code UPDATE_SIGN} (C→S) packet sent when the client
     * closes a sign editor (whether by pressing Done or by the force-close
     * AIR block-change we send).
     *
     * <p>If the sign position matches an active sign-translation session for
     * this player, the 4 rendered lines are forwarded to
     * {@link SignTranslationCheck#handleSignChange} for detection evaluation.
     * The packet is then cancelled so that {@link org.bukkit.event.block.SignChangeEvent}
     * never fires and no world modification is attempted on the temporary sign
     * block.
     *
     * <p>Minecraft 1.21 sign update packet layout (C→S):
     * <pre>
     *   BlockPosition  – position of the sign
     *   boolean        – true = front side, false = back
     *   String[4]      – the four rendered line strings (plain text)
     * </pre>
     */
    private void handleSignUpdate(PacketEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;

        PacketContainer packet = event.getPacket();

        // Read the sign position
        com.comphenix.protocol.wrappers.BlockPosition pos;
        try {
            pos = packet.getBlockPositionModifier().read(0);
        } catch (Exception e) {
            if (config.isDebug()) {
                log.fine("[AMD-DEBUG][PL] Could not read UPDATE_SIGN position from "
                        + player.getName() + ": " + e.getMessage());
            }
            return;
        }

        Location loc = new Location(player.getWorld(), pos.getX(), pos.getY(), pos.getZ());

        // Only handle packets that belong to an active sign check session
        if (!signCheck.isTestSign(player, loc)) return;

        if (config.isDebug()) {
            log.info("[AMD-DEBUG][PL] UPDATE_SIGN intercepted from " + player.getName()
                    + " at " + pos.getX() + "," + pos.getY() + "," + pos.getZ());
        }

        // Read the 4 rendered line strings.
        // In Minecraft 1.21 the lines are individual String fields (not an array).
        // The boolean "front side" field comes before the strings in the packet;
        // ProtocolLib's getStrings() returns only the String-typed fields, so
        // indices 0-3 correspond to lines 1-4.
        Component[] lines = new Component[4];
        try {
            StructureModifier<String> strings = packet.getStrings();
            if (config.isDebug() && strings.size() != 4) {
                log.warning("[AMD-DEBUG][PL] UPDATE_SIGN from " + player.getName()
                        + " had unexpected string field count: " + strings.size()
                        + " (expected 4) – packet structure may differ from MC 1.21");
            }
            for (int i = 0; i < 4; i++) {
                String raw = (i < strings.size()) ? strings.read(i) : "";
                // The client sends the rendered (post-translation) plain text.
                // Wrap as a plain text component so handleSignChange can serialise it.
                lines[i] = Component.text(raw != null ? raw : "");
            }
        } catch (Exception e) {
            if (config.isDebug()) {
                log.warning("[AMD-DEBUG][PL] Failed to read UPDATE_SIGN lines from "
                        + player.getName() + ": "
                        + e.getClass().getSimpleName() + " – " + e.getMessage());
            }
            return;
        }

        // Forward to sign check logic (same processing path as SignChangeEvent)
        boolean consumed = signCheck.handleSignChange(player, lines, resultSink);

        if (consumed) {
            // Prevent SignChangeEvent from firing and prevent world modification.
            event.setCancelled(true);
        }
    }

    // ====================================================================
    //  Packet field accessors
    // ====================================================================

    /**
     * Reads the channel name from a CUSTOM_PAYLOAD packet.
     *
     * <p>ProtocolLib represents the MinecraftKey (ResourceLocation) as a
     * {@code MinecraftKey} object in field index 0. We use
     * {@link StructureModifier} to read it safely.
     */
    private String readChannelName(PacketContainer packet) {
        try {
            // Try MinecraftKey modifier first (ProtocolLib 5.x+)
            var keys = packet.getMinecraftKeys();
            if (keys.size() > 0) {
                var key = keys.read(0);
                if (key != null) return key.getFullKey(); // returns "namespace:key"
            }
        } catch (Exception e) {
            // MinecraftKey modifier not available in this ProtocolLib version –
            // fall through to the string modifier fallback below.
            if (config.isDebug()) log.fine("[AMD-DEBUG][PL] MinecraftKey read failed: " + e.getMessage());
        }

        try {
            // Fallback: string modifier
            var strings = packet.getStrings();
            if (strings.size() > 0) return strings.read(0);
        } catch (Exception e) {
            // No string representation available either – packet layout differs.
            if (config.isDebug()) log.fine("[AMD-DEBUG][PL] String channel read failed: " + e.getMessage());
        }

        return null;
    }

    /**
     * Reads the raw payload bytes from a CUSTOM_PAYLOAD packet.
     *
     * <p>ProtocolLib exposes the payload as a byte array or via a
     * {@code ByteBuf} wrapper depending on version. We try the byte-array
     * modifier first, then fall back to a ByteBuf drain.
     */
    private byte[] readPayloadBytes(PacketContainer packet) {
        try {
            var byteArrays = packet.getByteArrays();
            if (byteArrays.size() > 0) {
                byte[] arr = byteArrays.read(0);
                if (arr != null) return arr;
            }
        } catch (Exception e) {
            // ByteArray modifier not available – try fallback below.
            if (config.isDebug()) log.fine("[AMD-DEBUG][PL] ByteArray read failed: " + e.getMessage());
        }

        try {
            var bytes = packet.getBytes();
            if (bytes.size() > 0) {
                // Single-byte path (unlikely but handle gracefully)
                return new byte[]{ bytes.read(0) };
            }
        } catch (Exception e) {
            if (config.isDebug()) log.fine("[AMD-DEBUG][PL] Byte read failed: " + e.getMessage());
        }

        return new byte[0];
    }
}
