package com.ed522.bcr2200.attendance;

import com.ed522.bcr2200.attendance.io.AttendanceEndpoint;
import javafx.application.Application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;

public class Launcher {

    public static void main(String[] args) {
        Application.launch(BaseApplication.class, args);
    }
    public static void main1(String... args) throws IOException {
        // test console app
        System.out.println("Starting...");
        AttendanceEndpoint endpoint = new AttendanceEndpoint();
        System.out.println("Now discovering");
        endpoint.connect(0, null);
        System.out.println("Discovered!");
        Thread thread = new Thread(() -> {
            try {
                endpoint.communicate();
            } catch (IOException e) {
                throw new RuntimeException("died", e);
            }
        });
        thread.start();
        System.out.println("Connected!");

        // loop
        // yes i do in fact want an infinite loop
        //noinspection InfiniteLoopStatement
        while (true) {
            System.out.print("Enter a code: ");
            byte[] data = new byte[1024];
            int read = System.in.read(data);
            data = Arrays.copyOf(data, read);

            String in = new String(data, StandardCharsets.UTF_8).strip().trim();
            Instant instant = Instant.now();
            long code;
            try {
                code = Long.parseUnsignedLong(in);
            } catch (NumberFormatException e) {
                System.out.println("bad code");
                continue;
            }
            // send
            System.out.println("Sending code " + code);
            endpoint.sendCode(new AttendanceEndpoint.VerificationCode(code, instant));
            System.out.println("Sent code " + code);
        }
    }
}
