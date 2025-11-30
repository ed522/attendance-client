package com.ed522.bcr2200.attendance;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.ImageView;

import java.sql.Date;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class CodeViewController {

    public static final int TICK_DELAY_MS = 100;

    @FXML private Label mainLabel;
    @FXML private Label time;
    @FXML private ProgressBar expiryBar;
    @FXML private ImageView networkGood;
    @FXML private ImageView networkWarn;
    @FXML private ImageView networkErr;

    ScheduledExecutorService timedEvents = Executors.newScheduledThreadPool(1);
    ExecutorService communicator = Executors.newSingleThreadExecutor();

    private void executeTimedTasks() {

        // for now just increment the progressbar
        // reset if it's one (or within epsilon = 0.001)
        if (this.expiryBar.getProgress() >= 1d - 0.001d) this.expiryBar.setProgress(0d);
        double progress = this.expiryBar.getProgress() + (((double) TICK_DELAY_MS) / 1000) / 30;
        Platform.runLater(() -> this.expiryBar.setProgress(progress));

        // update time
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String value = formatter.format(Date.from(Instant.now()));
        Platform.runLater(() -> this.time.setText(value));

    }

    @FXML
    public void initialize() {
        this.expiryBar.setProgress(0d);
        // set up threads
        timedEvents.scheduleAtFixedRate(this::executeTimedTasks, 0 /* ms initial delay */, TICK_DELAY_MS /* ms, per tick */, TimeUnit.MILLISECONDS);
        System.out.println("inc = " + (((double) TICK_DELAY_MS) / 1000) / 30);
    }

}
