package com.ed522.bcr2200.attendance.io;

import com.ed522.bcr2200.attendance.BaseApplication;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AttendanceEndpoint {

    public record VerificationCode(long value, Instant time) {}
    public enum ConnectionState {
        DISCONNECTED,
        SEARCHING_FOR_HOSTS,
        CONNECTING,
        CONNECTED_GOOD,
        CONNECTED_PROBLEM
    }

    private static final Logger LOGGER = Logger.getLogger("AttendanceEndpoint");

    private static final int STRICT_SO_TIMEOUT = 1_000;
    private static final int INTERVAL = 5_000; // ms
    private static final int PORT = 5789;

    private final ConcurrentLinkedQueue<VerificationCode> codesToSend = new ConcurrentLinkedQueue<>();
    private final Lock waitLock = new ReentrantLock();
    private final Condition newMessageCondition = waitLock.newCondition();
    private final ExecutorService discoveryExecutor =
            Executors.newSingleThreadExecutor(Thread.ofVirtual().name("CommunicatorDiscoveryThread").factory());

    private final List<Consumer<ConnectionState>> stateChangeCallbacks = new ArrayList<>();
    private Consumer<Instant> onExpiryReceived = x -> {};

    private ConnectionState connectionState;
    private int heartbeatCounter = 0;
    private Socket socket;

    private void handshake() throws IOException {

        LOGGER.log(Level.INFO, "Attempting to connect with host " + socket.getInetAddress().getHostAddress());

        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();

        // compose handshake message
        JsonObject handshakeMessage = new JsonObject();
        handshakeMessage.addProperty("type", "connect");
        System.out.println(handshakeMessage);
        out.write(handshakeMessage.toString().getBytes(StandardCharsets.UTF_8));

        // expect correct response
        byte[] bytes = new byte[1024];
        int read = in.read(bytes);
        if (read < 1) {
            throw new IOException("Got no data??");
        }
        bytes = Arrays.copyOf(bytes, read);

        if (!socket.isConnected()) {
            this.setState(ConnectionState.DISCONNECTED);
            LOGGER.log(Level.SEVERE, "Failed to connect with host!");
        }

        IOException exceptionToThrow = new IOException("Handshake failure - incorrect response given." +
                " Is something else on port " + PORT + "?");

        String dataRaw = new String(bytes, StandardCharsets.UTF_8);
        JsonElement element;
        try {
            element = JsonParser.parseString(dataRaw);
        } catch (JsonParseException e) {
            throw exceptionToThrow;
        }

        if (!element.isJsonObject()) throw exceptionToThrow;
        JsonObject handshakeResponse = element.getAsJsonObject();

        if (
                !"acknowledge".equals(handshakeResponse.get("type").getAsString()) ||
                !"connect".equals(handshakeResponse.get("targeting").getAsString())
        ) {
            this.setState(ConnectionState.DISCONNECTED);
            throw exceptionToThrow;
        }
        LOGGER.log(Level.INFO, "Connected");

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
     * @param maxTries How many times to try and discover a host before failing (0 for infinite tries). Must be >= 0. Applies per-host if knownGoodHosts is provided.
     * @param knownGoodHosts An array of known good hosts, or null if there are none and discovery should be used.
     * @throws IOException if there is an issue connecting to or communicating with the server.
     */
    public void connect(int maxTries, String[] knownGoodHosts) throws IOException {

        this.setState(ConnectionState.DISCONNECTED);

        if (maxTries < 0)
            throw new IllegalArgumentException("maxTries must be a positive number or 0, got " + maxTries);

        if (knownGoodHosts == null || knownGoodHosts.length == 0)
            connectWithDiscovery("255.255.255.255", maxTries);
        else {
            this.setState(ConnectionState.SEARCHING_FOR_HOSTS);
            boolean couldConnect = false;
            for (String s : knownGoodHosts) {
                try {
                    connectWithDiscovery(s, maxTries);
                    couldConnect = true;
                    break;
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Could not connect to host " + s + ", trying again");
                }
            }
            if (!couldConnect) {
                LOGGER.log(Level.SEVERE, "No good host found!");
                this.setState(ConnectionState.DISCONNECTED);
            }
        }

    }

    public ConnectionState getState() {
        return this.connectionState;
    }

    public void sendCode(VerificationCode code) {
        System.out.println("add");
        this.codesToSend.add(code);
        System.out.println("signal");
        this.waitLock.lock();
        this.newMessageCondition.signalAll();
    }
    public Instant sendCodeAndWait(VerificationCode code, long timeoutMillis) throws InterruptedException {
        System.out.println("enter send");
        Consumer<Instant> oldAction = this.onExpiryReceived;
        final Instant[] valueHolder = {null};
        final Lock lock = new ReentrantLock();
        final Condition lockCondition = lock.newCondition();


        System.out.println("setup done");
        this.setOnExpiryReceived(i -> {
            System.out.println("!!got value");
            valueHolder[0] = i;
            System.out.println("!!signal");
            lockCondition.signalAll();
        });
        System.out.println("before code sent");
        this.sendCode(code);
        System.out.println("code sent");
        lock.lock();

        do {
            System.out.println("enter wait loop");
            if (timeoutMillis <= 0) {
                lockCondition.await();
            } else {
                boolean val = lockCondition.await(timeoutMillis, TimeUnit.MILLISECONDS);
                if (!val) return null;
            }
        } while (valueHolder[0] == null);
        System.out.println("done");
        this.setOnExpiryReceived(oldAction);

        return valueHolder[0];

    }

    public void registerConnectionStateListener(Consumer<ConnectionState> callback) {
        this.stateChangeCallbacks.add(callback);
    }
    public void setOnExpiryReceived(Consumer<Instant> callback) {
        this.onExpiryReceived = callback;
    }

    private void setState(ConnectionState state) {
        this.connectionState = state;
        LOGGER.log(Level.INFO, "Changed state to " + this.getState());
        // trigger state listeners
        synchronized (stateChangeCallbacks) {
            for (Consumer<ConnectionState> c : stateChangeCallbacks) {
                c.accept(this.connectionState);
            }
        }
    }

    public void communicate() throws IOException {
        this.setState(ConnectionState.CONNECTED_GOOD);
        this.waitLock.lock();
        while (true) {
            // wait 10s-ish
            try {
                // ignoring inspection because we don't really care how we exited
                // all the condition is is a trigger to run a loop
                // we check the real condition (are there codes to send
                //noinspection ResultOfMethodCallIgnored
                this.newMessageCondition.await(10_000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return;
            }

            // make sure we are still connected
            if (this.socket.isClosed() || !this.socket.isConnected()) {
                LOGGER.log(Level.WARNING, "Remote host disconnected");
                this.setState(ConnectionState.DISCONNECTED);
                return;
            }

            // check to see if there are codes to send
            if (!codesToSend.isEmpty()) {
                // send
                VerificationCode code = codesToSend.poll();
                JsonObject message = new JsonObject();
                message.addProperty("type", "code");
                message.addProperty("code", code.value());
                message.addProperty("generation_time", code.time.getEpochSecond());

                int attempts = 0;
                while (true) {
                    attempts++;
                    this.socket.getOutputStream().write(message.toString().getBytes(StandardCharsets.UTF_8));

                    // wait for acknowledgement
                    int oldTimeout = this.socket.getSoTimeout();
                    this.socket.setSoTimeout(STRICT_SO_TIMEOUT /* ms */);

                    byte[] data = new byte[1024];
                    int read = this.socket.getInputStream().read(data);
                    if (read == -1) {
                        continue; // did not respond within timeout
                    }
                    data = Arrays.copyOf(data, read);

                    JsonObject response = checkAcknowledgement(data, "code");
                    this.socket.setSoTimeout(oldTimeout);

                    // trigger expiry callback
                    this.onExpiryReceived.accept(Instant.ofEpochSecond(response.get("valid_to").getAsLong()));

                    if (attempts >= 3) {
                        this.setState(ConnectionState.DISCONNECTED);
                        LOGGER.log(Level.SEVERE, "Lost connection with remote");
                        return;
                    }
                    // good
                    break;

                }

            } else {

                // we just hit the timeout
                // send heartbeat (preincremented)
                JsonObject message = new JsonObject();
                message.addProperty("type", "heartbeat");
                message.addProperty("counter", this.heartbeatCounter);

                // try up to 3 times
                int attempts = 0;
                while (true) {
                    attempts++;
                    this.socket.getOutputStream().write(message.toString().getBytes(StandardCharsets.UTF_8));

                    // wait for acknowledgement
                    int oldTimeout = this.socket.getSoTimeout();
                    this.socket.setSoTimeout(STRICT_SO_TIMEOUT /* ms */);

                    byte[] data = new byte[1024];
                    int read = socket.getInputStream().read(data);
                    if (read < 1) {
                        this.setState(ConnectionState.CONNECTED_PROBLEM);
                        continue;
                    }
                    data = Arrays.copyOf(data, read);

                    JsonObject response = checkAcknowledgement(data, "heartbeat");
                    if (response.get("counter") == null) {
                        this.setState(ConnectionState.DISCONNECTED);
                        throw new IOException("Invalid response to heartbeat");
                    }
                    if (this.heartbeatCounter != response.get("counter").getAsInt()) {
                        // correct counter
                        // made a method
                        correctHeartbeat(this.heartbeatCounter);
                    }
                    this.socket.setSoTimeout(oldTimeout);

                    if (attempts >= 3) {
                        this.setState(ConnectionState.DISCONNECTED);
                        LOGGER.log(Level.SEVERE, "Lost connection with remote");
                        return;
                    }

                    // success
                    break;
                }
                heartbeatCounter++;

            }

        }
    }

    private JsonObject checkAcknowledgement(byte[] data, String type) throws IOException {
        JsonElement responseRaw = JsonParser.parseString(
                new String(data, StandardCharsets.UTF_8)
        );
        if (!responseRaw.isJsonObject()) {
            this.setState(ConnectionState.DISCONNECTED);
            throw new IOException("Remote sent invalid response to code (not a JsonObject)");
        }

        // try to parse
        JsonObject response = responseRaw.getAsJsonObject();
        if (response.get("type") == null || response.get("targeting") == null ||
                !response.get("type").getAsString().equals("acknowledge") ||
                !response.get("targeting").getAsString().equals(type)) {
            this.setState(ConnectionState.DISCONNECTED);
            throw new IOException("Remote sent invalid response to code (not an acknowledge/wrong target)");
        }
        return response;
    }

    private void correctHeartbeat(int counter) throws IOException {
        JsonObject message = new JsonObject();
        message.addProperty("type", "heartbeat_error");
        message.addProperty("counter", counter);
        this.socket.getOutputStream().write(message.toString().getBytes(StandardCharsets.UTF_8));

        // expect right value
        byte[] data = new byte[1024];
        int read = this.socket.getInputStream().read(data);
        data = Arrays.copyOf(data, read);
        checkAcknowledgement(data, "heartbeat_error");
    }

    private void connectWithDiscovery(String host, int maxTries) throws IOException {

        this.setState(ConnectionState.SEARCHING_FOR_HOSTS);

        // set up to accept any connections
        Future<Socket> future = discoveryExecutor.submit(() -> {
            try (ServerSocket s = new ServerSocket(PORT)) {
                return s.accept();
            }
        });

        DatagramSocket datagramSocket = new DatagramSocket();

        // broadcast to find any available servers
        datagramSocket.connect(new InetSocketAddress(host, PORT));

        // look for hosts
        int tries = 0;

        JsonObject object = new JsonObject();
        object.addProperty("app", "attendance");
        object.addProperty("type", "discovery");
        object.addProperty("version", BaseApplication.VERSION);
        object.addProperty("host", InetAddress.getLocalHost().getHostAddress());

        byte[] message = object.toString().getBytes(StandardCharsets.UTF_8);

        while ((tries < maxTries || maxTries == 0)) {
            DatagramPacket packet = new DatagramPacket(message, message.length);
            datagramSocket.send(packet);
            tries++;
            try {
                // if the server connects (future call does not throw) break
                future.get(INTERVAL, TimeUnit.MILLISECONDS);
                // here it must be done
                break;
            } catch (InterruptedException e) {
                throw new IOException("No host found (interrupted)");
            } catch (ExecutionException e) {
                if (e.getCause() instanceof IOException) {
                    throw (IOException) e.getCause();
                } else throw new IllegalStateException("Unreachable state: unexpected exception", e);
            } catch (TimeoutException e) {
                // if the server does not connect, try again
                // pass
            }
        }

        datagramSocket.close();

        // we have a connection, or we ran out of tries
        if (!future.isDone()) {
            this.setState(ConnectionState.DISCONNECTED);
            // ran out of tries
            throw new IOException("No host found (%d attempts)".formatted(tries));
        }

        // otherwise get the socket
        try {
            this.setState(ConnectionState.CONNECTING);
            socket = future.get();
        } catch (InterruptedException e) {
            this.setState(ConnectionState.DISCONNECTED);
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            this.setState(ConnectionState.DISCONNECTED);
            throw new IOException("Error occurred while connecting to server", e);
        }

        handshake();

    }

}
