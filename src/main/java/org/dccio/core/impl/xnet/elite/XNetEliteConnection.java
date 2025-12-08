package org.dccio.core.impl.xnet.elite;

import org.dccio.core.AccessoryController;
import org.dccio.core.ProgrammerSession;
import org.dccio.core.SystemConfig;
import org.dccio.core.ThrottleSession;
import org.dccio.core.events.DccEvent;
import org.dccio.core.events.DccEventBus;
import org.dccio.core.events.DccEventType;
import org.dccio.core.impl.common.BaseCommandStationConnection;
import org.dccio.core.impl.common.JmriAccessoryController;
import org.dccio.core.impl.common.JmriProgrammerSession;
import org.dccio.core.impl.common.JmriThrottleSession;

import jmri.DccThrottle;
import jmri.GlobalProgrammerManager;
import jmri.InstanceManager;
import jmri.PowerManager;
import jmri.ThrottleListener;
import jmri.ThrottleManager;
import jmri.TurnoutManager;
import jmri.jmrix.SystemConnectionMemoManager;
import jmri.jmrix.lenz.XNetListener;
import jmri.jmrix.lenz.XNetMessage;
import jmri.jmrix.lenz.XNetReply;
import jmri.jmrix.lenz.XNetTrafficController;
import jmri.jmrix.lenz.XNetInterface;
import jmri.jmrix.lenz.hornbyelite.EliteAdapter;
import jmri.jmrix.lenz.hornbyelite.EliteXNetSystemConnectionMemo;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * CommandStationConnection for Hornby Elite / XpressNet, backed by the JMRI
 * XNet stack.
 */
public final class XNetEliteConnection extends BaseCommandStationConnection {

    private final EliteAdapter adapter;
    private final EliteXNetSystemConnectionMemo memo;

    private JmriAccessoryController accessoryController;
    private JmriProgrammerSession programmerSession;

    private final PropertyChangeListener powerListener = this::onPowerChange;
    private final XNetListener xnetListener = new XNetListener() {
        @Override
        public void message(XNetMessage m) {
            // Outgoing message to command station - publish for console
            Map<String, Object> payload = new HashMap<>();
            payload.put("direction", "out");
            payload.put("message", m.toString());
            payload.put("hex", bytesToHex(m));
            // Use generic message formatter
            String formatted = formatMessage(m);
            if (formatted != null && !formatted.equals(m.toString())) {
                payload.put("decoded", formatted);
            }
            eventBus.publish(new DccEvent(DccEventType.MESSAGE_SENT, id, payload));
        }

        @Override
        public void message(XNetReply m) {
            // Incoming message from command station - publish for console and check for important events
            Map<String, Object> payload = new HashMap<>();
            payload.put("direction", "in");
            payload.put("message", m.toString());
            payload.put("hex", bytesToHex(m));
            // Use generic message formatter
            String formatted = formatMessage(m);
            if (formatted != null && !formatted.equals(m.toString())) {
                payload.put("decoded", formatted);
            }
            eventBus.publish(new DccEvent(DccEventType.MESSAGE_RECEIVED, id, payload));
            handleXNetReply(m);
        }

        @Override
        public void notifyTimeout(XNetMessage m) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("message", m.toString());
            eventBus.publish(new DccEvent(DccEventType.COMMUNICATION_ERROR, id, payload));
        }
    };

    public XNetEliteConnection(SystemConfig config, DccEventBus eventBus) {
        super(config, eventBus);
        this.memo = new EliteXNetSystemConnectionMemo();
        this.adapter = new EliteAdapter();
        this.adapter.setSystemConnectionMemo(memo);
        memo.setUserName(config.getUserName());
        memo.setSystemPrefix(config.getSystemPrefix());
        // register in the global memo manager so standard JMRI managers work
        SystemConnectionMemoManager.getDefault().register(memo);
    }

    @Override
    public void connect() throws IOException {
        if (connected) {
            return;
        }
        String portName = config.getOption("portName");
        if (portName == null) {
            throw new IOException("Missing 'portName' option for Elite connection");
        }
        
        // Configure baud rate if provided (e.g., "9600", "19200", "38400", "57600", "115200")
        // Default to 19200 for Elite if not specified
        String baudRate = config.getOption("baudRate");
        if (baudRate == null || baudRate.isEmpty()) {
            baudRate = "19200"; // Default for Elite
        }
        try {
            // Try to configure by number first (e.g., "19200")
            adapter.configureBaudRateFromNumber(baudRate);
        } catch (Exception e) {
            // If that fails, try as a string (e.g., "19200 bps")
            adapter.configureBaudRate(baudRate);
        }
        
        // Configure flow control if provided
        // Valid values: "none"/"no" (no flow control) or "rtscts"/"hardware"/"hw" (hardware flow control)
        String flowControl = config.getOption("flowControl");
        if (flowControl != null && !flowControl.isEmpty()) {
            String[] validOptions = adapter.getOptionChoices("FlowControl");
            if (validOptions != null && validOptions.length >= 2) {
                String flowControlValue;
                if (flowControl.equalsIgnoreCase("none") || flowControl.equalsIgnoreCase("no")) {
                    // No flow control - use first option value
                    flowControlValue = validOptions[0];
                } else if (flowControl.equalsIgnoreCase("rtscts") || flowControl.equalsIgnoreCase("hardware") || flowControl.equalsIgnoreCase("hw")) {
                    // Hardware flow control - use second option value
                    flowControlValue = validOptions[1];
                } else {
                    // Use the provided value directly (might be a localized string)
                    flowControlValue = flowControl;
                }
                adapter.setOptionState("FlowControl", flowControlValue);
            }
        }
        
        String error = adapter.openPort(portName, "dcc-io-daemon");
        if (error != null) {
            throw new IOException("Failed to open Elite port: " + error);
        }
        adapter.configure();
        attachManagersAndListeners();
        
        // Request version info automatically on connection (XNet-specific)
        // This will populate the command station info when the response arrives
        // Similar to what XNetInitializationManager does
        try {
            XNetTrafficController tc = memo.getXNetTrafficController();
            if (tc != null) {
                XNetMessage msg = XNetMessage.getCSVersionRequestMessage();
                tc.sendXNetMessage(msg, null);
            }
        } catch (Exception e) {
            // Log but don't fail connection if version request fails
            // Version info will just remain unknown until manually requested
        }
        
        connected = true;
        publishConnectionState();
    }

    private void attachManagersAndListeners() {
        // ensure a traffic controller exists and is wired
        XNetTrafficController tc = memo.getXNetTrafficController();
        if (tc == null) {
            tc = new jmri.jmrix.lenz.XNetPacketizer(new jmri.jmrix.lenz.hornbyelite.HornbyEliteCommandStation());
            tc.connectPort(adapter);
            memo.setXNetTrafficController(tc);
        }

        // set up accessory and programmer wrappers
        TurnoutManager turnoutManager = memo.getTurnoutManager();
        if (turnoutManager != null) {
            accessoryController = new JmriAccessoryController(id, turnoutManager);
        }
        GlobalProgrammerManager gpm = InstanceManager.getNullableDefault(GlobalProgrammerManager.class);
        if (gpm != null) {
            programmerSession = new JmriProgrammerSession(id, gpm);
        }

        // observe power events
        PowerManager pm = memo.getPowerManager();
        if (pm != null) {
            pm.addPropertyChangeListener(powerListener);
        }

        // register XNet listener to catch all incoming messages (emergency stops, throttle changes, etc.)
        // Using XNetInterface.ALL mask to receive all message types
        tc.addXNetListener(XNetInterface.ALL, xnetListener);
    }

    /**
     * Handle incoming XNet replies from the command station.
     * This catches events like emergency stops, throttle changes from other devices, etc.
     */
    private void handleXNetReply(XNetReply reply) {
        // Check for emergency stop or track power off messages
        // XNet uses specific message codes for these events
        if (reply.isOkMessage()) {
            // OK message - could be response to various commands
            // Could check context if needed
        } else if (reply.getNumDataElements() > 0) {
            int firstByte = reply.getElement(0) & 0xFF;
            
            // Check for command station info messages
            if (firstByte == 0x61) { // CS_INFO message
                if (reply.getNumDataElements() > 1) {
                    int secondByte = reply.getElement(1) & 0xFF;
                    // Check for emergency stop (0x82) or track power off (0x00 in some contexts)
                    if (secondByte == 0x82) {
                        Map<String, Object> payload = new HashMap<>();
                        payload.put("reason", "emergency_stop");
                        payload.put("message", reply.toString());
                        eventBus.publish(new DccEvent(DccEventType.EMERGENCY_STOP, id, payload));
                    }
                }
            }
            
            // Check for throttle status messages (0xE5) which indicate throttle changes
            // These are handled by the throttle objects themselves, but we could log them here
            if (firstByte == 0xE5) {
                // Throttle status update - the throttle objects will handle this
                // but we could publish a generic event if needed
            }
            
            // Check for command station version response (0x63 = CS_SERVICE_MODE_RESPONSE)
            if (firstByte == 0x63 && reply.getNumDataElements() > 1) {
                int secondByte = reply.getElement(1) & 0xFF;
                // Check if this is a version response (0x21 = CS_SOFTWARE_VERSION)
                if (secondByte == 0x21) {
                    // Update the command station object with version info
                    XNetTrafficController tc = memo.getXNetTrafficController();
                    if (tc != null) {
                        jmri.jmrix.lenz.LenzCommandStation cs = (jmri.jmrix.lenz.LenzCommandStation) tc.getCommandStation();
                        if (cs != null) {
                            cs.setCommandStationType(reply);
                            cs.setCommandStationSoftwareVersion(reply);
                        }
                    }
                }
            }
        }
    }
    
    @Override
    public void requestVersion() throws IOException {
        if (!connected) {
            throw new IOException("Not connected");
        }
        XNetTrafficController tc = memo.getXNetTrafficController();
        if (tc != null) {
            // Use JMRI's standard version request message
            XNetMessage msg = XNetMessage.getCSVersionRequestMessage();
            tc.sendXNetMessage(msg, null);
        } else {
            throw new IOException("Traffic controller not available");
        }
    }

    @Override
    public ThrottleSession openThrottle(int address, boolean longAddress) throws IOException {
        ThrottleManager tm = memo.getThrottleManager();
        if (tm == null) {
            throw new IOException("No ThrottleManager available on Elite connection");
        }
        final DccThrottle[] holder = new DccThrottle[1];
        final IOException[] error = new IOException[1];
        ThrottleListener listener = new ThrottleListener() {
            @Override
            public void notifyThrottleFound(DccThrottle t) {
                holder[0] = t;
            }

            @Override
            public void notifyFailedThrottleRequest(jmri.LocoAddress address, String reason) {
                error[0] = new IOException("Throttle request failed: " + reason);
            }

            @Override
            public void notifyDecisionRequired(jmri.LocoAddress address, ThrottleListener.DecisionType question) {
                // Default behavior: cancel the request if a decision is required
                error[0] = new IOException("Throttle address " + address + " is in use, decision required: " + question);
            }
        };
        tm.requestThrottle(address, longAddress, listener, false);
        if (error[0] != null) {
            throw error[0];
        }
        if (holder[0] == null) {
            throw new IOException("Throttle not granted for address " + address);
        }
        return new JmriThrottleSession(id, address, longAddress, holder[0], eventBus);
    }

    @Override
    public ProgrammerSession getProgrammer() {
        return programmerSession;
    }

    @Override
    public AccessoryController getAccessoryController() {
        return accessoryController;
    }

    @Override
    public java.util.Map<String, String> getCommandStationInfo() {
        Map<String, String> info = new HashMap<>();
        XNetTrafficController tc = memo.getXNetTrafficController();
        if (tc != null) {
            jmri.jmrix.lenz.LenzCommandStation cs = (jmri.jmrix.lenz.LenzCommandStation) tc.getCommandStation();
            if (cs != null) {
                info.put("manufacturer", "Hornby");
                info.put("model", "Elite");
                String versionString = cs.getVersionString();
                if (versionString != null && !versionString.isEmpty()) {
                    info.put("version", versionString);
                }
                int csType = cs.getCommandStationType();
                if (csType >= 0) {
                    info.put("type", String.valueOf(csType));
                }
                float swVersion = cs.getCommandStationSoftwareVersion();
                if (swVersion >= 0) {
                    info.put("softwareVersion", String.valueOf(swVersion));
                }
            }
        }
        return info.isEmpty() ? null : info;
    }

    @Override
    public String getPowerStatus() {
        PowerManager pm = memo.getPowerManager();
        if (pm != null) {
            int power = pm.getPower();
            switch (power) {
                case PowerManager.ON:
                    return "ON";
                case PowerManager.OFF:
                    return "OFF";
                case PowerManager.IDLE:
                    return "IDLE";
                default:
                    return "UNKNOWN";
            }
        }
        return "UNKNOWN";
    }

    private void onPowerChange(PropertyChangeEvent evt) {
        if (PowerManager.POWER.equals(evt.getPropertyName())) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("old", evt.getOldValue());
            payload.put("new", evt.getNewValue());
            // Also include the power status string for convenience
            int power = (Integer) evt.getNewValue();
            String powerStatus;
            switch (power) {
                case PowerManager.ON:
                    powerStatus = "ON";
                    break;
                case PowerManager.OFF:
                    powerStatus = "OFF";
                    break;
                case PowerManager.IDLE:
                    powerStatus = "IDLE";
                    break;
                default:
                    powerStatus = "UNKNOWN";
            }
            payload.put("status", powerStatus);
            eventBus.publish(new DccEvent(DccEventType.POWER_CHANGED, id, payload));
        }
    }

    private String bytesToHex(jmri.jmrix.AbstractMessage msg) {
        if (msg == null) return "";
        StringBuilder sb = new StringBuilder();
        int len = msg.getNumDataElements();
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02X ", msg.getElement(i) & 0xFF));
        }
        return sb.toString().trim();
    }
    

    @Override
    public void close() {
        connected = false;
        publishConnectionState();
        
        // Remove listeners first
        PowerManager pm = memo.getPowerManager();
        if (pm != null) {
            pm.removePropertyChangeListener(powerListener);
        }
        XNetTrafficController tc = memo.getXNetTrafficController();
        if (tc != null) {
            tc.removeXNetListener(XNetInterface.ALL, xnetListener);
            // Terminate traffic controller threads before closing port
            try {
                tc.terminateThreads();
            } catch (Exception e) {
                // Ignore errors during shutdown
            }
        }
        
        // Dispose adapter and memo (dispose() will close the port)
        if (adapter != null) {
            adapter.dispose();
        }
        if (memo != null) {
            memo.dispose();
        }
    }
}


