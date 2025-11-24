package com.gameengine.recording;

public class InputEventRecord {
    public double t;
    public int[] keys;
    public boolean release = false;
    public InputEventRecord() {}
    public InputEventRecord(double t, int[] keys) { this.t = t; this.keys = keys; }
    public InputEventRecord(double t, int[] keys, boolean release) { this.t = t; this.keys = keys; this.release = release; }
}
