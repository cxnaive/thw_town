package cn.lunadeer.validation;

import com.google.gson.*;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.*;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftCodec;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.*;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetActionBarTextPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** A real offline 26.2 client controlled with JSON lines; no event simulation or translation. */
public final class NativeClient {
    private static final Gson JSON = new Gson();
    private static volatile boolean spawned;
    private static volatile double x, y, z;
    private static volatile float yaw, pitch;
    private static volatile int window, revision;
    private static int sequence;
    private static synchronized void emit(String type, Object value) {
        System.out.println(JSON.toJson(Map.of("type", type, "value", value)));
        System.out.flush();
    }
    private static String component(net.kyori.adventure.text.Component value) {
        return GsonComponentSerializer.gson().serialize(value);
    }
    public static void main(String[] args) throws Exception {
        var codec = MinecraftCodec.CODEC;
        if (codec.getProtocolVersion() != 776 || !codec.getMinecraftVersion().equals("26.2")) throw new IllegalStateException("Expected native 26.2 codec");
        emit("codec", Map.of("version", codec.getMinecraftVersion(), "protocol", codec.getProtocolVersion()));
        var session = ClientNetworkSessionFactory.factory().setAddress("127.0.0.1", Integer.parseInt(args[0]))
                .setProtocol(new MinecraftProtocol(args[1])).create();
        session.addListener(new SessionAdapter() {
            @Override public void packetReceived(Session connection, Packet packet) {
                if (packet instanceof ClientboundPlayerPositionPacket p) {
                    x = p.getPosition().getX() + (p.getRelatives().contains(PositionElement.X) ? x : 0);
                    y = p.getPosition().getY() + (p.getRelatives().contains(PositionElement.Y) ? y : 0);
                    z = p.getPosition().getZ() + (p.getRelatives().contains(PositionElement.Z) ? z : 0);
                    yaw = p.getYRot(); pitch = p.getXRot();
                    connection.send(new ServerboundAcceptTeleportationPacket(p.getId()));
                    connection.send(new ServerboundMovePlayerPosRotPacket(true, false, x, y, z, yaw, pitch));
                    connection.send(ServerboundPlayerLoadedPacket.INSTANCE);
                    spawned = true;
                    emit("position", Map.of("x", x, "y", y, "z", z));
                } else if (packet instanceof ClientboundLoginPacket p) {
                    emit("dimension", p.getCommonPlayerSpawnInfo().getWorldName().asString());
                } else if (packet instanceof ClientboundRespawnPacket p) {
                    spawned = false;
                    emit("dimension", p.getCommonPlayerSpawnInfo().getWorldName().asString());
                } else if (packet instanceof ClientboundSystemChatPacket p) {
                    emit("chat", component(p.getContent()));
                } else if (packet instanceof ClientboundSetActionBarTextPacket p) {
                    emit("chat", component(p.getText()));
                } else if (packet instanceof ClientboundOpenScreenPacket p) {
                    window = p.getContainerId(); revision = 0;
                    emit("window", Map.of("id", window, "title", component(p.getTitle()), "kind", p.getType().name()));
                } else if (packet instanceof ClientboundContainerSetContentPacket p) {
                    if (p.getContainerId() == window) revision = p.getStateId();
                    emit("content", Map.of("id", p.getContainerId(), "revision", p.getStateId(), "items", Arrays.stream(p.getItems()).map(i -> i == null ? "" : i.toString()).toList()));
                } else if (packet instanceof ClientboundContainerSetSlotPacket p) {
                    if (p.getContainerId() == window) revision = p.getStateId();
                    emit("slot", Map.of("id", p.getContainerId(), "slot", p.getSlot(), "item", p.getItem() == null ? "" : p.getItem().toString()));
                } else if (packet instanceof ClientboundContainerClosePacket) {
                    window = 0; emit("close", true);
                } else if (packet instanceof ClientboundAddEntityPacket p) {
                    emit("entity", Map.of("id", p.getEntityId(), "kind", p.getType().name(), "x", p.getX(), "y", p.getY(), "z", p.getZ()));
                } else if (packet instanceof ClientboundSetEntityDataPacket p) {
                    emit("metadata", Map.of("id", p.getEntityId(), "data", Arrays.toString(p.getMetadata())));
                }
            }
            @Override public void disconnected(DisconnectedEvent event) {
                spawned = false;
                emit("disconnect", Map.of("reason", component(event.getReason()), "cause", String.valueOf(event.getCause())));
            }
            @Override public void packetError(PacketErrorEvent event) {
                emit("error", event.getCause().toString());
                event.getCause().printStackTrace(System.err);
            }
        });
        var ticker = Executors.newSingleThreadScheduledExecutor();
        ticker.scheduleAtFixedRate(() -> {
            if (spawned && session.isConnected()) {
                session.send(new ServerboundMovePlayerPosRotPacket(true, false, x, y, z, yaw, pitch));
                session.send(ServerboundClientTickEndPacket.INSTANCE);
            }
        }, 50, 50, TimeUnit.MILLISECONDS);
        session.connect(false);
        try (var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonObject command = JsonParser.parseString(line).getAsJsonObject();
                String op = command.get("op").getAsString();
                switch (op) {
                    case "command" -> session.send(new ServerboundChatCommandPacket(command.get("command").getAsString().replaceFirst("^/", "")));
                    case "close" -> { if (window != 0) session.send(new ServerboundContainerClosePacket(window)); window = 0; emit("close", true); }
                    case "click" -> session.send(new ServerboundContainerClickPacket(window, revision, command.get("slot").getAsInt(), ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK, null, Map.of()));
                    case "block" -> session.send(new ServerboundUseItemOnPacket(Vector3i.from(command.get("x").getAsInt(), command.get("y").getAsInt(), command.get("z").getAsInt()), Direction.valueOf(command.has("face") ? command.get("face").getAsString() : "UP"), Hand.MAIN_HAND, .5f, .5f, .5f, false, false, ++sequence));
                    case "break" -> session.send(new ServerboundPlayerActionPacket(PlayerAction.START_DIGGING, Vector3i.from(command.get("x").getAsInt(), command.get("y").getAsInt(), command.get("z").getAsInt()), Direction.UP, ++sequence));
                    case "interact" -> session.send(new ServerboundInteractPacket(command.get("entity").getAsInt(), Vector3d.from(0, .5, 0), false));
                    case "attack" -> session.send(new ServerboundAttackPacket(command.get("entity").getAsInt()));
                    case "held" -> session.send(new ServerboundSetCarriedItemPacket(command.get("slot").getAsInt()));
                    case "quit" -> { return; }
                    default -> throw new IllegalArgumentException(op);
                }
                emit("ack", command.get("id").getAsInt());
            }
        } finally {
            ticker.shutdownNow(); session.disconnect("Validation complete");
        }
    }
}
