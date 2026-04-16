package dev.antimod.listener;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.*;
import com.comphenix.protocol.reflect.StructureModifier;
import dev.antimod.check.ClientBrandCheck;
import dev.antimod.check.ChannelRegistrationCheck;
import dev.antimod.config.ConfigManager;
import dev.antimod.detection.DetectionResult;
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
    private final ProtocolManager protocolManager;
    private final Logger log;

    /** Callback to forward detections to DetectionManager. */
    private final java.util.function.Consumer<DetectionResult> resultSink;

    public AntiModPacketListener(JavaPlugin plugin,
                                 ConfigManager config,
                                 ClientBrandCheck brandCheck,
                                 ChannelRegistrationCheck channelCheck,
                                 ProtocolManager protocolManager,
                                 java.util.function.Consumer<DetectionResult> resultSink) {
        this.plugin           = plugin;
        this.config           = config;
        this.brandCheck       = brandCheck;
        this.channelCheck     = channelCheck;
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
