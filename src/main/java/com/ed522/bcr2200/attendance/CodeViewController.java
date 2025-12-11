package com.ed522.bcr2200.attendance;

import static com.ed522.bcr2200.attendance.io.AttendanceEndpoint.VerificationCode;

import com.ed522.bcr2200.attendance.io.AttendanceEndpoint;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.ImageView;

import java.io.IOException;
import java.security.SecureRandom;
import java.sql.Date;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoField;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public class CodeViewController {

    public static final int MAX_TRIES = 0;

    /**
     * A callback for a task that should be run periodically.
     * @param initialCounter The counter value to set initially. Zero signifies a task to run every tick
     * @param callback The task to run, with the first argument being the tick delay (in milliseconds)
     */
    private record TimedTask(int initialCounter, Consumer<Integer> callback) {}

    private static final Logger LOGGER = Logger.getLogger("CodeView");
    public static final int TICK_DELAY_MS = 100;

    @FXML private Label codeLabel1;
    @FXML private Label codeLabel2;
    @FXML private Label codeLabel3;
    @FXML private Label time;
    @FXML private ProgressBar expiryBar;
    @FXML private ImageView networkGood;
    @FXML private ImageView networkWarn;
    @FXML private ImageView networkErr;

    private final ScheduledExecutorService timedEventExecutor = Executors.newScheduledThreadPool(1);
    private final ExecutorService communicatorExecutor = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
    private final Map<TimedTask, Integer> timedEvents = new HashMap<TimedTask, Integer>();
    private final SecureRandom random = new SecureRandom();
    private final AttendanceEndpoint server = new AttendanceEndpoint();

    private Instant expiryInstant = Instant.MIN;
    private Instant generationInstant = Instant.MIN;

    private void executeTimedTasks() {

        // go through each task
        // - if zero, run it and reset
        // then decrement

        synchronized (timedEvents) {
            for (TimedTask task : timedEvents.keySet()) {
                if (this.timedEvents.get(task) <= 0) {
                    task.callback.accept(TICK_DELAY_MS);
                    this.timedEvents.put(task, task.initialCounter);
                }
                this.timedEvents.put(task, this.timedEvents.get(task) - 1);
            }
        }

    }

    private VerificationCode generateCode(Instant generationInstant) throws InterruptedException {
        // six digits
        int code = random.nextInt(100_000_000);
        Instant expiry = this.server.sendCodeAndWait(new VerificationCode(code, generationInstant), -1);
        return new VerificationCode(code, expiry);
    }

    private void updateExpiryBar(int timeStep) {
        
        if (this.generationInstant.equals(Instant.MIN) || this.expiryInstant.equals(Instant.MIN)) {
            return;
        }

        double expiryTimeDelta = this.expiryInstant.minus(
                Duration.ofMillis(this.generationInstant.toEpochMilli())
        ).get(ChronoField.MILLI_OF_DAY);
        double currentTimeDelta = Instant.now().minus(
                Duration.ofMillis(this.generationInstant.toEpochMilli())
        ).get(ChronoField.MILLI_OF_DAY);
        double progress = currentTimeDelta / expiryTimeDelta;

        Platform.runLater(() -> this.expiryBar.setProgress(progress));

    }
    private void updateTime(int unused) {
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String value = formatter.format(Date.from(Instant.now()));
        Platform.runLater(() -> this.time.setText(value));
    }
    private void updateCodes(int unused) {

        // check if they are expired
        if (this.expiryInstant.isBefore(Instant.now())) {
            // regenerate
            this.generationInstant = Instant.now();
            VerificationCode code;
            // if we get interrupted then give up early
            try {
                code = this.generateCode(generationInstant);
            } catch (InterruptedException e) {
                return;
            }
            this.expiryInstant = code.time();
            LOGGER.log(Level.FINE, "Generated new code " + code.value());
        }

    }

    private void registerTask(Consumer<Integer> task, long intervalMs) {
        TimedTask realTask = new TimedTask((int) Math.floor((double) intervalMs / (double) TICK_DELAY_MS), task);
        this.timedEvents.put(realTask, realTask.initialCounter);
    }

    @FXML
    public void initialize() {

        Future<?> task = this.communicatorExecutor.submit(() -> {

        });

        this.expiryBar.setProgress(0d);

        this.registerTask(this::updateExpiryBar, 0L);
        this.registerTask(this::updateTime, 500L);
        this.registerTask(this::updateCodes, 0L);

        // set up threads

        timedEventExecutor.scheduleAtFixedRate(
                this::executeTimedTasks, 0 /* ms initial delay */,
                TICK_DELAY_MS /* ms, per tick */, TimeUnit.MILLISECONDS
        );

        this.communicatorExecutor.submit(() -> {

            // always run this loop, when JVM terminates then this thread will die (daemon)

            // an infinite loop is kind of the point
            //noinspection InfiniteLoopStatement
            while (true) {
                // second loop: keep trying to connect (even with IOException) until it works
                while (true) try {
                    this.server.connect(MAX_TRIES, BaseApplication.getAllowedHosts());
                    break;
                } catch (IOException e1) {
                    LOGGER.log(Level.SEVERE, "Got an IOException, trying again: " + e1.getMessage());
                }

                if (!task.isDone())
                    throw new IllegalStateException("Not connected yet but second thread is running? - not trying again");
                else try {
                    this.server.communicate();
                } catch (IOException e) {
                    LOGGER.log(Level.SEVERE, "Got an IOException! " + e.getMessage());
                }
            }
        });

    }

}
