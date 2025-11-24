package com.gameengine.example;

import com.gameengine.core.GameEngine;
import com.gameengine.input.InputManager;

public class AutoPlayRecorder {
    public static void main(String[] args) {
        GameEngine engine = new GameEngine(800, 600, "AutoPlayRecorder");
        HuluSnakeScene scene = new HuluSnakeScene(engine, true); // autoRecord = true
        engine.setScene(scene);
        engine.run();

        // start input simulation in background
        Thread inputThread = new Thread(() -> {
            try {
                InputManager im = InputManager.getInstance();
                Thread.sleep(800);
                // sequence: move RIGHT a few steps, then DOWN, LEFT, UP
                // press RIGHT
                im.onKeyPressed(39); // Right
                Thread.sleep(900);
                im.onKeyReleased(39);
                Thread.sleep(200);
                im.onKeyPressed(40); // Down
                Thread.sleep(900);
                im.onKeyReleased(40);
                Thread.sleep(200);
                im.onKeyPressed(37); // Left
                Thread.sleep(900);
                im.onKeyReleased(37);
                Thread.sleep(200);
                im.onKeyPressed(38); // Up
                Thread.sleep(900);
                im.onKeyReleased(38);
                // random wiggle for a few seconds
                for (int i = 0; i < 10; i++) {
                    im.onKeyPressed(39); Thread.sleep(150); im.onKeyReleased(39);
                    Thread.sleep(100);
                    im.onKeyPressed(40); Thread.sleep(150); im.onKeyReleased(40);
                    Thread.sleep(100);
                }
                // wait a bit and then stop engine
                Thread.sleep(2000);
                System.out.println("[AutoPlayRecorder] stopping engine");
                engine.stop();
                // give some time to flush recording
                Thread.sleep(500);
                System.exit(0);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "AutoPlayInputThread");
        inputThread.setDaemon(true);
        inputThread.start();
    }
}
