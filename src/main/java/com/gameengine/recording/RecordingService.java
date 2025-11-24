
package com.gameengine.recording;

import com.gameengine.components.TransformComponent;
import com.gameengine.core.GameObject;
import com.gameengine.input.InputManager;
import com.gameengine.scene.Scene;

import java.io.IOException;
import java.text.DecimalFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class RecordingService {
    private final RecordingConfig config;
    private final BlockingQueue<String> lineQueue;
    private volatile boolean recording;
    private Thread writerThread;
    private RecordingStorage storage = new FileRecordingStorage();
    private double elapsed;
    private double keyframeElapsed;
    private java.util.Set<Integer> prevPressedSnapshot = new java.util.HashSet<>();
    private double sampleAccumulator;
    private final double warmupSec = 0.1; // 等待一帧让场景对象完成初始化
    private final DecimalFormat qfmt;
    private final java.util.Map<String, double[]> lastPositions = new java.util.HashMap<>();
    private Scene lastScene;

    public RecordingService(RecordingConfig config) {
        this.config = config;
        this.lineQueue = new ArrayBlockingQueue<>(config.queueCapacity);
        this.recording = false;
        this.elapsed = 0.0;
        this.keyframeElapsed = 0.0;
        this.sampleAccumulator = 0.0;
        this.qfmt = new DecimalFormat();
        this.qfmt.setMaximumFractionDigits(Math.max(0, config.quantizeDecimals));
        this.qfmt.setGroupingUsed(false);
    }

    public boolean isRecording() {
        return recording;
    }

    public void start(Scene scene, int width, int height) throws IOException {
        if (recording) return;
        storage.openWriter(config.outputPath);
        writerThread = new Thread(() -> {
            try {
                while (recording || !lineQueue.isEmpty()) {
                    String s = lineQueue.poll();
                    if (s == null) {
                        try { Thread.sleep(2); } catch (InterruptedException ignored) {}
                        continue;
                    }
                    storage.writeLine(s);
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                try { storage.closeWriter(); } catch (Exception ignored) {}
            }
        }, "record-writer");
        recording = true;
        writerThread.start();

        // header (include optional RNG seed if scene exposes it via getRecordingSeed())
        long seedValue = -1L;
        try {
            java.lang.reflect.Method m = scene.getClass().getMethod("getRecordingSeed");
            Object rv = m.invoke(scene);
            if (rv instanceof Number) seedValue = ((Number) rv).longValue();
        } catch (NoSuchMethodException ignored) {
        } catch (Exception ignored) {
        }
        StringBuilder hdr = new StringBuilder();
        hdr.append("{\"type\":\"header\",\"version\":1,\"w\":").append(width).append(",\"h\":").append(height);
        if (seedValue >= 0) hdr.append(",\"seed\":").append(seedValue);
        hdr.append('}');
        enqueue(hdr.toString());
        keyframeElapsed = 0.0;
    }

    // expose elapsed recording time for external event timestamps
    public double getElapsed() { return this.elapsed; }

    // allow external code to write arbitrary json lines into the recording
    public void recordRaw(String jsonLine) {
        if (!recording) return;
        enqueue(jsonLine);
    }

    public void stop() {
        if (!recording) return;
        try {
            if (lastScene != null) {
                writeKeyframe(lastScene);
            }
        } catch (Exception ignored) {}
        recording = false;
        try { writerThread.join(500); } catch (InterruptedException ignored) {}
    }

    public void update(double deltaTime, Scene scene, InputManager input) {
        if (!recording) return;
        elapsed += deltaTime;
        keyframeElapsed += deltaTime;
        sampleAccumulator += deltaTime;
        lastScene = scene;

        // input events (sample at native frequency)
        java.util.Set<Integer> currentPressed = input.getPressedKeysSnapshot();
        // detect newly pressed
        java.util.Set<Integer> newlyPressed = new java.util.HashSet<>(currentPressed);
        newlyPressed.removeAll(prevPressedSnapshot);
        if (!newlyPressed.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"type\":\"input\",\"t\":").append(fmt(elapsed)).append(",\"action\":\"press\",\"keys\":[");
            boolean first = true;
            for (Integer k : newlyPressed) {
                if (!first) sb.append(',');
                sb.append(k);
                first = false;
            }
            sb.append("]}");
            enqueue(sb.toString());
        }
        // detect released keys
        java.util.Set<Integer> released = new java.util.HashSet<>(prevPressedSnapshot);
        released.removeAll(currentPressed);
        if (!released.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"type\":\"input\",\"t\":").append(fmt(elapsed)).append(",\"action\":\"release\",\"keys\":[");
            boolean first = true;
            for (Integer k : released) {
                if (!first) sb.append(',');
                sb.append(k);
                first = false;
            }
            sb.append("]}");
            enqueue(sb.toString());
        }
        prevPressedSnapshot = currentPressed;

        // sampled deltas placeholder（可扩展）：此处先跳过，保持最小版本

        // periodic keyframe（跳过开头暖机，避免空关键帧）
        if (elapsed >= warmupSec && keyframeElapsed >= config.keyframeIntervalSec) {
            if (writeKeyframe(scene)) {
                keyframeElapsed = 0.0;
            }
        }
    }

    private boolean writeKeyframe(Scene scene) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"keyframe\",\"t\":").append(fmt(elapsed)).append(",\"entities\":[");
        List<GameObject> objs = scene.getGameObjects();
        // ensure deterministic ordering: sort by object name to avoid index drift between keyframes
        java.util.List<GameObject> sorted = new java.util.ArrayList<>();
        for (GameObject o : objs) {
            if (o.getComponent(TransformComponent.class) != null) sorted.add(o);
        }
        sorted.sort((a,b) -> {
            String an = a.getName() == null ? "" : a.getName();
            String bn = b.getName() == null ? "" : b.getName();
            return an.compareTo(bn);
        });
        boolean first = true;
        int count = 0;
        for (GameObject obj : sorted) {
            TransformComponent tc = obj.getComponent(TransformComponent.class);
            if (tc == null) continue;
            float x = tc.getPosition().x;
            float y = tc.getPosition().y;
            // position threshold filtering: skip entities with negligible movement
            String objName = obj.getName();
            boolean skip = false;
            if (objName != null) {
                double[] prev = lastPositions.get(objName);
                if (prev != null) {
                    double dx = x - prev[0];
                    double dy = y - prev[1];
                    double dist = Math.hypot(dx, dy);
                    if (dist < config.positionThreshold) skip = true;
                }
            }
            if (skip) continue;
            if (!first) sb.append(',');
            sb.append('{')
              .append("\"id\":\"").append(obj.getName()).append("\",")
              .append("\"x\":").append(fmt(x)).append(',')
              .append("\"y\":").append(fmt(y));

            // 可选渲染信息（若对象带有 RenderComponent，则记录形状、尺寸、颜色）
            com.gameengine.components.RenderComponent rc = obj.getComponent(com.gameengine.components.RenderComponent.class);
            if (rc != null) {
                com.gameengine.components.RenderComponent.RenderType rt = rc.getRenderType();
                com.gameengine.math.Vector2 sz = rc.getSize();
                com.gameengine.components.RenderComponent.Color col = rc.getColor();
                sb.append(',')
                  .append("\"rt\":\"").append(rt.name()).append("\",")
                  .append("\"w\":").append(fmt(sz.x)).append(',')
                  .append("\"h\":").append(fmt(sz.y)).append(',')
                  .append("\"color\":[")
                  .append(fmt(col.r)).append(',')
                  .append(fmt(col.g)).append(',')
                  .append(fmt(col.b)).append(',')
                  .append(fmt(col.a)).append(']');
            } else {
                // 标记自定义渲染（如 Player），方便回放做近似还原
                sb.append(',').append("\"rt\":\"CUSTOM\"");
            }

            sb.append('}');
            first = false;
            count++;
            // update last recorded position for this object
            if (objName != null) lastPositions.put(objName, new double[]{x, y});
        }
        sb.append("]}");
        if (count == 0) return false;
        enqueue(sb.toString());
        return true;
    }

    private void enqueue(String line) {
        if (!lineQueue.offer(line)) {
            // 简单丢弃策略：队列满时丢弃低优先级数据（此处直接丢弃）
        }
    }

    // format numbers using Locale.US to ensure dot as decimal separator
    private String fmt(double v) {
        try {
            return String.format(Locale.US, "%." + Math.max(0, config.quantizeDecimals) + "f", v);
        } catch (Exception e) {
            // fallback
            return Double.toString(v);
        }
    }
}
