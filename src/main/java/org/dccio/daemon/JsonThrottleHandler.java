package org.dccio.daemon;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.dccio.core.ThrottleSession;

import java.io.IOException;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Handles throttle operations with automatic throttle management and client locking.
 * Clients POST to an address directly - throttles are created/retrieved automatically.
 * Only one client can control speed/direction per address at a time (2 second timeout).
 * Functions can be controlled by any client concurrently.
 */
public class JsonThrottleHandler implements JsonMessageHandler.TypeHandler {

    private final ThrottleService service;
    private JsonBroadcaster broadcaster;
    
    // Track which client has control of speed/direction for each address
    // Key: address string (e.g., "3" or "754:true"), Value: LockInfo
    private final Map<String, LockInfo> speedDirectionLocks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timeoutExecutor = Executors.newScheduledThreadPool(1);

    private static class LockInfo {
        final String clientId;
        volatile long lastUpdateTime;
        
        LockInfo(String clientId) {
            this.clientId = clientId;
            this.lastUpdateTime = System.currentTimeMillis();
        }
    }

    public JsonThrottleHandler(ThrottleService service) {
        this.service = service;
        // Clean up expired locks every 500ms
        timeoutExecutor.scheduleAtFixedRate(this::cleanupExpiredLocks, 500, 500, TimeUnit.MILLISECONDS);
    }

    public void setBroadcaster(JsonBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    public void shutdown() {
        timeoutExecutor.shutdown();
    }

    private void cleanupExpiredLocks() {
        long now = System.currentTimeMillis();
        speedDirectionLocks.entrySet().removeIf(entry -> {
            LockInfo lock = entry.getValue();
            return (now - lock.lastUpdateTime) > 2000; // 2 second timeout
        });
    }

    @Override
    public JsonObject handle(String method, JsonObject data) {
        return handle(method, data, null);
    }

    @Override
    public JsonObject handle(String method, JsonObject data, String clientId) {
        String actualClientId = clientId != null ? clientId : (data.has("clientId") ? data.get("clientId").getAsString() : "unknown");
        switch (method.toLowerCase(Locale.ROOT)) {
            case "list":
                return list();
            case "get":
                return get(data);
            case "post":
                return post(data, actualClientId);
            default:
                throw new IllegalArgumentException("Unsupported method '" + method + "'. Use GET or POST with address.");
        }
    }

    private JsonObject list() {
        JsonArray array = new JsonArray();
        for (ThrottleSession session : service.getThrottles()) {
            array.add(throttleInfo(session));
        }
        JsonObject response = new JsonObject();
        response.addProperty("type", "throttles");
        response.add("data", array);
        return response;
    }

    private JsonObject get(JsonObject data) {
        int address = requireInt(data, "address");
        boolean longAddress = data.has("longAddress") && data.get("longAddress").getAsBoolean();
        
        // Get or create throttle for this address
        String throttleId = getOrCreateThrottle(address, longAddress);
        ThrottleSession session = service.getThrottle(throttleId);
        if (session == null) {
            throw new IllegalStateException("Failed to get or create throttle for address " + address);
        }
        
        JsonObject response = new JsonObject();
        response.addProperty("type", "throttle");
        response.add("data", throttleInfo(session));
        return response;
    }

    private JsonObject post(JsonObject data, String clientId) {
        // Extract address
        int address = requireInt(data, "address");
        boolean longAddress = data.has("longAddress") && data.get("longAddress").getAsBoolean();
        
        // Get or create throttle for this address
        String throttleId = getOrCreateThrottle(address, longAddress);
        ThrottleSession session = service.getThrottle(throttleId);
        if (session == null) {
            throw new IllegalStateException("Failed to get throttle for address " + address);
        }

        String addressKey = addressKey(address, longAddress);
        boolean hasSpeedOrDirection = data.has("speed") || data.has("forward");
        boolean hasFunctions = hasFunctionChanges(data);

        // Check lock for speed/direction
        if (hasSpeedOrDirection) {
            LockInfo lock = speedDirectionLocks.get(addressKey);
            if (lock != null && !lock.clientId.equals(clientId)) {
                // Another client has control
                throw new IllegalStateException("Throttle busy: another client is controlling speed/direction for address " + address);
            }
            // Update or create lock
            if (lock == null) {
                speedDirectionLocks.put(addressKey, new LockInfo(clientId));
            } else {
                lock.lastUpdateTime = System.currentTimeMillis();
            }
        }

        boolean changed = false;
        Float newSpeed = null;
        Boolean newDirection = null;
        JsonObject functionsChanged = null;

        // Handle speed
        if (data.has("speed")) {
            float speed = data.get("speed").getAsFloat();
            validateSpeed(speed);
            try {
                session.setSpeed(speed);
                changed = true;
                newSpeed = speed;
            } catch (IOException e) {
                throw new IllegalArgumentException(e.getMessage(), e);
            }
        }

        // Handle direction
        if (data.has("forward")) {
            boolean forward = data.get("forward").getAsBoolean();
            try {
                session.setDirection(forward);
                changed = true;
                newDirection = forward;
            } catch (IOException e) {
                throw new IllegalArgumentException(e.getMessage(), e);
            }
        }

        // Handle functions (no locking required)
        // Support functions as an object: { "functions": { "0": true, "1": false, ... } }
        if (data.has("functions") && data.get("functions").isJsonObject()) {
            JsonObject functionsObj = data.getAsJsonObject("functions");
            for (String key : functionsObj.keySet()) {
                try {
                    int funcNum = Integer.parseInt(key);
                    if (funcNum < 0 || funcNum > 28) {
                        throw new IllegalArgumentException("Function number must be between 0 and 28");
                    }
                    boolean on = functionsObj.get(key).getAsBoolean();
                    session.setFunction(funcNum, on);
                    changed = true;
                    if (functionsChanged == null) {
                        functionsChanged = new JsonObject();
                    }
                    functionsChanged.addProperty(key, on);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid function key: " + key);
                } catch (IOException e) {
                    throw new IllegalArgumentException(e.getMessage(), e);
                }
            }
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "throttle");
        JsonObject payload = throttleInfo(session);
        if (changed) {
            payload.addProperty("updated", true);
        }
        response.add("data", payload);
        
        if (changed) {
            broadcastDelta(throttleId, session.getConnectionId(), address, longAddress, false, newSpeed, newDirection, functionsChanged, false, session);
        }
        
        return response;
    }

    private String getOrCreateThrottle(int address, boolean longAddress) {
        // Try to find existing throttle for this address
        for (ThrottleSession session : service.getThrottles()) {
            if (session.getAddress() == address && session.isLongAddress() == longAddress) {
                return sessionId(session);
            }
        }
        // Create new throttle
        try {
            return service.openThrottle(null, address, longAddress);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to open throttle: " + e.getMessage(), e);
        }
    }

    private String addressKey(int address, boolean longAddress) {
        return address + ":" + longAddress;
    }

    private boolean hasFunctionChanges(JsonObject data) {
        return data.has("functions") && data.get("functions").isJsonObject();
    }

    private JsonObject throttleInfo(ThrottleSession session) {
        JsonObject obj = new JsonObject();
        obj.addProperty("throttle", sessionId(session));
        obj.addProperty("connectionId", session.getConnectionId());
        obj.addProperty("address", session.getAddress());
        obj.addProperty("longAddress", session.isLongAddress());
        obj.addProperty("speed", session.getSpeed());
        obj.addProperty("forward", session.getDirection());
        // Add all function states as an object: { "0": true, "1": false, ... }
        JsonObject functions = new JsonObject();
        for (int i = 0; i <= 28; i++) {
            functions.addProperty(String.valueOf(i), session.getFunction(i));
        }
        obj.add("functions", functions);
        return obj;
    }

    private String sessionId(ThrottleSession session) {
        return session.getConnectionId() + ":" + session.getAddress() + ":" + session.isLongAddress();
    }

    private int requireInt(JsonObject data, String field) {
        if (!data.has(field)) {
            throw new IllegalArgumentException("Field '" + field + "' is required");
        }
        return data.get(field).getAsInt();
    }

    private void validateSpeed(float speed) {
        if (speed < 0 || speed > 1) {
            throw new IllegalArgumentException("Speed must be between 0.0 and 1.0");
        }
    }

    private void broadcastDelta(String throttleId,
                                String connectionId,
                                int address,
                                boolean longAddress,
                                boolean opened,
                                Float speed,
                                Boolean forward,
                                JsonObject functionsChanged,
                                boolean released,
                                ThrottleSession session) {
        if (broadcaster == null) {
            return;
        }
        JsonObject delta = new JsonObject();
        delta.addProperty("type", "throttle");
        delta.addProperty("method", "patch");
        JsonObject deltaData = new JsonObject();
        deltaData.addProperty("throttle", throttleId);
        deltaData.addProperty("address", address);
        deltaData.addProperty("longAddress", longAddress);
        if (opened) {
            deltaData.addProperty("opened", true);
        }
        if (released) {
            deltaData.addProperty("released", true);
        }
        if (speed != null) {
            deltaData.addProperty("speed", speed);
            // Always include direction when speed is included
            if (session != null) {
                deltaData.addProperty("forward", session.getDirection());
            } else if (forward != null) {
                deltaData.addProperty("forward", forward);
            }
        } else if (forward != null) {
            deltaData.addProperty("forward", forward);
        }
        if (functionsChanged != null && functionsChanged.size() > 0) {
            // Add functions as an object: { "0": true, "1": false, ... }
            deltaData.add("functions", functionsChanged);
        }
        delta.add("data", deltaData);
        broadcaster.broadcast(delta);
    }

    public interface ThrottleService {
        String openThrottle(String connectionId, int address, boolean longAddress) throws IOException;
        ThrottleSession getThrottle(String throttleId);
        Collection<ThrottleSession> getThrottles();
        void closeThrottle(String throttleId);
    }
}
