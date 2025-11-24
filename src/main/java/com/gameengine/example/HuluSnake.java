package com.gameengine.example;

import com.gameengine.core.GameEngine;

/**
 * A simple snake-like game "Hulu Snake" built on the existing engine.
 * - Seeds: randomly spawn with one of seven colors; eating grows one segment with the color
 * - Monsters: moving obstacles; touching them resets
 * - Collisions: self/wall/monster cause reset (per current requirement)
 */
public class HuluSnake {
    public static void main(String[] args) {
        GameEngine engine = new GameEngine(800, 600, "贪吃葫芦 (Hulu Snake)");

        engine.setScene(new MenuScene(engine, "menu"));
        engine.run();
    }

    // Nested helper types (static so they are accessible without an instance)
    static enum Dir { LEFT, RIGHT, UP, DOWN }

    static class Color {
        float r,g,b,a;
        Color(float r, float g, float b, float a) { this.r=r; this.g=g; this.b=b; this.a=a; }
    }

    static class Seg {
        int x,y; Color color;
        Seg(int x, int y, Color c) { this.x=x; this.y=y; this.color=c; }
    }

    static class Seed {
        int gx, gy; Color color;
        Seed(int gx, int gy, Color color) { this.gx=gx; this.gy=gy; this.color=color; }
    }

    static class Monster {
        float x,y; float vx, vy; float size;
    }
}
