package org.dccio.core.impl.xnet.elite;

import org.dccio.core.ThrottleSession;
import org.dccio.core.events.DccEventBus;

import jmri.DccThrottle;
import jmri.jmrix.lenz.XNetMessage;
import jmri.jmrix.lenz.XNetTrafficController;

import java.io.IOException;

/**
 * Direct XpressNet throttle session that bypasses JMRI's throttle abstraction
 * for speed/direction commands, sending them directly like the Python implementation.
 * Functions still use JMRI's throttle for compatibility.
 */
public class DirectXNetThrottleSession implements ThrottleSession {

    private final String connectionId;
    private final int address;
    private final boolean longAddress;
    private final XNetTrafficController trafficController;
    private final DccEventBus eventBus;
    private final DccThrottle jmriThrottle; // Used for functions only
    
    // Track state internally (for speed/direction sent directly)
    private volatile float currentSpeed = 0.0f;
    private volatile boolean currentDirection = true; // forward

    public DirectXNetThrottleSession(String connectionId, int address, boolean longAddress,
                                     XNetTrafficController trafficController, DccEventBus eventBus,
                                     DccThrottle jmriThrottle) {
        this.connectionId = connectionId;
        this.address = address;
        this.longAddress = longAddress;
        this.trafficController = trafficController;
        this.eventBus = eventBus;
        this.jmriThrottle = jmriThrottle;
    }

    @Override
    public String getConnectionId() {
        return connectionId;
    }

    @Override
    public int getAddress() {
        return address;
    }

    @Override
    public boolean isLongAddress() {
        return longAddress;
    }

    @Override
    public void setSpeed(float speed) throws IOException {
        if (speed < 0 || speed > 1) {
            throw new IllegalArgumentException("Speed must be between 0.0 and 1.0");
        }
        
        // Convert normalized speed (0.0-1.0) to XpressNet speed (0-127)
        int xnetSpeed = Math.round(speed * 127);
        
        // Send direct XpressNet throttle command: 0xE4 0x13 [address] [speed|direction] [checksum]
        // This matches the Python throttle() method exactly
        byte[] throttleBytes = new byte[5];
        throttleBytes[0] = (byte)0xE4;  // Loco operation request
        throttleBytes[1] = 0x13;          // Set speed and direction
        
        // Encode address (same logic as Python)
        if (address < 100) {
            throttleBytes[2] = 0x00;
            throttleBytes[3] = (byte)address;
        } else {
            // Long address encoding: high byte has 0xC0 set
            throttleBytes[2] = (byte)((address >> 8) | 0xC0);
            throttleBytes[3] = (byte)(address & 0xFF);
        }
        
        // Speed and direction: bit 7 = direction (0x80 = forward, 0x00 = reverse)
        throttleBytes[4] = (byte)xnetSpeed;
        if (currentDirection) {
            throttleBytes[4] |= 0x80;  // Set forward bit
        } else {
            throttleBytes[4] &= 0x7F;   // Clear forward bit (reverse)
        }
        
        // Calculate checksum (XOR of all bytes)
        byte checksum = 0;
        for (byte b : throttleBytes) {
            checksum ^= b;
        }
        
        // Create and send XNetMessage
        XNetMessage throttleMsg = new XNetMessage(throttleBytes.length + 1);
        for (int i = 0; i < throttleBytes.length; i++) {
            throttleMsg.setElement(i, throttleBytes[i] & 0xFF);
        }
        throttleMsg.setElement(throttleBytes.length, checksum & 0xFF);
        
        trafficController.sendXNetMessage(throttleMsg, null);
        
        // Update internal state
        currentSpeed = speed;
        
        // Note: We don't publish THROTTLE_UPDATED events here because:
        // 1. We're bypassing JMRI, so these events are not needed
        // 2. Publishing events would cause an infinite loop (event -> openThrottle -> setSpeed -> event)
        // 3. Events are meant for external changes (physical controller), not our own commands
    }

    @Override
    public void setDirection(boolean forward) throws IOException {
        boolean directionChanged = (currentDirection != forward);
        if (!directionChanged) {
            return; // No change needed
        }
        
        currentDirection = forward;
        
        // Send throttle command with current speed but new direction
        // Convert normalized speed (0.0-1.0) to XpressNet speed (0-127)
        int xnetSpeed = Math.round(currentSpeed * 127);
        
        // Send direct XpressNet throttle command: 0xE4 0x13 [address] [speed|direction] [checksum]
        byte[] throttleBytes = new byte[5];
        throttleBytes[0] = (byte)0xE4;  // Loco operation request
        throttleBytes[1] = 0x13;          // Set speed and direction
        
        // Encode address (same logic as Python)
        if (address < 100) {
            throttleBytes[2] = 0x00;
            throttleBytes[3] = (byte)address;
        } else {
            // Long address encoding: high byte has 0xC0 set
            throttleBytes[2] = (byte)((address >> 8) | 0xC0);
            throttleBytes[3] = (byte)(address & 0xFF);
        }
        
        // Speed and direction: bit 7 = direction (0x80 = forward, 0x00 = reverse)
        throttleBytes[4] = (byte)xnetSpeed;
        if (currentDirection) {
            throttleBytes[4] |= 0x80;  // Set forward bit
        } else {
            throttleBytes[4] &= 0x7F;   // Clear forward bit (reverse)
        }
        
        // Calculate checksum (XOR of all bytes)
        byte checksum = 0;
        for (byte b : throttleBytes) {
            checksum ^= b;
        }
        
        // Create and send XNetMessage
        XNetMessage throttleMsg = new XNetMessage(throttleBytes.length + 1);
        for (int i = 0; i < throttleBytes.length; i++) {
            throttleMsg.setElement(i, throttleBytes[i] & 0xFF);
        }
        throttleMsg.setElement(throttleBytes.length, checksum & 0xFF);
        
        trafficController.sendXNetMessage(throttleMsg, null);
        
        // Note: We don't publish THROTTLE_UPDATED events here (same reason as setSpeed)
    }

    @Override
    public void setFunction(int functionNumber, boolean on) throws IOException {
        // Use JMRI throttle for functions (they work fine)
        if (jmriThrottle != null) {
            jmriThrottle.setFunction(functionNumber, on);
        } else {
            throw new IOException("JMRI throttle not available for function control");
        }
    }

    @Override
    public float getSpeed() {
        return currentSpeed;
    }

    @Override
    public boolean getDirection() {
        return currentDirection;
    }

    @Override
    public boolean getFunction(int functionNumber) {
        // Use JMRI throttle for functions
        if (jmriThrottle != null) {
            return jmriThrottle.getFunction(functionNumber);
        }
        return false;
    }

    @Override
    public void close() {
        // Release JMRI throttle (used for functions)
        if (jmriThrottle != null) {
            jmriThrottle.release(null);
        }
    }
}
