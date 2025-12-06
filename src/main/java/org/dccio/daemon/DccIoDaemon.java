package org.dccio.daemon;

import org.dccio.core.impl.DccIoServiceImpl;

/**
 * Entry point for the standalone DCC IO daemon.
 *
 * Usage:
 * <pre>
 *   java -cp ... org.dccio.daemon.DccIoDaemon [port]
 * </pre>
 *
 * The daemon will start an embedded HTTP server exposing the minimal
 * management API implemented in {@link DccIoHttpServer}.
 */
public final class DccIoDaemon {

    private DccIoDaemon() {
        // no instances
    }

    public static void main(String[] args) throws Exception {
        int port = 9000;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignore) {
                // use default
            }
        }
        DccIoServiceImpl service = new DccIoServiceImpl();
        
        // Start continuous device monitoring and auto-connect
        System.out.println("Starting device monitoring...");
        service.startDeviceMonitoring();
        
        DccIoHttpServer httpServer = new DccIoHttpServer(service, port);
        httpServer.start();
        System.out.println("DCC IO daemon listening on port " + port);
        System.out.println("Press Ctrl+C to stop the daemon");
        
        // Register shutdown hook for graceful shutdown
        final Thread mainThread = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down DCC IO daemon...");
            try {
                // Interrupt main thread to wake it up if it's waiting
                mainThread.interrupt();
                // Stop HTTP server (give it 2 seconds to finish current requests)
                httpServer.stop(2);
                // Close all connections
                service.close();
                System.out.println("Daemon stopped successfully");
            } catch (Exception e) {
                System.err.println("Error during shutdown: " + e.getMessage());
                e.printStackTrace();
            }
        }));
        
        // Keep the main thread alive, but make it interruptible
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            // Expected on shutdown
            Thread.currentThread().interrupt();
        }
    }
}


