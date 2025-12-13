package org.dccio.daemon;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket front-end that delegates JSON messages to {@link JsonMessageHandler}.
 */
public class JsonWebSocketHandler extends WebSocketServer {

    private final String path;
    private final JsonMessageHandler messageHandler;
    private final Gson gson = new Gson();
    private final Set<WebSocket> connections = ConcurrentHashMap.newKeySet();

    public JsonWebSocketHandler(int port, String path, JsonMessageHandler messageHandler) {
        super(new InetSocketAddress(port));
        this.path = path == null ? "/json" : path;
        this.messageHandler = messageHandler;
        setReuseAddr(true);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String resource = handshake.getResourceDescriptor();
        if (!resource.equals(path) && !resource.startsWith(path + "?")) {
            conn.close(1008, "Invalid path");
            return;
        }
        connections.add(conn);
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        connections.remove(conn);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            // Parse message and add clientId (use connection's remote address as identifier)
            JsonObject messageObj = gson.fromJson(message, JsonObject.class);
            String clientId = conn.getRemoteSocketAddress().toString() + "-" + conn.hashCode();
            messageObj.addProperty("clientId", clientId);
            
            JsonObject response = messageHandler.handle(messageObj);
            conn.send(gson.toJson(response));
        } catch (com.google.gson.JsonSyntaxException e) {
            // If JSON parsing fails, let the message handler deal with it
            JsonObject response = messageHandler.handle(message);
            conn.send(gson.toJson(response));
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("WebSocket error: " + ex.getMessage());
    }

    @Override
    public void onStart() {
        setConnectionLostTimeout(60);
    }

    public JsonBroadcaster getBroadcaster() {
        return msg -> {
            String json = gson.toJson(msg);
            for (WebSocket socket : connections) {
                if (socket.isOpen()) {
                    socket.send(json);
                }
            }
        };
    }

    public void shutdown() {
        try {
            stop(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

