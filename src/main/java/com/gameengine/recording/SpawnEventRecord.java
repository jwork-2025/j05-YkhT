package com.gameengine.recording;

/**
 * Lightweight record type representing spawn/destroy events parsed from recordings.
 */
public class SpawnEventRecord {
    public double t;
    public String id;
    public int gx;
    public int gy;
    public float[] color; // nullable
    public boolean spawn; // true = spawn, false = destroy

    public SpawnEventRecord(double t, String id, int gx, int gy, float[] color, boolean spawn) {
        this.t = t;
        this.id = id;
        this.gx = gx;
        this.gy = gy;
        this.color = color;
        this.spawn = spawn;
    }
}
