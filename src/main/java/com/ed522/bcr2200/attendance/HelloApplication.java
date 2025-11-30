package com.ed522.bcr2200.attendance;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

import java.io.IOException;

public class HelloApplication extends Application {
    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(HelloApplication.class.getResource("hello-view.fxml"));
        Scene scene = new Scene(fxmlLoader.load(), 640, 480);

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
