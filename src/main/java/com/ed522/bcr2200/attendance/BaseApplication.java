package com.ed522.bcr2200.attendance;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public class BaseApplication extends Application {

    public static final long VERSION = 0x1;

    private static String[] allowedHosts = new String[0];

    public static String[] getAllowedHosts() {
        return Arrays.copyOf(allowedHosts, allowedHosts.length);
    }

    /**
     * Parse command line arguments, storing them away when necessary.
     * @param args full arguments array
     * @param index the index to start parsing from
     * @return the index of the next unparsed argument - suitable to pass into another invocation
     */
    private int parseArguments(String[] args, int index) {
        Objects.requireNonNull(args, "arg array must be non null");
        if (index >= args.length)
            return -1; // so a method can just loop until -1 is received
        if (index == 0)
            return 1; // skip argv[0], which is just the executable name
        return switch (args[index]) {
            case null -> throw new IllegalArgumentException("One of the args was null");
            case "--host", "--hosts", "-h" -> {
                // consume host + an actual hostname
                String hosts = args[index + 1];
                Objects.requireNonNull(hosts, "Argument null?");
                allowedHosts = hosts.split(",");
                yield index + 2;
            }
            default -> {
                Logger.getLogger("Application").log(Level.WARNING,
                        "Got unrecognized command line argument - ignoring");
                yield index + 1;
            }
        };
    }

    @Override
    public void start(Stage stage) throws IOException {

        Parameters params = this.getParameters();
        String[] args = params.getRaw().toArray(new String[0]);

        int index = 0;
        while (index != -1) {
            index = parseArguments(args, index);
        }

        FXMLLoader fxmlLoader = new FXMLLoader(BaseApplication.class.getResource("code-view.fxml"));
        Scene scene = new Scene(fxmlLoader.load(), 800, 480);

        scene.addEventHandler(KeyEvent.KEY_PRESSED, k -> {
            if (k.getCode() == KeyCode.F11) {
                stage.setFullScreen(!stage.isFullScreen());
            }
        });

        stage.setOnCloseRequest(x -> System.exit(0));

        stage.setTitle("Attendance Client");
        stage.setFullScreen(true);
        stage.setScene(scene);
        stage.show();
    }
}
