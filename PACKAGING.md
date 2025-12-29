# Debian Package Building Guide

This document describes how to build and install the dcc-io-daemon Debian package.

## Prerequisites

Before building the Debian package, ensure you have the following installed:

```bash
sudo apt-get update
sudo apt-get install dpkg-dev debhelper build-essential default-jdk maven curl jq
```

## Building the Package

### Option 1: Using the build script (recommended)

```bash
./build-deb.sh
```

This will:
1. Build the JAR file using Maven
2. Create the Debian package
3. Output the `.deb` file in the parent directory

### Option 2: Manual build

```bash
# Build the JAR (may fail if JMRI not available - that's OK)
mvn clean package -DskipTests

# Build the Debian package
dpkg-buildpackage -us -uc -b
```

The package will be created in the parent directory as `dcc-io-daemon_0.1.0-1_all.deb`.

## Installing the Package

```bash
sudo dpkg -i ../dcc-io-daemon_0.1.0-1_all.deb
```

If there are missing dependencies, install them with:

```bash
sudo apt-get install -f
```

## Installing JMRI

After installing the package, you must install JMRI separately:

```bash
sudo jmri-install
```

This script will:
1. Fetch available JMRI releases from GitHub
2. Display production and test releases
3. Allow you to select which version to install
4. Download and extract JMRI to `/opt/jmri`
5. Create a symlink at `/opt/jmri/current`
6. Optionally restart the dcc-io-daemon service

## Service Management

Once JMRI is installed, the service can be managed with systemd:

```bash
# Start the service
sudo systemctl start dcc-io-daemon

# Stop the service
sudo systemctl stop dcc-io-daemon

# Check status
sudo systemctl status dcc-io-daemon

# View logs
sudo journalctl -u dcc-io-daemon -f
```

The service will automatically start on boot if enabled.

## Package Structure

The Debian package installs:

- `/usr/lib/dcc-io-daemon/` - Main JAR file
- `/usr/local/bin/jmri-install` - JMRI installation script
- `/lib/systemd/system/dcc-io-daemon.service` - Systemd service file
- `/var/lib/dcc-io-daemon/` - Working directory (created on install)
- `/etc/dcc-io-daemon/` - Configuration directory (created on install)

JMRI is installed separately to `/opt/jmri/` via the `jmri-install` script.

## Removing the Package

```bash
# Remove the package (keeps configuration)
sudo apt-get remove dcc-io-daemon

# Purge the package (removes configuration)
sudo apt-get purge dcc-io-daemon
```

During purge, you'll be prompted whether to remove JMRI as well.

## Troubleshooting

### Service fails to start

1. Check if JMRI is installed:
   ```bash
   ls -la /opt/jmri/current
   ```

2. If not installed, run:
   ```bash
   sudo jmri-install
   ```

3. Check service logs:
   ```bash
   sudo journalctl -u dcc-io-daemon -n 50
   ```

### JMRI installation fails

- Ensure you have internet connectivity
- Check that `curl` and `jq` are installed
- Verify GitHub API is accessible

### Build fails

- Ensure all prerequisites are installed
- Check that Maven can access required dependencies
- Note: The build may fail if JMRI is not available, but this is expected for packaging

