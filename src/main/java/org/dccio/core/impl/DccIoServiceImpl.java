package org.dccio.core.impl;

import org.dccio.core.CommandStationConnection;
import org.dccio.core.ControllerRole;
import org.dccio.core.DccIoService;
import org.dccio.core.DeviceDiscoveryService;
import org.dccio.core.SystemConfig;
import org.dccio.core.ThrottleSession;
import org.dccio.core.events.DccEventBus;
import org.dccio.core.impl.dccpp.DccppConnection;
import org.dccio.core.impl.nce.NceSerialConnection;
import org.dccio.core.impl.nce.NceUsbConnection;
import org.dccio.core.impl.xnet.elite.XNetEliteConnection;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * In-memory implementation of {@link DccIoService} that manages JMRI-backed
 * connections and shares a single {@link DccEventBus} for outbound events.
 */
public final class DccIoServiceImpl implements DccIoService {

    private final Map<String, CommandStationConnection> connections = new ConcurrentHashMap<>();
    private final Map<String, ThrottleSession> throttles = new ConcurrentHashMap<>();
    private final DccEventBus eventBus = new DccEventBus();
    private final DeviceDiscoveryService discoveryService = new DeviceDiscoveryService();
    
    // Track which controllers are assigned to which roles
    private volatile String throttleControllerId = null;  // Controller ID for throttles
    private volatile String accessoryControllerId = null; // Controller ID for accessories
    
    // Track connected device ports to detect new devices
    private final Set<String> connectedPorts = ConcurrentHashMap.newKeySet();
    
    // Background thread for continuous device monitoring
    private final ScheduledExecutorService deviceMonitor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "DeviceMonitor");
        t.setDaemon(true);
        return t;
    });
    
    /**
     * Start continuous device monitoring and auto-connect.
     */
    public void startDeviceMonitoring() {
        // Initial scan
        autoConnectDevices();
        
        // Schedule periodic scans (every 5 seconds)
        deviceMonitor.scheduleWithFixedDelay(() -> {
            try {
                autoConnectDevices();
            } catch (Exception e) {
                System.err.println("Error in device monitoring: " + e.getMessage());
            }
        }, 5, 5, TimeUnit.SECONDS);
    }
    
    /**
     * Auto-discover and connect to detected controllers.
     * This will scan for devices and automatically create connections for any detected controllers.
     */
    private void autoConnectDevices() {
        java.util.List<DeviceDiscoveryService.DetectedDevice> devices = discoveryService.discoverDevices();
        for (DeviceDiscoveryService.DetectedDevice device : devices) {
            // Skip if already connected
            if (connectedPorts.contains(device.port)) {
                continue;
            }
            
            // Generate a connection ID from device name and port
            String connectionId = device.systemType.replace("-", "") + "_" + device.port.replace("/", "_").replace("\\", "_");
            
            // Skip if connection already exists
            if (connections.containsKey(connectionId)) {
                connectedPorts.add(device.port);
                continue;
            }
            
            try {
                // Build SystemConfig with device settings
                SystemConfig.Builder builder = SystemConfig.builder(connectionId, device.systemType)
                        .userName(device.name)
                        .systemPrefix(connectionId);
                
                // Add port
                builder.option("portName", device.port);
                
                // Add config options from device config
                for (Map.Entry<String, String> entry : device.config.entrySet()) {
                    builder.option(entry.getKey(), entry.getValue());
                }
                
                SystemConfig config = builder.build();
                CommandStationConnection conn = createConnection(config);
                
                // Auto-connect
                conn.connect();
                connectedPorts.add(device.port);
                System.out.println("Auto-connected to " + device.name + " on " + device.port);
                
                // Auto-assign roles if not already assigned
                assignControllerRoles(connectionId);
                
            } catch (Exception e) {
                System.err.println("Failed to auto-connect to " + device.name + " on " + device.port + ": " + e.getMessage());
            }
        }
    }
    
    /**
     * Auto-assign controller roles based on current assignments.
     * If only one controller, assign it to both roles.
     * If multiple controllers, assign first detected to both roles until user configures.
     */
    private void assignControllerRoles(String connectionId) {
        synchronized (this) {
            // If this is the first/only controller, assign it to both roles
            if (throttleControllerId == null && accessoryControllerId == null) {
                throttleControllerId = connectionId;
                accessoryControllerId = connectionId;
                System.out.println("Assigned " + connectionId + " to both throttles and accessories (only controller)");
            } else if (throttleControllerId == null) {
                // No throttle controller yet, but we have an accessory controller
                throttleControllerId = connectionId;
                System.out.println("Assigned " + connectionId + " to throttles");
            } else if (accessoryControllerId == null) {
                // No accessory controller yet, but we have a throttle controller
                accessoryControllerId = connectionId;
                System.out.println("Assigned " + connectionId + " to accessories");
            }
            // If both are already assigned, don't auto-assign (user must configure)
        }
    }
    
    /**
     * Set controller role assignment.
     */
    public void setControllerRole(String connectionId, ControllerRole role, boolean enabled) {
        synchronized (this) {
            if (enabled) {
                // Check if another controller already has this role
                if (role == ControllerRole.THROTTLES && throttleControllerId != null && !throttleControllerId.equals(connectionId)) {
                    throw new IllegalArgumentException("Another controller (" + throttleControllerId + ") is already assigned to throttles");
                }
                if (role == ControllerRole.ACCESSORIES && accessoryControllerId != null && !accessoryControllerId.equals(connectionId)) {
                    throw new IllegalArgumentException("Another controller (" + accessoryControllerId + ") is already assigned to accessories");
                }
                
                if (role == ControllerRole.THROTTLES) {
                    throttleControllerId = connectionId;
                } else {
                    accessoryControllerId = connectionId;
                }
            } else {
                // Disable role
                if (role == ControllerRole.THROTTLES && throttleControllerId != null && throttleControllerId.equals(connectionId)) {
                    throttleControllerId = null;
                }
                if (role == ControllerRole.ACCESSORIES && accessoryControllerId != null && accessoryControllerId.equals(connectionId)) {
                    accessoryControllerId = null;
                }
            }
        }
    }
    
    /**
     * Get controller ID for throttles.
     */
    public String getThrottleControllerId() {
        return throttleControllerId;
    }
    
    /**
     * Get controller ID for accessories.
     */
    public String getAccessoryControllerId() {
        return accessoryControllerId;
    }
    
    /**
     * Get controller for throttles (or first available if none assigned).
     */
    public CommandStationConnection getThrottleController() {
        if (throttleControllerId != null) {
            CommandStationConnection conn = connections.get(throttleControllerId);
            if (conn != null && conn.isConnected()) {
                return conn;
            }
        }
        // Fallback to first connected controller
        for (CommandStationConnection conn : connections.values()) {
            if (conn.isConnected()) {
                return conn;
            }
        }
        return null;
    }
    
    /**
     * Get controller for accessories (or first available if none assigned).
     */
    public CommandStationConnection getAccessoryController() {
        if (accessoryControllerId != null) {
            CommandStationConnection conn = connections.get(accessoryControllerId);
            if (conn != null && conn.isConnected()) {
                return conn;
            }
        }
        // Fallback to first connected controller
        for (CommandStationConnection conn : connections.values()) {
            if (conn.isConnected()) {
                return conn;
            }
        }
        return null;
    }
    
    public DeviceDiscoveryService getDiscoveryService() {
        return discoveryService;
    }

    @Override
    public CommandStationConnection createConnection(SystemConfig config) {
        CommandStationConnection existing = connections.get(config.getId());
        if (existing != null) {
            return existing;
        }
        CommandStationConnection conn;
        switch (config.getSystemType()) {
            case "xnet-elite":
                conn = new XNetEliteConnection(config, eventBus);
                break;
            case "dccpp-ethernet":
                conn = new DccppConnection(config, eventBus);
                break;
            case "nce-serial":
                conn = new NceSerialConnection(config, eventBus);
                break;
            case "nce-usb":
                conn = new NceUsbConnection(config, eventBus);
                break;
            default:
                throw new IllegalArgumentException("Unsupported systemType: " + config.getSystemType());
        }
        connections.put(config.getId(), conn);
        return conn;
    }

    @Override
    public CommandStationConnection getConnection(String id) {
        return connections.get(id);
    }

    @Override
    public Collection<CommandStationConnection> getConnections() {
        return Collections.unmodifiableCollection(connections.values());
    }

    @Override
    public void removeConnection(String id) {
        CommandStationConnection conn = connections.remove(id);
        if (conn != null) {
            try {
                conn.close();
            } catch (Exception ignore) {
                // ignore
            }
            // Clear role assignments if this connection had any
            synchronized (this) {
                if (id.equals(throttleControllerId)) {
                    throttleControllerId = null;
                }
                if (id.equals(accessoryControllerId)) {
                    accessoryControllerId = null;
                }
            }
            // Remove from connected ports (we'll need to track port somehow)
            // For now, just clear all and let monitoring rediscover
            connectedPorts.clear();
        }
    }

    public DccEventBus getEventBus() {
        return eventBus;
    }
    
    /**
     * Open a throttle session.
     * @param connectionId The connection ID
     * @param address The locomotive address
     * @param longAddress Whether this is a long address
     * @return The throttle session ID (format: "connectionId:address:longAddress")
     */
    public String openThrottle(String connectionId, int address, boolean longAddress) throws IOException {
        // If connectionId is null, use the assigned throttle controller
        CommandStationConnection conn;
        if (connectionId == null || connectionId.isEmpty()) {
            conn = getThrottleController();
            if (conn == null) {
                throw new IOException("No throttle controller available");
            }
            connectionId = conn.getId();
        } else {
            conn = connections.get(connectionId);
            if (conn == null) {
                throw new IOException("Connection not found: " + connectionId);
            }
        }
        if (!conn.isConnected()) {
            throw new IOException("Connection not connected: " + connectionId);
        }
        String throttleId = connectionId + ":" + address + ":" + longAddress;
        ThrottleSession existing = throttles.get(throttleId);
        if (existing != null) {
            return throttleId; // Already open
        }
        ThrottleSession throttle = conn.openThrottle(address, longAddress);
        throttles.put(throttleId, throttle);
        return throttleId;
    }
    
    /**
     * Get a throttle session by ID.
     */
    public ThrottleSession getThrottle(String throttleId) {
        return throttles.get(throttleId);
    }
    
    /**
     * Get all active throttle sessions.
     */
    public Collection<ThrottleSession> getThrottles() {
        return Collections.unmodifiableCollection(throttles.values());
    }
    
    /**
     * Close a throttle session.
     */
    public void closeThrottle(String throttleId) {
        ThrottleSession throttle = throttles.remove(throttleId);
        if (throttle != null) {
            try {
                throttle.close();
            } catch (Exception ignore) {
                // ignore
            }
        }
    }

    @Override
    public void close() {
        // Stop device monitoring
        deviceMonitor.shutdown();
        try {
            if (!deviceMonitor.awaitTermination(2, TimeUnit.SECONDS)) {
                deviceMonitor.shutdownNow();
            }
        } catch (InterruptedException e) {
            deviceMonitor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // Close all throttles first
        for (ThrottleSession throttle : throttles.values()) {
            try {
                throttle.close();
            } catch (Exception ignore) {
                // ignore
            }
        }
        throttles.clear();
        // Then close connections
        for (CommandStationConnection conn : connections.values()) {
            conn.close();
        }
        connections.clear();
        connectedPorts.clear();
    }
}


