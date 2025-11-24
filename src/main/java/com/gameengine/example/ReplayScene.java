package com.gameengine.example;

import com.gameengine.components.RenderComponent;
import com.gameengine.components.TransformComponent;
import com.gameengine.core.GameObject;
import com.gameengine.core.GameEngine;
import com.gameengine.graphics.Renderer;
import com.gameengine.math.Vector2;
import com.gameengine.recording.FileRecordingStorage;
import com.gameengine.recording.RecordingJson;
import com.gameengine.recording.RecordingStorage;
import com.gameengine.scene.Scene;
import java.io.File;
import java.io.IOException;
import java.util.*;

public class ReplayScene extends Scene {
    private final GameEngine engine;
    private final Renderer renderer;
    private final RecordingStorage storage = new FileRecordingStorage();
    private final String path; // null -> show file list

    private final List<Keyframe> keyframes = new ArrayList<>();
    private final Map<String, GameObject> objects = new HashMap<>();
    private final java.util.List<GameObject> objectList = new ArrayList<>();
    // pending removals: id -> scheduled removal time (seconds)
    private final Map<String, Double> pendingRemovals = new HashMap<>();
    // input events loaded from recording
    private static class InputEvent { double t; int[] keys; boolean release = false; }
    private final List<InputEvent> inputEvents = new ArrayList<>();
    private int nextInputIndex = 0;
    private double time = 0.0;
    private boolean playing = false;
    private Long recordingSeed = null;
    private Integer recordingWidth = null;
    private Integer recordingHeight = null;
    private static final boolean DEBUG_REPLAY = Boolean.getBoolean("replay.debug");

    // file list UI
    private List<File> recordings = new ArrayList<>();
    private int selectedIndex = 0;

    public ReplayScene(GameEngine engine, String path) {
        super("replay");
        this.engine = engine;
        this.renderer = engine.getRenderer();
        this.path = path;
    }

    @Override
    public void initialize() {
        super.initialize();
        recordings = storage.listRecordings();
        selectedIndex = 0;
        time = 0.0;
        playing = false;
        keyframes.clear();
        objects.clear();
        if (path != null) {
            loadRecording(path);
        }
    }

    @Override
    public void update(float deltaTime) {
        super.update(deltaTime);
        if (!playing) {
            // file list selection mode
            if (path == null) {
                // navigation by keyboard
                if (engine.getInputManager().isKeyJustPressed(38)) selectedIndex = Math.max(0, selectedIndex - 1);
                if (engine.getInputManager().isKeyJustPressed(40)) selectedIndex = Math.min(recordings.size() - 1, selectedIndex + 1);
                if (engine.getInputManager().isKeyJustPressed(10) || engine.getInputManager().isMouseButtonJustPressed(0)) {
                    if (!recordings.isEmpty()) {
                            File f = recordings.get(selectedIndex);
                            loadRecording(f.getAbsolutePath());
                            return;
                        }
                }
            } else {
                // playback finished and waiting -> any key or click returns to menu
                if (engine.getInputManager().isKeyJustPressed(10) || engine.getInputManager().isMouseButtonJustPressed(0)) {
                    engine.setScene(new MenuScene(engine, "menu"));
                }
            }
            return;
        }

            // playback
            time += deltaTime;
            if (keyframes.isEmpty()) return;
            // dispatch input events scheduled up to current time
            while (nextInputIndex < inputEvents.size() && inputEvents.get(nextInputIndex).t <= time) {
                InputEvent ie = inputEvents.get(nextInputIndex);
                for (int k : ie.keys) {
                    if (ie.release) {
                        engine.getInputManager().onKeyReleased(k);
                        if (DEBUG_REPLAY) System.out.println(String.format("[Replay] t=%.3f input keyReleased=%d", time, k));
                    } else {
                        engine.getInputManager().onKeyPressed(k);
                        if (DEBUG_REPLAY) System.out.println(String.format("[Replay] t=%.3f input keyPressed=%d", time, k));
                    }
                }
                nextInputIndex++;
            }
            // process pending removals that expired
            Iterator<Map.Entry<String, Double>> itRem = pendingRemovals.entrySet().iterator();
            while (itRem.hasNext()) {
                Map.Entry<String, Double> e = itRem.next();
                String rid = e.getKey();
                double when = e.getValue();
                if (time >= when) {
                    GameObject robj = objects.get(rid);
                    if (robj != null) {
                        if (DEBUG_REPLAY) System.out.println(String.format("[Replay] t=%.3f id=%s removed (delayed)", time, rid));
                        robj.setActive(false);
                        objects.remove(rid);
                    }
                    itRem.remove();
                }
            }
            double lastT = keyframes.get(keyframes.size() - 1).t;
            if (time > lastT) { playing = false; return; }

            // find interval
            int idx = 0;
            while (idx + 1 < keyframes.size() && keyframes.get(idx + 1).t < time) idx++;
            Keyframe a = keyframes.get(idx);
            Keyframe b = (idx + 1 < keyframes.size()) ? keyframes.get(idx + 1) : a;
            double dt = b.t - a.t;
            double alpha = (dt <= 0.0) ? 0.0 : ((time - a.t) / dt);
            // clamp interpolation factor to [0,1] to avoid extrapolation artifacts
            if (alpha < 0.0) alpha = 0.0;
            if (alpha > 1.0) alpha = 1.0;

            // 处理链式实体（seg0, seg1 ...）使用索引对齐，保证顺序一致，避免尾巴割裂
            List<EntityInfo> segA = new ArrayList<>(), segB = new ArrayList<>();
            for (EntityInfo ei : a.entities) if (isSegmentId(ei.id)) segA.add(ei);
            for (EntityInfo ei : b.entities) if (isSegmentId(ei.id)) segB.add(ei);
            int segN = Math.min(segA.size(), segB.size());
            for (int i = 0; i < segN; i++) {
                EntityInfo ea = segA.get(i);
                EntityInfo eb = segB.get(i);
                double[] xy = interpolateManhattan(ea.x, ea.y, eb.x, eb.y, alpha);
                float x = (float)xy[0], y = (float)xy[1];
                String id = ea.id != null ? ea.id : (eb.id != null ? eb.id : ("seg"+i));
                GameObject obj = objects.get(id);
                if (obj == null) {
                    obj = buildObjectFromEntity(id, eb != null ? eb.rt : ea.rt, (float)ea.w, (float)ea.h, ea.color);
                    if (obj != null) {
                        if (!obj.hasComponent(TransformComponent.class)) obj.addComponent(new TransformComponent(new Vector2(x,y)));
                        else obj.getComponent(TransformComponent.class).setPosition(new Vector2(x,y));
                        addGameObject(obj);
                        objects.put(id, obj);
                    }
                } else {
                    TransformComponent tc = obj.getComponent(TransformComponent.class);
                    if (tc != null) tc.setPosition(new Vector2(x,y));
                }
            }
            // handle leftover segments present only in a (disappear) or only in b (appear)
            if (segA.size() > segN) {
                for (int i = segN; i < segA.size(); i++) {
                    String id = segA.get(i).id;
                    GameObject obj = objects.get(id);
                    if (obj != null) {
                        // schedule delayed removal to avoid flicker, only if not already scheduled
                        if (!pendingRemovals.containsKey(id)) {
                            double when = time + 0.25; // 0.25s grace
                            pendingRemovals.put(id, when);
                            if (DEBUG_REPLAY) System.out.println(String.format("[Replay] t=%.3f seg %s scheduled removal at %.3f", time, id, when));
                        }
                    }
                }
            }
            if (segB.size() > segN) {
                for (int i = segN; i < segB.size(); i++) {
                    EntityInfo eb = segB.get(i);
                    String id = eb.id != null ? eb.id : ("seg"+i);
                    if (!objects.containsKey(id)) {
                        GameObject obj = buildObjectFromEntity(id, eb.rt, (float)eb.w, (float)eb.h, eb.color);
                        if (obj != null) { obj.addComponent(new TransformComponent(new Vector2((float)eb.x, (float)eb.y))); addGameObject(obj); objects.put(id, obj); if (DEBUG_REPLAY) System.out.println(String.format("[Replay] t=%.3f seg %s created", time, id)); }
                        // cancel any pending removal for this id
                        pendingRemovals.remove(id);
                    }
                }
            }

            // Use index-based update (fixed object list from first keyframe) to avoid id-mapping drift
            updateInterpolatedPositions(a, b, alpha);
            return;
    }

    @Override
    public void render() {
        if (renderer == null) return;
        renderer.drawRect(0,0,renderer.getWidth(), renderer.getHeight(), 0.15f,0.15f,0.18f,1.0f);
        if (!playing) {
            if (path == null) {
                renderFileList();
            } else {
                renderer.drawText(20, 40, "回放结束，点击任意处返回菜单", 1f,1f,1f,1f);
            }
            return;
        }
        super.render();
    }

    private void renderFileList() {
        renderer.drawText(20, 20, "请选择要回放的存档：", 1f,1f,1f,1f);
        int y = 60;
        for (int i = 0; i < recordings.size(); i++) {
            File f = recordings.get(i);
            if (i == selectedIndex) renderer.drawRect(10, y - 6, renderer.getWidth() - 20, 26, 0.25f,0.25f,0.35f,0.9f);
            renderer.drawText(20, y, f.getName(), 0.95f,0.95f,0.95f,1f);
            y += 34;
        }
        if (recordings.isEmpty()) renderer.drawText(20, 80, "没有找到 recordings 目录或没有可回放的文件", 1f,0.5f,0.5f,1f);
    }

    private void loadRecording(String p) {
        try {
            keyframes.clear();
            inputEvents.clear();
            nextInputIndex = 0;
            recordingSeed = null;
            Iterable<String> lines = storage.readLines(p);
            for (String line : lines) {
                String t = RecordingJson.stripQuotes(RecordingJson.field(line, "type"));
                if (t == null) continue;
                if ("header".equals(t)) {
                    String seedField = RecordingJson.field(line, "seed");
                    if (seedField != null) {
                        double sd = RecordingJson.parseDouble(seedField);
                        recordingSeed = (long) sd;
                        if (DEBUG_REPLAY) System.out.println("[Replay] recording seed=" + recordingSeed);
                    }
                    // read recorded viewport size if present
                    String wField = RecordingJson.field(line, "w");
                    String hField = RecordingJson.field(line, "h");
                    if (wField != null) {
                        try { recordingWidth = (int)RecordingJson.parseDouble(wField); } catch (Exception ignored) {}
                    }
                    if (hField != null) {
                        try { recordingHeight = (int)RecordingJson.parseDouble(hField); } catch (Exception ignored) {}
                    }
                    continue;
                }
                if ("keyframe".equals(t)) {
                    double time = RecordingJson.parseDouble(RecordingJson.field(line, "t"));
                    String arr = RecordingJson.extractArray(line, line.indexOf("\"entities\""));
                    String[] ents = RecordingJson.splitTopLevel(arr);
                    Keyframe kf = new Keyframe(time);
                    for (String ent : ents) {
                        String id = RecordingJson.stripQuotes(RecordingJson.field(ent, "id"));
                        double x = RecordingJson.parseDouble(RecordingJson.field(ent, "x"));
                        double y = RecordingJson.parseDouble(RecordingJson.field(ent, "y"));
                        String rt = RecordingJson.stripQuotes(RecordingJson.field(ent, "rt"));
                        double w = RecordingJson.parseDouble(RecordingJson.field(ent, "w"));
                        double h = RecordingJson.parseDouble(RecordingJson.field(ent, "h"));
                        float[] col = null;
                        int colorIdx = ent.indexOf("\"color\"");
                        if (colorIdx >= 0) {
                            String carr = RecordingJson.extractArray(ent, colorIdx);
                            String[] cs = carr.split(",");
                            if (cs.length >= 4) {
                                col = new float[4];
                                for (int i = 0; i < 4; i++) col[i] = (float)RecordingJson.parseDouble(cs[i]);
                            }
                        }
                        EntityInfo ei = new EntityInfo(id, x, y, rt, w, h, col);
                        kf.entities.add(ei);
                    }
                    keyframes.add(kf);
                } else if ("input".equals(t)) {
                        double it = RecordingJson.parseDouble(RecordingJson.field(line, "t"));
                        String actionField = RecordingJson.stripQuotes(RecordingJson.field(line, "action"));
                        String ks = RecordingJson.extractArray(line, line.indexOf("\"keys\""));
                        ks = ks.trim();
                        if (ks.startsWith("[")) ks = ks.substring(1);
                        if (ks.endsWith("]")) ks = ks.substring(0, ks.length()-1);
                        String[] parts = ks.split(",");
                        java.util.List<Integer> list = new java.util.ArrayList<>();
                        for (String pstr : parts) {
                            pstr = pstr.trim();
                            if (pstr.isEmpty()) continue;
                            try { list.add(Integer.parseInt(pstr)); } catch (Exception ex) { }
                        }
                        if (!list.isEmpty()) {
                            InputEvent ie = new InputEvent(); ie.t = it; ie.keys = new int[list.size()];
                            for (int i = 0; i < list.size(); i++) ie.keys[i] = list.get(i);
                            if (actionField != null && actionField.equalsIgnoreCase("release")) ie.release = true;
                            inputEvents.add(ie);
                        }
                    }
            }
            keyframes.sort(Comparator.comparingDouble(a -> a.t));
            inputEvents.sort((a,b) -> Double.compare(a.t, b.t));
            // parse spawn/destroy events separately for simulation mode
            java.util.List<com.gameengine.recording.SpawnEventRecord> spawnEvents = new java.util.ArrayList<>();
            // Re-iterate lines to collect spawn/destroy (simpler than mixing earlier)
            Iterable<String> lines2 = storage.readLines(p);
            for (String line2 : lines2) {
                String tt = RecordingJson.stripQuotes(RecordingJson.field(line2, "type"));
                if (tt == null) continue;
                if ("spawn".equals(tt)) {
                    double st = RecordingJson.parseDouble(RecordingJson.field(line2, "t"));
                    // extract entity object by matching braces
                    int entKey = line2.indexOf("\"entity\"");
                    if (entKey < 0) continue;
                    int brace = line2.indexOf('{', entKey);
                    if (brace < 0) continue;
                    int depth = 0; int i = brace;
                    for (; i < line2.length(); i++) {
                        char ch = line2.charAt(i);
                        if (ch == '{') depth++; else if (ch == '}') { depth--; if (depth == 0) break; }
                    }
                    if (i <= brace) continue;
                    String ent = line2.substring(brace, i+1);
                    String id = RecordingJson.stripQuotes(RecordingJson.field(ent, "id"));
                    int gx = (int)RecordingJson.parseDouble(RecordingJson.field(ent, "gx"));
                    int gy = (int)RecordingJson.parseDouble(RecordingJson.field(ent, "gy"));
                    float[] col = null;
                    int colorIdx = ent.indexOf("\"color\"");
                    if (colorIdx >= 0) {
                        String carr = RecordingJson.extractArray(ent, colorIdx);
                        String[] cs = carr.split(",");
                        if (cs.length >= 3) {
                            col = new float[4];
                            for (int k = 0; k < Math.min(3, cs.length); k++) col[k] = (float)RecordingJson.parseDouble(cs[k]);
                            col[3] = (cs.length >= 4) ? (float)RecordingJson.parseDouble(cs[3]) : 1.0f;
                        }
                    }
                    spawnEvents.add(new com.gameengine.recording.SpawnEventRecord(st, id, gx, gy, col, true));
                } else if ("destroy".equals(tt)) {
                    double st = RecordingJson.parseDouble(RecordingJson.field(line2, "t"));
                    String id = RecordingJson.stripQuotes(RecordingJson.field(line2, "id"));
                    spawnEvents.add(new com.gameengine.recording.SpawnEventRecord(st, id, 0, 0, null, false));
                }
            }
            spawnEvents.sort((a,b) -> Double.compare(a.t, b.t));
            // Normalize timeline so the first keyframe starts at t=0.0 (prevents initial extrapolation)
            if (!keyframes.isEmpty()) {
                double t0 = keyframes.get(0).t;
                if (t0 != 0.0) {
                    for (Keyframe k : keyframes) k.t -= t0;
                    for (InputEvent ie : inputEvents) ie.t -= t0;
                    for (com.gameengine.recording.SpawnEventRecord se : spawnEvents) se.t -= t0;
                }
            }
            // If recording contains RNG seed, switch to simulation mode: construct a HuluSnakeScene with seed and input events
            if (recordingSeed != null) {
                java.util.List<com.gameengine.recording.InputEventRecord> recs = new java.util.ArrayList<>();
                for (InputEvent iev : inputEvents) {
                    recs.add(new com.gameengine.recording.InputEventRecord(iev.t, iev.keys, iev.release));
                }
                if (DEBUG_REPLAY) System.out.println("[Replay] launching simulation mode with seed=" + recordingSeed + " events=" + recs.size() + " spawns=" + spawnEvents.size());
                if (recordingWidth != null && recordingHeight != null) {
                    engine.setScene(new HuluSnakeScene(engine, recordingSeed, recs, spawnEvents, recordingWidth, recordingHeight));
                } else {
                    engine.setScene(new HuluSnakeScene(engine, recordingSeed, recs, spawnEvents));
                }
                return;
            }
            if (DEBUG_REPLAY) {
                System.out.println("[Replay] loaded keyframes=" + keyframes.size() + " from " + p);
                for (int i = 0; i < keyframes.size(); i++) {
                    Keyframe k = keyframes.get(i);
                    System.out.println(String.format("[Replay] k[%d] t=%.3f ents=%d", i, k.t, k.entities.size()));
                }
                System.out.println("[Replay] loaded input events=" + inputEvents.size());
                for (int i = 0; i < inputEvents.size(); i++) {
                    System.out.println(String.format("[Replay] in[%d] t=%.3f keys=%s", i, inputEvents.get(i).t, java.util.Arrays.toString(inputEvents.get(i).keys)));
                }
            }
            time = 0.0;
            playing = true;
            // clear any existing game objects in scene
            clear();
            objects.clear();
            // build fixed object list from the first keyframe to avoid id/index drift
            buildObjectsFromFirstKeyframe();
        } catch (IOException e) {
            e.printStackTrace();
            playing = false;
        }
    }

    private GameObject buildObjectFromEntity(String id, String rt, float w, float h, float[] col) {
        GameObject obj = new GameObject(id != null ? id : UUID.randomUUID().toString());
        // ensure transform
        TransformComponent tc = new TransformComponent(new Vector2(0,0));
        obj.addComponent(tc);
        // render component
        float rw = (w > 0) ? w : 16f;
        float rh = (h > 0) ? h : 16f;
        float r = 1f, g = 1f, b = 1f, a = 1f;
        if (col != null && col.length >= 4) { r = col[0]; g = col[1]; b = col[2]; a = col[3]; }
        RenderComponent.RenderType rtType = RenderComponent.RenderType.RECTANGLE;
        if (rt != null) {
            String s = rt.trim().toUpperCase();
            if ("CIRCLE".equals(s)) rtType = RenderComponent.RenderType.CIRCLE;
            else if ("LINE".equals(s)) rtType = RenderComponent.RenderType.LINE;
            else rtType = RenderComponent.RenderType.RECTANGLE;
        }
        RenderComponent rc = new RenderComponent(rtType, new Vector2(rw, rh), new RenderComponent.Color(r, g, b, a));
        rc.setRenderer(renderer);
        obj.addComponent(rc);
        return obj;
    }

    /**
     * 曼哈顿风格插值：优先沿一个轴移动，再沿另一个轴移动，避免斜线移动。
     * 返回长度为 2 的数组 {x,y}
     */
    private static double[] interpolateManhattan(double ax, double ay, double bx, double by, double t) {
        double dx = bx - ax;
        double dy = by - ay;
        double adx = Math.abs(dx);
        double ady = Math.abs(dy);
        if (adx <= 1e-6 && ady <= 1e-6) return new double[]{ax, ay};
        if (adx <= 1e-6) {
            // only y changes
            double y = ay + (by - ay) * t;
            return new double[]{ax, y};
        }
        if (ady <= 1e-6) {
            // only x changes
            double x = ax + (bx - ax) * t;
            return new double[]{x, ay};
        }
        double man = adx + ady;
        double px = adx / man; // proportion of total distance used by x
        if (t <= px) {
            double frac = (px <= 0.0) ? 0.0 : (t / px);
            double x = ax + dx * frac;
            return new double[]{x, ay};
        } else {
            double frac = (1.0 - px <= 0.0) ? 1.0 : ((t - px) / (1.0 - px));
            double x = bx;
            double y = ay + dy * frac;
            return new double[]{x, y};
        }
    }

    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    private void ensureObjectCount(int n) {
        while (objectList.size() < n) {
            GameObject obj = new GameObject("RObj#" + objectList.size());
            obj.addComponent(new TransformComponent(new Vector2(0, 0)));
            addGameObject(obj);
            objectList.add(obj);
        }
        while (objectList.size() > n) {
            GameObject obj = objectList.remove(objectList.size() - 1);
            obj.setActive(false);
        }
    }

    private void buildObjectsFromFirstKeyframe() {
        objectList.clear();
        if (keyframes.isEmpty()) return;
        Keyframe k0 = keyframes.get(0);
        clear();
        for (int i = 0; i < k0.entities.size(); i++) {
            EntityInfo ei = k0.entities.get(i);
            GameObject obj = buildObjectFromEntity(ei.id, ei.rt, (float)ei.w, (float)ei.h, ei.color);
            if (obj == null) {
                obj = new GameObject(ei.id != null ? ei.id : ("RObj#" + i));
                obj.addComponent(new TransformComponent(new Vector2((float)ei.x, (float)ei.y)));
                addGameObject(obj);
            } else {
                if (!obj.hasComponent(TransformComponent.class)) obj.addComponent(new TransformComponent(new Vector2((float)ei.x, (float)ei.y)));
                else obj.getComponent(TransformComponent.class).setPosition(new Vector2((float)ei.x, (float)ei.y));
                addGameObject(obj);
            }
            objectList.add(obj);
            if (ei.id != null) objects.put(ei.id, obj);
        }
    }

    private void updateInterpolatedPositions(Keyframe a, Keyframe b, double alpha) {
        // Build id->entity maps for robust matching (prefer id matching over index matching)
        java.util.Map<String, EntityInfo> mapA = new java.util.HashMap<>();
        java.util.Map<String, EntityInfo> mapB = new java.util.HashMap<>();
        for (EntityInfo ei : a.entities) if (ei != null && ei.id != null) mapA.put(ei.id, ei);
        for (EntityInfo ei : b.entities) if (ei != null && ei.id != null) mapB.put(ei.id, ei);

        int n = Math.max(a.entities.size(), b.entities.size());
        ensureObjectCount(n);

        // build name->object map for objectList for quick lookup
        java.util.Map<String, GameObject> nameToObj = new java.util.HashMap<>();
        for (GameObject go : objectList) if (go != null) nameToObj.put(go.getName(), go);

        for (int i = 0; i < n; i++) {
            EntityInfo ea = (i < a.entities.size()) ? a.entities.get(i) : null;
            EntityInfo eb = (i < b.entities.size()) ? b.entities.get(i) : null;

            // prefer matching by id if available
            String id = (ea != null && ea.id != null) ? ea.id : (eb != null ? eb.id : null);
            EntityInfo useA = null, useB = null;
            if (id != null) {
                useA = mapA.getOrDefault(id, ea);
                useB = mapB.getOrDefault(id, eb);
            } else {
                useA = ea; useB = eb;
            }

            double x = 0, y = 0;
            if (useA != null && useB != null) {
                if (isSegmentId(useA.id) || isSegmentId(useB.id)) {
                    double[] xy = interpolateManhattan(useA.x, useA.y, useB.x, useB.y, alpha);
                    x = xy[0]; y = xy[1];
                } else {
                    x = lerp(useA.x, useB.x, alpha);
                    y = lerp(useA.y, useB.y, alpha);
                }
            } else if (useB != null) {
                x = useB.x; y = useB.y;
            } else if (useA != null) {
                x = useA.x; y = useA.y;
            }

            GameObject obj = null;
            if (id != null) obj = nameToObj.get(id);
            if (obj == null) {
                // fallback to positional mapping in objectList
                if (i < objectList.size()) obj = objectList.get(i);
            }

            if (obj != null) {
                TransformComponent tc = obj.getComponent(TransformComponent.class);
                if (tc == null) { obj.addComponent(new TransformComponent(new Vector2((float)x, (float)y))); }
                else tc.setPosition(new Vector2((float)x, (float)y));
                if (id != null) objects.put(id, obj);
            }
        }
        // schedule removal for extras present in a but not in b
        if (a.entities.size() > n) {
            for (int i = n; i < a.entities.size(); i++) {
                String id = a.entities.get(i).id;
                if (id != null && !pendingRemovals.containsKey(id) && objects.containsKey(id)) {
                    double when = time + 0.25;
                    pendingRemovals.put(id, when);
                    if (DEBUG_REPLAY) System.out.println(String.format("[Replay] t=%.3f id=%s scheduled removal at %.3f", time, id, when));
                }
            }
        }
    }

    private static class Keyframe {
        double t;
        List<EntityInfo> entities = new ArrayList<>();
        Keyframe(double t) { this.t = t; }
    }

    private static class EntityInfo {
        String id; double x,y; String rt; double w,h; float[] color;
        EntityInfo(String id, double x, double y, String rt, double w, double h, float[] color) {
            this.id = id; this.x = x; this.y = y; this.rt = rt; this.w = w; this.h = h; this.color = color;
        }
    }

    private static boolean isSegmentId(String id) {
        if (id == null) return false;
        return id.startsWith("seg") || id.startsWith("segment") || id.matches("seg\\d+");
    }
}
