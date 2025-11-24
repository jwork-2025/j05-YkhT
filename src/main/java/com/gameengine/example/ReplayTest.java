package com.gameengine.example;

import com.gameengine.core.GameEngine;

public class ReplayTest {
    public static void main(String[] args) {
        GameEngine engine = new GameEngine(800, 600, "ReplayTest");
        // choose an existing recording file from recordings/ to test replay
        String rec = "recordings/hulusnake-1763820688929.jsonl";
        if (args != null && args.length > 0 && args[0] != null && !args[0].isEmpty()) {
            rec = args[0];
        }
        engine.setScene(new ReplayScene(engine, rec));
        engine.run();
    }
}
