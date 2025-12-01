package com.ed522.bcr2200.attendance.io;

import com.ed522.bcr2200.attendance.BaseApplication;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public class AttendanceServer {

    private static final int INTERVAL = 5_000; // ms
    private static final int PORT = 5783; // TODO verify port

    private final ExecutorService executor = Executors.newFixedThreadPool(
            4, Thread.ofVirtual().name("NetworkWorker-", 1).factory()
    );
    private Socket socket;

    private static void handshake(OutputStream out, InputStream in) throws IOException {

        // compose handshake message
        JsonObject handshakeMessage = new JsonObject();
        handshakeMessage.addProperty("type", "handshake");
        out.write(handshakeMessage.toString().getBytes(StandardCharsets.UTF_8));

        // expect correct response
        JsonObject handshakeResponse = JsonParser.parseString(new String(in.readAllBytes())).getAsJsonObject();
        if (
                !"acknowledge".equals(handshakeResponse.get("type").getAsString()) ||
                !"handshake".equals(handshakeResponse.get("targeting").getAsString())
        ) {
            throw new IOException("Handshake failure - incorrect response given." +
                    " Is something else on port " + PORT + "?");
        }

    }

    /**
     * Attempt to discover and connect to a willing server.
     * <br /><br />
     * By default, the client will attempt to discover servers using a
     * broadcast. If {@code knownGoodHosts} is non-empty and non-null, it will
     * try to connect to each of those instead.
     * <br /><br />
     * After starting, a thread will be spawned to send generated codes to the
     * server.
     * <br /><br />
     * In the case that there are no good hosts, the
     * @param maxTries How many times to try and discover a host before failing (0 for infinite tries). Must be >= 0.
     * @param knownGoodHosts An array of known good hosts, or null if there are none and discovery should be used.
     * @throws IOException if there is an issue connecting to or communicating with the server.
     */
    public void connect(int maxTries, String[] knownGoodHosts) throws IOException {

        if (maxTries < 0)
            throw new IllegalArgumentException("maxTries must be a positive number or 0, got " + maxTries);

        if (knownGoodHosts == null || knownGoodHosts.length == 0)
            connectWithDiscovery(maxTries);
        else {
            // try to connect to each host in series
            for (String host : knownGoodHosts) {
                try (Socket s = new Socket(host, PORT)) {
                    handshake(s.getOutputStream(), s.getInputStream());
                }
            }
        }

    }

    private void connectWithDiscovery(int maxTries) throws IOException {

        // set up to accept any connections
        Future<Socket> future = executor.submit(() -> {
            try (ServerSocket s = new ServerSocket(PORT)) {
                return s.accept();
            }
        });

        // broadcast to find any available servers
        socket.connect(new InetSocketAddress("255.255.255.255", PORT), 2500);

        // look for hosts
        int tries = 0;

        JsonObject object = new JsonObject();
        object.addProperty("app", "attendance");
        object.addProperty("type", "discovery");
        object.addProperty("version", BaseApplication.VERSION);
        object.addProperty("host", InetAddress.getLocalHost().getHostAddress());

        byte[] message = object.toString().getBytes(StandardCharsets.UTF_8);

        while ((tries < maxTries || maxTries == 0)) {
            socket.getOutputStream().write(message);
            tries++;
            try {
                // if the server connects (future call does not throw) break
                future.get(INTERVAL, TimeUnit.MILLISECONDS);
                // here it must be done
                break;
            } catch (InterruptedException e) {
                throw new IOException("No host found (interrupted)");
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            } catch (TimeoutException e) {
                // if the server does not connect, try again
                // pass
            }
        }

        // we have a connection, or we ran out of tries
        if (!future.isDone()) {
            // ran out of tries
            throw new IOException("No host found (%d attempts)".formatted(tries));
        }

        // otherwise get the socket
        try {
            socket = future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            throw new IOException("Error occurred while connecting to server", e);
        }

        handshake(socket.getOutputStream(), socket.getInputStream());

    }

}
