# DCC IO Daemon

A standalone Java daemon that provides a simple, clean API for controlling DCC command stations by leveraging JMRI's rich controller support without the complexity of JMRI's full object model and data structures.

## Philosophy

JMRI provides excellent support for a wide variety of DCC command stations through its `jmrix` layer, but using JMRI directly means dealing with its complex object model, data structures, and dependencies. This project bypasses all of that complexity by:

- **Using JMRI's controller support via abstract interfaces** - We leverage JMRI's `jmrix` implementations (XpressNet, DCC++, NCE, etc.) without needing to understand or interact with JMRI's internal data model
- **Providing simple, DCC-spec-focused APIs** - Clean interfaces for throttles and accessories that follow the DCC specification, not JMRI's object hierarchy
- **Minimal dependencies** - Only what's needed for controller communication, not the full JMRI ecosystem
- **RESTful API** - Language-agnostic HTTP/JSON interface perfect for integration with other systems

Perfect for headless deployments on Raspberry Pi or other embedded systems where you want DCC control without the overhead of a full JMRI installation.

## Features

- **Auto-Discovery**: Automatically detects and connects to USB/serial controllers using vendor/product IDs
- **Controller Role Assignment**: Assign controllers to handle throttles and/or accessories via web UI
- **Web-Based Configuration**: Modern web interface for managing connections and testing controllers
- **RESTful API**: Clean JSON API for integration with other applications
- **Real-Time Console**: Live message monitoring with decoded and raw hex output
- **Multi-Controller Support**: Handle multiple controllers with automatic role assignment
- **Platform Agnostic**: Works on Linux, Windows, and macOS

## Supported Controllers

- **Hornby Elite / XpressNet** (`xnet-elite`) - Serial/USB - Tested
- **NCE PowerCab Serial** (`nce-serial`) - Serial/USB - Untested
- **NCE PowerCab USB** (`nce-usb`) - USB - Untested
- **DCC++ Ethernet** (`dccpp-ethernet`) - Network connection - Untested

## Prerequisites

- Java 11 or higher
- Maven 3.6+
- JMRI 5.13.6+ (either pre-built JAR or source)

## Building

### Option 1: Using Pre-built JMRI JAR (Recommended)

If you have a pre-built `jmri.jar`:

```bash
cd dcc-io-daemon
mvn clean package
```

The `pom.xml` is configured to use the pre-built JAR via system scope.

### Option 2: Building JMRI from Source

If you only have JMRI source code:

```bash
cd ../JMRI-5.13.6
ant
jar -cf jmri.jar -C java/build/classes .
mvn install:install-file -Dfile=jmri.jar -DgroupId=org.jmri -DartifactId=jmri -Dversion=5.13.6 -Dpackaging=jar
cd ../dcc-io-daemon
mvn clean package
```

### Using Build Scripts

The provided build scripts automatically detect and use pre-built JAR or build from source:

- **Linux/Mac**: `./build.sh`
- **Windows**: `build.bat`

## Running

### Basic Usage

```bash
java -jar target/dcc-io-daemon-0.1.0-SNAPSHOT-jar-with-dependencies.jar [port]
```

- If `port` is omitted, the daemon listens on port `9000` by default
- The web UI will be available at `http://localhost:9000/`

### With System-Scope JMRI

If using system scope for JMRI, you may need to add JMRI to the classpath:

```bash
java -cp "target/dcc-io-daemon-0.1.0-SNAPSHOT-jar-with-dependencies.jar:../JMRI/jmri.jar:../JMRI/lib/*" org.dccio.daemon.DccIoDaemon [port]
```

## Web UI

The daemon includes a comprehensive web-based interface accessible at `http://localhost:9000/`.

### Features

- **Auto-Discovery**: Scan for connected controllers automatically
- **Connection Management**: Create and manage controller connections
- **Controller Role Assignment**: Assign controllers to handle throttles and/or accessories
- **Throttle Control**: Test throttle functionality (speed, direction, functions F0-F12)
- **Accessory Control**: Test turnout/accessory control
- **Live Console**: Monitor all messages with decoded and raw hex output
- **Command Station Info**: View version, model, and power status

### Connection Configuration

1. Click "🔍 Auto-Discover Controllers" to scan for connected devices
2. Select a detected device or manually configure:
   - Connection ID (e.g., `elite1`)
   - Controller Type (e.g., `Hornby Elite / XpressNet`)
   - Port (for serial/USB) or Host/Port (for network)
   - Baud Rate and Flow Control (for serial connections)
3. Click "Create Connection"

### Controller Roles

Controllers can be assigned to one or both roles:

- **Throttles**: Handles locomotive control (speed, direction, functions)
- **Accessories**: Handles turnout/accessory control

If only one controller is connected, it's automatically assigned both roles. If multiple controllers are present, you can assign roles via checkboxes in the connections list.

## API Endpoints

### Health & Status

- `GET /health` - Health check (`{"status":"ok"}`)
- `GET /connections` - List all active connections with status and roles

### Connection Management

- `POST /connections/create?id={id}&systemType={type}&portName={port}&...` - Create a new connection
  - Query parameters:
    - `id` (required): Connection identifier
    - `systemType` (required): Controller type (e.g., `xnet-elite`, `nce-usb`)
    - `portName` (serial): Serial/USB port (e.g., `/dev/ttyACM0`, `COM3`)
    - `host` (network): Hostname/IP for network connections
    - `port` (network): Port number for network connections
    - `baudRate` (serial, optional): Baud rate (default: 9600, Elite: 19200)
    - `flowControl` (serial, optional): `none` or `rtscts`
    - `userName` (optional): User name for the connection
    - `systemPrefix` (optional): System prefix

- `POST /connections/requestVersion?id={id}` - Request version info from command station
- `POST /connections/setRole?connectionId={id}&role={role}&enabled={true|false}` - Assign/unassign controller role
  - `role`: `throttles` or `accessories`

### System Information

- `GET /api/systems` - List supported controller types
- `GET /api/ports` - List available serial/USB ports
- `GET /api/discover` - Discover connected controllers (auto-detection)

### Throttle Control

- `GET /api/throttles` - List all open throttles
- `POST /api/throttles?address={addr}&longAddress={true|false}` - Open a throttle
  - Uses assigned throttle controller automatically
- `POST /api/throttles/{id}/speed?value={0.0-1.0}` - Set speed (0.0 = stop, 1.0 = full)
- `POST /api/throttles/{id}/direction?forward={true|false}` - Set direction
- `POST /api/throttles/{id}/function?number={0-28}&on={true|false}` - Set function state
- `DELETE /api/throttles/{id}` - Close throttle

### Accessory Control

- `POST /api/accessories?address={addr}&closed={true|false}` - Set turnout state
  - Uses assigned accessory controller automatically
  - `closed=true` = CLOSED, `closed=false` = THROWN

### Real-Time Events

- `GET /api/events` - Server-Sent Events (SSE) stream for real-time updates
  - Event types: `MESSAGE_RECEIVED`, `MESSAGE_SENT`, `THROTTLE_UPDATED`, `POWER_CHANGED`, `CONNECTION_STATE_CHANGED`

## Configuration

### Device Discovery

Device auto-discovery is configured via `src/main/resources/device-discovery-config.json`. This file defines:

- Vendor ID and Product ID for USB device detection
- System type mapping
- Default configuration (baud rate, flow control, etc.)
- Description patterns for device matching

Example entry:

```json
{
  "name": "Hornby Elite",
  "vendorId": "0x04d8",
  "productId": "0x000a",
  "systemType": "xnet-elite",
  "config": {
    "baudRate": "19200",
    "flowControl": "none"
  },
  "descriptionPatterns": ["Hornby Elite", "XpressNet"]
}
```

### Auto-Connection

The daemon automatically:
- Scans for devices on startup
- Connects to detected controllers
- Assigns roles automatically (single controller = both roles, multiple = first gets both)
- Continuously monitors for new devices (every 5 seconds)

## Architecture

### Design Philosophy

This daemon is designed to be a thin abstraction layer over JMRI's `jmrix` implementations. We:

1. **Reuse JMRI's controller implementations** - All the protocol-specific code (XpressNet, DCC++, NCE, etc.) comes from JMRI
2. **Wrap with simple interfaces** - Our `CommandStationConnection`, `ThrottleSession`, and `AccessoryController` interfaces hide JMRI's complexity
3. **Avoid JMRI's object model** - We don't use JMRI's `InstanceManager`, `NamedBean` hierarchies, or other complex data structures
4. **Focus on DCC operations** - The API is centered on DCC specification operations (throttle control, accessory control) rather than JMRI concepts

### Core Components

- **`DccIoService`**: Main service interface for managing connections and throttles
- **`CommandStationConnection`**: Simple interface for controller connections (wraps JMRI's `SystemConnectionMemo` and adapters)
- **`ThrottleSession`**: Clean interface for locomotive control (wraps JMRI's `DccThrottle`)
- **`AccessoryController`**: Simple interface for turnout/accessory control (wraps JMRI's `TurnoutManager`)
- **`DeviceDiscoveryService`**: Platform-agnostic USB/serial device detection

### Connection Implementations

Each connection implementation:
- Wraps JMRI's `SystemConnectionMemo` and protocol-specific adapters
- Provides a simple `CommandStationConnection` interface
- Handles protocol-specific details internally (you don't need to know about XNet messages, NCE commands, etc.)
- Exposes only DCC-standard operations (throttle speed/direction/functions, accessory states)

- `XNetEliteConnection`: Hornby Elite / XpressNet (wraps JMRI's `XNetSystemConnectionMemo`)
- `NceSerialConnection`: NCE PowerCab Serial (wraps JMRI's `NceSystemConnectionMemo`)
- `NceUsbConnection`: NCE PowerCab USB (wraps JMRI's `NceSystemConnectionMemo`)
- `DccppConnection`: DCC++ Ethernet (wraps JMRI's `DCCppSystemConnectionMemo`)

### Web Server

Uses JDK's built-in `HttpServer` for minimal dependencies. Web UI is served from resources with separate HTML, CSS, and JavaScript files.

## Development

### Project Structure

```
dcc-io-daemon/
├── src/main/java/org/dccio/
│   ├── core/              # Core interfaces and services
│   │   ├── impl/          # Implementation classes
│   │   └── events/        # Event system
│   └── daemon/            # HTTP server and web UI
├── src/main/resources/
│   ├── web/               # Web UI files
│   │   ├── index.html
│   │   └── static/
│   │       ├── style.css
│   │       └── app.js
│   └── device-discovery-config.json
└── pom.xml
```

### Adding New Controllers

1. Create a new connection class implementing `CommandStationConnection`
2. Extend `BaseCommandStationConnection` for common functionality
3. Implement protocol-specific methods
4. Register in `DccIoServiceImpl.createConnection()`
5. Add device discovery entry to `device-discovery-config.json`

## Troubleshooting

### Controller Not Detected

- Check USB/serial port permissions (Linux: add user to `dialout` group)
- Verify vendor/product IDs in `device-discovery-config.json`
- Check device description patterns match actual device name

### Connection Fails

- Verify port name is correct (`/dev/ttyACM0`, `COM3`, etc.)
- Check baud rate matches controller requirements (Elite: 19200)
- Ensure controller is powered on and connected

### No Throttle/Accessory Controller Available

- Assign controller roles via web UI checkboxes
- Ensure at least one controller is connected and assigned the required role

## License

This project wraps JMRI components. Please refer to JMRI's license for usage terms.

## Contributing

Contributions welcome! Please ensure:
- Code follows existing style
- New controllers are properly tested
- Documentation is updated