package com.gameengine.example;

import com.gameengine.graphics.Renderer;
import com.gameengine.input.InputManager;
import com.gameengine.scene.Scene;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.gameengine.core.GameEngine;
import com.gameengine.math.Vector2;
import com.gameengine.recording.RecordingConfig;
import com.gameengine.recording.RecordingService;
import com.gameengine.recording.InputEventRecord;

public class HuluSnakeScene extends Scene {
    private Renderer renderer;
    private InputManager input;
    private Random random;
    private long recordingSeed = -1L;

    // Recording
    private RecordingConfig recordingConfig;
    private RecordingService recordingService;

    private int worldW, worldH;
    private int cell = 20;              // grid cell size
    private int cols, rows;

    private Dir dir = Dir.RIGHT;        // current direction
    private Dir pendingDir = Dir.RIGHT; // next direction set by input
    private int headX, headY;           // head position in grid coords
    private List<Seg> body;             // snake body segments (no head)

    private List<Seed> seeds;
    private int seedCounter = 0;
    private float seedTimer = 0f;
    private float seedIntervalMin = 1.5f; // slower spawn
    private float seedIntervalMax = 3.0f; // slower spawn
    private float nextSeedIn = 2.0f;      // initial slower spawn
    private int seedSpawnMargin = 2;       // keep seeds away from walls by N grid cells

    private List<Monster> monsters;
    private ExecutorService executor;

    private float stepTime = 0.12f;     // seconds per step
    private float stepAcc = 0f;

    // game over state
    private boolean gameOver = false;
    private String gameOverReason = "";

    private final Color[] sevenColors = new Color[] {
        new Color(1.0f, 0.0f, 0.0f, 1.0f), // red
        new Color(1.0f, 0.5f, 0.0f, 1.0f), // orange
        new Color(1.0f, 1.0f, 0.0f, 1.0f), // yellow
        new Color(0.0f, 1.0f, 0.0f, 1.0f), // green
        new Color(0.0f, 1.0f, 1.0f, 1.0f), // cyan
        new Color(0.0f, 0.5f, 1.0f, 1.0f), // blue
        new Color(0.6f, 0.2f, 1.0f, 1.0f)  // purple
    };

    private final GameEngine engine;
    private final boolean autoRecord;
    // optional override for world size when running simulation replay from a recording
    private int overrideWorldW = -1;
    private int overrideWorldH = -1;
    // replay simulation mode
    private boolean simulateMode = false;
    private java.util.List<InputEventRecord> replayEvents = null;
    private int nextReplayIndex = 0;
    private double replayTime = 0.0;
    // spawn/destroy events parsed from recording (simulation mode)
    private java.util.List<com.gameengine.recording.SpawnEventRecord> spawnEvents = null;
    private int nextSpawnIndex = 0;
    // small time epsilon to avoid missing events due to floating point/frame timing
    private static final double TIME_EPS = 1e-3;
    // maps built from recording spawnEvents for runtime verification
    private java.util.Map<String, Double> recordedDestroyTimes = new java.util.HashMap<>();
    private java.util.Map<String, int[]> recordedSpawnPos = new java.util.HashMap<>();

    public HuluSnakeScene(GameEngine engine) {
        this(engine, false);
    }

    // Construct a scene for simulation replay with a fixed RNG seed and scheduled input events
    public HuluSnakeScene(GameEngine engine, long seed, java.util.List<InputEventRecord> replayEvents) {
        this(engine, false);
        this.recordingSeed = seed;
        this.random = new java.util.Random(seed);
        this.replayEvents = replayEvents;
        this.simulateMode = true;
        this.nextReplayIndex = 0;
        this.replayTime = 0.0;
        this.spawnEvents = null;
        this.nextSpawnIndex = 0;
    }

    // Same as above but with explicit world size override (recorded viewport)
    public HuluSnakeScene(GameEngine engine, long seed, java.util.List<InputEventRecord> replayEvents, int overrideWorldW, int overrideWorldH) {
        this(engine, seed, replayEvents);
        this.overrideWorldW = overrideWorldW;
        this.overrideWorldH = overrideWorldH;
    }

    // Construct a scene for simulation replay with seed, input events and spawn/destroy events
    public HuluSnakeScene(GameEngine engine, long seed, java.util.List<InputEventRecord> replayEvents, java.util.List<com.gameengine.recording.SpawnEventRecord> spawnEvents) {
        this(engine, seed, replayEvents);
        this.spawnEvents = spawnEvents;
        this.nextSpawnIndex = 0;
    }

    // Same as above but with explicit world size override (recorded viewport)
    public HuluSnakeScene(GameEngine engine, long seed, java.util.List<InputEventRecord> replayEvents, java.util.List<com.gameengine.recording.SpawnEventRecord> spawnEvents, int overrideWorldW, int overrideWorldH) {
        this(engine, seed, replayEvents, spawnEvents);
        this.overrideWorldW = overrideWorldW;
        this.overrideWorldH = overrideWorldH;
    }

    public HuluSnakeScene(GameEngine engine, boolean autoRecord) {
        super("HuluSnakeScene");
        this.engine = engine;
        this.autoRecord = autoRecord;
    }

    

    @Override
    public void initialize() {
        super.initialize();
        renderer = engine.getRenderer();
        input = InputManager.getInstance();
        // use a reproducible seed for recording/replay
        // If this scene was constructed for simulation mode the seed/random
        // are already provided by the constructor and must not be overwritten.
        if (this.recordingSeed < 0L) {
            recordingSeed = System.currentTimeMillis();
        }
        if (this.random == null) {
            random = new Random(recordingSeed);
        }
        executor = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() - 1));
        // use override size when replaying a recording with recorded viewport dimensions
        worldW = (overrideWorldW > 0) ? overrideWorldW : renderer.getWidth();
        worldH = (overrideWorldH > 0) ? overrideWorldH : renderer.getHeight();
        // Use ceiling division so partial cells at the right/bottom
        // edges are treated as playable columns/rows. This fixes
        // inaccurate right/bottom wall collision when width/height
        // are not exact multiples of `cell`.
        cols = (worldW + cell - 1) / cell;
        rows = (worldH + cell - 1) / cell;

        body = new ArrayList<>();
        seeds = new ArrayList<>();
        monsters = new ArrayList<>();

        try {
            recordingConfig = new RecordingConfig("recordings/hulusnake-" + System.currentTimeMillis() + ".jsonl");
            recordingService = new RecordingService(recordingConfig);
            // only auto-start recording when explicitly requested and not running a simulation replay
            if (autoRecord && recordingService != null && !this.simulateMode) {
                recordingService.start(this, renderer.getWidth(), renderer.getHeight());
            }
        } catch (Exception ignored) {}

        // (replay debug prints use System.out.printf)

        resetGame();
    }

    // Expose seed for recording service (optional)
    public long getRecordingSeed() { return recordingSeed; }

    @Override
    public void update(float deltaTime) {
        super.update(deltaTime);
        // If running in simulation replay mode, advance replay clock and inject scheduled input events
        if (simulateMode && replayEvents != null && !replayEvents.isEmpty()) {
            replayTime += deltaTime;
            InputManager im = InputManager.getInstance();
            while (nextReplayIndex < replayEvents.size() && replayEvents.get(nextReplayIndex).t <= replayTime + TIME_EPS) {
                com.gameengine.recording.InputEventRecord er = replayEvents.get(nextReplayIndex);
                if (er.keys != null) {
                    for (int k : er.keys) {
                        if (er.release) im.onKeyReleased(k);
                        else im.onKeyPressed(k);
                    }
                }
                nextReplayIndex++;
            }
            // process spawn/destroy events from recording (simulate exact spawns)
            if (spawnEvents != null) {
                while (nextSpawnIndex < spawnEvents.size() && spawnEvents.get(nextSpawnIndex).t <= replayTime + TIME_EPS) {
                    com.gameengine.recording.SpawnEventRecord se = spawnEvents.get(nextSpawnIndex);
                    if (se.spawn) {
                        // create seed with recorded id and position/color
                        float cr = 1.0f, cg = 1.0f, cb = 1.0f, ca = 1.0f;
                        if (se.color != null) {
                            cr = se.color[0]; cg = se.color[1]; cb = se.color[2]; ca = (se.color.length>3?se.color[3]:1.0f);
                        }
                        Seed s = new Seed(se.id, se.gx, se.gy, new Color(cr, cg, cb, ca));
                        seeds.add(s);
                        System.out.printf("[ReplayDebug] applied spawnEvent t=%.3f id=%s gx=%d gy=%d replayTime=%.3f seedCounter=%d\n",
                            se.t, se.id, se.gx, se.gy, replayTime, seedCounter);
                        // keep seedCounter in sync with recorded numeric ids (seedN)
                        try {
                            if (se.id != null && se.id.startsWith("seed")) {
                                String num = se.id.substring(4);
                                int n = Integer.parseInt(num);
                                seedCounter = Math.max(seedCounter, n+1);
                            }
                        } catch (Exception ignored) {}
                    } else {
                        // NOTE: do NOT remove the seed here in simulation mode.
                        // The original destroy record is produced when the snake actually consumed the seed
                        // during its step. If we remove it now, the simulation's step won't find the seed
                        // and growth will not occur. Keep the destroy event for logging/verification only.
                        System.out.printf("[ReplayDebug] recorded destroyEvent (ignored removing) t=%.3f id=%s replayTime=%.3f seedCounter=%d\n",
                            se.t, se.id, replayTime, seedCounter);
                    }
                    nextSpawnIndex++;
                }
            }
            // build verification maps from spawnEvents for later checks
            try {
                if (spawnEvents != null) {
                    for (com.gameengine.recording.SpawnEventRecord se : spawnEvents) {
                        if (se.spawn) recordedSpawnPos.put(se.id, new int[]{se.gx, se.gy});
                        else recordedDestroyTimes.put(se.id, se.t);
                    }
                }
            } catch (Exception ignored) {}
        }

        try {
            // Recording is now controlled by menu (autoRecord) instead of R key.
            if (recordingService != null) recordingService.update(deltaTime, this, input);
        } catch (Exception ignored) {}

        // If game over, wait for input to return to menu
        if (gameOver) {
            // allow click or Enter to return to menu
            if (input.isMouseButtonJustPressed(0)) {
                Vector2 mp = input.getMousePosition();
                int w = renderer.getWidth(); int h = renderer.getHeight();
                float cx = w / 2.0f; float cy = h / 2.0f + 60;
                float bw = 220; float bh = 50;
                if (mp.x >= cx - bw/2 && mp.x <= cx + bw/2 && mp.y >= cy - bh/2 && mp.y <= cy + bh/2) {
                    engine.setScene(new MenuScene(engine, "menu"));
                }
            }
            if (input.isKeyJustPressed(10) || input.isKeyJustPressed(32)) {
                engine.setScene(new MenuScene(engine, "menu"));
            }
            return;
        }

        Dir newDir = pendingDir;
        if (input.isKeyPressed(37) || input.isKeyPressed(65)) newDir = Dir.LEFT;   // Left or A
        else if (input.isKeyPressed(39) || input.isKeyPressed(68)) newDir = Dir.RIGHT; // Right or D
        else if (input.isKeyPressed(38) || input.isKeyPressed(87)) newDir = Dir.UP;    // Up or W
        else if (input.isKeyPressed(40) || input.isKeyPressed(83)) newDir = Dir.DOWN;  // Down or S
        if (!isOpposite(dir, newDir)) pendingDir = newDir;

        if (!monsters.isEmpty()) {
            List<Monster> snapshot = new ArrayList<>(monsters);
            int threadCount = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
            int batchSize = Math.max(1, snapshot.size() / threadCount + 1);
            List<Future<?>> futures = new ArrayList<>();

            for (int i = 0; i < snapshot.size(); i += batchSize) {
                final int start = i;
                final int end = Math.min(i + batchSize, snapshot.size());
                futures.add(executor.submit(() -> {
                    for (int j = start; j < end; j++) {
                        Monster m = snapshot.get(j);
                        m.x += m.vx * deltaTime;
                        m.y += m.vy * deltaTime;
                        if (m.x < 0) { m.x = 0; m.vx = Math.abs(m.vx); }
                        if (m.y < 0) { m.y = 0; m.vy = Math.abs(m.vy); }
                        if (m.x > worldW - m.size) { m.x = worldW - m.size; m.vx = -Math.abs(m.vx); }
                        if (m.y > worldH - m.size) { m.y = worldH - m.size; m.vy = -Math.abs(m.vy); }
                    }
                }));
            }

            for (Future<?> f : futures) {
                try { f.get(); } catch (Exception e) { e.printStackTrace(); }
            }
        }

        if (headHitsMonster()) { triggerGameOver("被妖精撞到"); return; }

        seedTimer += deltaTime;
        if (seedTimer >= nextSeedIn) {
            spawnSeed();
            seedTimer = 0f;
            nextSeedIn = lerp(seedIntervalMin, seedIntervalMax, random.nextFloat());
        }

        stepAcc += deltaTime;
        if (stepAcc >= stepTime) {
            stepAcc -= stepTime;
            dir = pendingDir;
            int headBeforeX = headX, headBeforeY = headY;
            int nx = headX + (dir == Dir.LEFT ? -1 : dir == Dir.RIGHT ? 1 : 0);
            int ny = headY + (dir == Dir.UP ? -1 : dir == Dir.DOWN ? 1 : 0);

                        if (nx < 0 || ny < 0 || nx >= cols || ny >= rows) { triggerGameOver("碰到墙"); return; }
                    if (collideBody(nx, ny)) { triggerGameOver("缠绕自己"); return; }

            boolean grew = false; Color growColor = null;
            Iterator<Seed> it = seeds.iterator();
            String consumedSeedId = null;
            // Debug: list seeds at step time to verify presence/alignment
                try {
                StringBuilder sb = new StringBuilder(); int cnt=0;
                for (Seed ss : seeds) { sb.append(String.format("%s:(%d,%d) ", ss.id, ss.gx, ss.gy)); cnt++; if (cnt>20) break; }
                System.out.printf("[ReplayDebug] stepCheck replayTime=%.3f stepAcc=%.3f target=(%d,%d) seedsCount=%d list=%s\n",
                    replayTime, stepAcc, nx, ny, seeds.size(), sb.toString());
            } catch (Exception ignored) {}
            while (it.hasNext()) {
                Seed s = it.next();
                if (s.gx == nx && s.gy == ny) { consumedSeedId = s.id; it.remove(); grew = true; growColor = s.color; break; }
            }
            if (consumedSeedId == null) {
                System.out.printf("[ReplayDebug] stepCheck -> no seed found at target=(%d,%d) replayTime=%.3f stepAcc=%.3f seedsCount=%d\n",
                    nx, ny, replayTime, stepAcc, seeds.size());
            }
            if (consumedSeedId != null) {
                double elapsedForLog = -1.0;
                try { elapsedForLog = (recordingService != null ? recordingService.getElapsed() : replayTime); } catch (Exception ignored) {}
                System.out.printf("[ReplayDebug] consuming seed at step elapsed=%.3f consumedId=%s nx=%d ny=%d bodyBefore=%d simulate=%b headBefore=(%d,%d)\n",
                    elapsedForLog, consumedSeedId, nx, ny, body.size(), this.simulateMode, headBeforeX, headBeforeY);
                try {
                    if (recordingService != null && recordingService.isRecording()) {
                        double t2 = recordingService.getElapsed();
                        String json2 = String.format(java.util.Locale.US, "{\"type\":\"destroy\",\"t\":%.3f,\"id\":\"%s\"}", t2, consumedSeedId);
                        recordingService.recordRaw(json2);
                    }
                } catch (Exception ignored) {}
                // runtime verification: compare consumed id/time/position with recorded spawn/destroy info
                if (this.simulateMode) {
                    try {
                        Double recT = recordedDestroyTimes.get(consumedSeedId);
                        int[] recPos = recordedSpawnPos.get(consumedSeedId);
                        System.out.printf("[ReplayVerify] consumedId=%s replayTime=%.3f recordedDestroyT=%s recordedSpawn=%s target=(%d,%d)\n",
                            consumedSeedId, replayTime, (recT==null?"<none>":String.format("%.3f", recT)), (recPos==null?"<none>":String.format("(%d,%d)", recPos[0], recPos[1])), nx, ny);
                        // check if another id was recorded at this position
                        String expectedIdAtPos = null;
                        for (java.util.Map.Entry<String,int[]> e : recordedSpawnPos.entrySet()) {
                            int[] p = e.getValue(); if (p != null && p[0] == nx && p[1] == ny) { expectedIdAtPos = e.getKey(); break; }
                        }
                        if (expectedIdAtPos != null && !expectedIdAtPos.equals(consumedSeedId)) {
                            System.out.printf("[ReplayVerify] POS MISMATCH at (%d,%d): expected id=%s but consumed id=%s\n", nx, ny, expectedIdAtPos, consumedSeedId);
                        }
                    } catch (Exception ignored) {}
                }
            }

            int prevX = headX, prevY = headY;
            for (int i = 0; i < body.size(); i++) {
                Seg seg = body.get(i);
                int tmpX = seg.x, tmpY = seg.y;
                seg.x = prevX; seg.y = prevY;
                prevX = tmpX; prevY = tmpY;
            }

            if (grew) body.add(new Seg(prevX, prevY, growColor));
                if (grew) System.out.printf("[ReplayDebug] grew -> added seg at (%d,%d) newBodySize=%d (headBefore=(%d,%d) headAfter=(%d,%d))\n", prevX, prevY, body.size(), headBeforeX, headBeforeY, nx, ny);
            headX = nx; headY = ny;
                    if (headHitsMonster()) { triggerGameOver("被妖精撞到"); return; }
        }
    }

    @Override
    public void render() {
        renderer.drawRect(0, 0, worldW, worldH, 0.08f, 0.1f, 0.12f, 1.0f);

        for (Seed s : seeds) {
            int px = s.gx * cell; int py = s.gy * cell;
            renderer.drawRect(px + 2, py + 2, cell - 4, cell - 4, s.color.r, s.color.g, s.color.b, 1.0f);
        }

        for (Monster m : monsters) {
            renderer.drawCircle(m.x + m.size*0.5f, m.y + m.size*0.5f, m.size*0.5f, 16, 0.3f, 0.6f, 1.0f, 0.95f);
        }

        for (Seg seg : body) {
            int px = seg.x * cell; int py = seg.y * cell;
            int bW = cell - 4; int bH = Math.max(8, (int)Math.round(cell * 0.55f));
            int bX = px + (cell - bW) / 2; int bY = py + (cell - bH);
            renderer.drawRect(bX, bY, bW, bH, seg.color.r, seg.color.g, seg.color.b, 1.0f);
            int tW = Math.max(5, (int)Math.round(cell * 0.5f)); int tH = Math.max(5, cell - bH - 2);
            int tX = px + (cell - tW) / 2; int tY = py + 2;
            renderer.drawRect(tX, tY, tW, tH, seg.color.r, seg.color.g, seg.color.b, 1.0f);
        }

        int hx = headX * cell; int hy = headY * cell;
        int bottomW = cell; int bottomH = Math.max(10, (int)Math.round(cell * 0.62f));
        int bottomX = hx; int bottomY = hy + (cell - bottomH);
        renderer.drawRect(bottomX, bottomY, bottomW, bottomH, 1.0f, 0.0f, 0.0f, 1.0f);
        int topW = Math.max(6, (int)Math.round(cell * 0.6f)); int topH = Math.max(6, cell - bottomH);
        int topX = hx + (cell - topW) / 2; int topY = hy;
        renderer.drawRect(topX, topY, topW, topH, 1.0f, 0.0f, 0.0f, 1.0f);

        try {
            String status = "RECORD OFF";
            if (recordingService != null && recordingService.isRecording()) status = "REC RECORDING";
            renderer.drawText(12, 18, status, 0.7f, 0.9f, 0.6f, 1.0f);
        } catch (Exception ignored) {}

        // Game over overlay
        if (gameOver) {
            int w = renderer.getWidth(); int h = renderer.getHeight();
            renderer.drawRect(0, 0, w, h, 0f, 0f, 0f, 0.6f);
            float cx = w / 2.0f; float cy = h / 2.0f;
            String title = "GAME OVER";
            String reason = (gameOverReason == null || gameOverReason.isEmpty()) ? "" : (" - " + gameOverReason);
            renderer.drawText((int)(cx - title.length()*9), (int)(cy - 40), title + reason, 1.0f, 0.6f, 0.6f, 1.0f);

            // Return button
            float bw = 220; float bh = 50;
            float bx = cx - bw/2.0f; float by = cy + 60 - bh/2.0f;
            renderer.drawRect((int)bx, (int)by, (int)bw, (int)bh, 0.4f, 0.4f, 0.6f, 1.0f);
            renderer.drawText((int)(cx - "RETURN TO MENU".length()*6), (int)(cy + 60), "RETURN TO MENU", 1.0f, 1.0f, 1.0f, 1.0f);
        }
    }

    private void resetGame() {
        body.clear(); seeds.clear(); monsters.clear();
        headX = cols / 2; headY = rows / 2; dir = Dir.RIGHT; pendingDir = Dir.RIGHT;
        for (int i = 0; i < 3; i++) spawnSeed();
        for (int i = 0; i < 3; i++) spawnMonster();
        seedTimer = 0f; nextSeedIn = 2.0f; stepAcc = 0f;
    }

    private void triggerGameOver(String reason) {
        this.gameOver = true;
        this.gameOverReason = reason;
        // stop recording if active
        try {
            if (recordingService != null && recordingService.isRecording()) recordingService.stop();
        } catch (Exception ignored) {}
    }

    private void spawnSeed() {
        int margin = seedSpawnMargin;
        int minX = margin, maxX = cols - 1 - margin;
        int minY = margin, maxY = rows - 1 - margin;
        if (maxX < minX) { minX = 0; maxX = cols - 1; }
        if (maxY < minY) { minY = 0; maxY = rows - 1; }
        int gx = minX + random.nextInt(maxX - minX + 1);
        int gy = minY + random.nextInt(maxY - minY + 1);
        if (gx == headX && gy == headY) return;
        for (Seg s : body) if (s.x == gx && s.y == gy) return;
        int colorIdx = random.nextInt(sevenColors.length);
        Color c = sevenColors[colorIdx];
        // Debug: log RNG choices for spawnSeed to verify replay alignment
        System.out.printf("[ReplayDebug] spawnSeed RNG -> gx=%d gy=%d colorIdx=%d seedCounter=%d simulate=%b\n", gx, gy, colorIdx, seedCounter, this.simulateMode);
        // If this position collides with head or body, behave like original and abort (we already consumed RNG above)
        if (gx == headX && gy == headY) return;
        for (Seg s : body) if (s.x == gx && s.y == gy) return;
        String id = "seed" + (seedCounter++);
        if (this.simulateMode) {
            // In simulation mode we do NOT create the seed here (spawn events from the recording will create them at recorded times),
            // but we must keep RNG/seedCounter progression identical to the original, so we incremented seedCounter above.
            return;
        }
        Seed newSeed = new Seed(id, gx, gy, c);
        seeds.add(newSeed);
        try {
            if (recordingService != null && recordingService.isRecording()) {
                double t = recordingService.getElapsed();
                String json = String.format(java.util.Locale.US,
                    "{\"type\":\"spawn\",\"t\":%.3f,\"entity\":{\"id\":\"%s\",\"type\":\"seed\",\"gx\":%d,\"gy\":%d,\"color\":[%.3f,%.3f,%.3f,1.0]}}",
                    t, id, gx, gy, c.r, c.g, c.b);
                recordingService.recordRaw(json);
            }
        } catch (Exception ignored) {}
    }

    private void spawnMonster() {
        Monster m = new Monster();
        int sizeRand = random.nextInt(10);
        m.size = 16 + sizeRand;
        float xf = random.nextFloat(); float yf = random.nextFloat();
        m.x = xf * (worldW - m.size);
        m.y = yf * (worldH - m.size);
        float speedRand = random.nextFloat(); float angleRand = random.nextFloat();
        float speed = 60 + speedRand * 80;
        float angle = angleRand * (float)Math.PI * 2f;
        m.vx = (float)Math.cos(angle) * speed; m.vy = (float)Math.sin(angle) * speed;
        monsters.add(m);
        System.out.printf("[ReplayDebug] spawnMonster RNG -> sizeRand=%d xf=%.3f yf=%.3f speedRand=%.3f angleRand=%.3f seedCounter=%d simulate=%b\n",
            sizeRand, xf, yf, speedRand, angleRand, seedCounter, this.simulateMode);
    }

    private boolean collideBody(int gx, int gy) { for (Seg s : body) if (s.x == gx && s.y == gy) return true; return false; }

    private boolean headHitsMonster() {
        float hx = headX * cell + cell * 0.5f; float hy = headY * cell + cell * 0.5f; float rHead = cell * 0.5f;
        for (Monster m : monsters) {
            float mx = m.x + m.size * 0.5f; float my = m.y + m.size * 0.5f;
            float dx = hx - mx, dy = hy - my; float dist2 = dx*dx + dy*dy; float rad = rHead + m.size * 0.5f;
            if (dist2 < rad * rad) return true;
        }
        return false;
    }

    private boolean isOpposite(Dir a, Dir b) { return (a == Dir.LEFT && b == Dir.RIGHT) || (a == Dir.RIGHT && b == Dir.LEFT) || (a == Dir.UP && b == Dir.DOWN) || (a == Dir.DOWN && b == Dir.UP); }

    private float lerp(float a, float b, float t) { return a + (b - a) * t; }

    @Override
    public void clear() {
        super.clear();
        if (executor != null) executor.shutdownNow();
        try { if (recordingService != null && recordingService.isRecording()) recordingService.stop(); } catch (Exception ignored) {}
    }

    // Nested helper types
    static enum Dir { LEFT, RIGHT, UP, DOWN }
    static class Color { float r,g,b,a; Color(float r, float g, float b, float a) { this.r=r; this.g=g; this.b=b; this.a=a; } }
    static class Seg { int x,y; Color color; Seg(int x, int y, Color c) { this.x=x; this.y=y; this.color=c; } }
    static class Seed { String id; int gx, gy; Color color; Seed(String id, int gx, int gy, Color color) { this.id = id; this.gx=gx; this.gy=gy; this.color=color; } }
    static class Monster { float x,y; float vx, vy; float size; }
}
