#!/bin/bash
# Build Debian package for dcc-io-daemon

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "Building Debian package for dcc-io-daemon..."

# Check for required tools
for cmd in dpkg-buildpackage mvn; do
    if ! command -v $cmd &> /dev/null; then
        echo "ERROR: $cmd is required but not installed."
        echo "Install with: sudo apt-get install dpkg-dev debhelper maven"
        exit 1
    fi
done

# Make sure scripts are executable
if [ -f scripts/jmri-install ]; then
    chmod +x scripts/jmri-install
fi
if [ -f debian/rules ]; then
    chmod +x debian/rules
fi
if [ -f debian/postinst ]; then
    chmod +x debian/postinst
fi
if [ -f debian/prerm ]; then
    chmod +x debian/prerm
fi
if [ -f debian/postrm ]; then
    chmod +x debian/postrm
fi

# Check if we need to build the JAR first
# For Debian package, we'll let debian/rules handle the build
# But we can do a test build to make sure everything compiles

echo "Building JAR file (this may fail if JMRI is not available - that's OK for packaging)..."
mvn clean package -DskipTests || {
    echo "WARNING: Maven build failed (likely due to missing JMRI)."
    echo "This is expected for packaging - JMRI will be installed separately."
    echo "Continuing with package build..."
}

# Build the Debian package
echo ""
echo "Building Debian package..."
dpkg-buildpackage -us -uc -b

echo ""
echo "Build complete!"
echo ""
echo "To install the package:"
echo "  sudo dpkg -i ../dcc-io-daemon_0.1.0-1_all.deb"
echo ""
echo "After installation, install JMRI with:"
echo "  sudo jmri-install"

