package com.gameengine.example;

import com.gameengine.core.GameEngine;
import com.gameengine.input.InputManager;
import com.gameengine.math.Vector2;
import com.gameengine.scene.Scene;
import com.gameengine.graphics.Renderer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MenuScene extends Scene {
    public enum MenuOption { START_GAME, REPLAY, EXIT }

    private Renderer renderer;
    private InputManager inputManager;
    private GameEngine engine;
    private int selectedIndex;
    private MenuOption[] options;
    private MenuOption selectedOption;

    public MenuScene(GameEngine engine, String name) {
        super(name);
        this.engine = engine;
        this.renderer = engine.getRenderer();
        this.inputManager = InputManager.getInstance();
        this.options = new MenuOption[]{MenuOption.START_GAME, MenuOption.REPLAY, MenuOption.EXIT};
        this.selectedIndex = 0;
    }

    @Override
    public void initialize() {
        super.initialize();
        selectedIndex = 0;
    }

    @Override
    public void update(float deltaTime) {
        super.update(deltaTime);
        if (inputManager.isKeyJustPressed(38)) selectedIndex = (selectedIndex - 1 + options.length) % options.length;
        else if (inputManager.isKeyJustPressed(40)) selectedIndex = (selectedIndex + 1) % options.length;
        else if (inputManager.isKeyJustPressed(10) || inputManager.isKeyJustPressed(32)) {
            selectedOption = options[selectedIndex];
            if (selectedOption == MenuOption.START_GAME) switchToGameScene();
            else if (selectedOption == MenuOption.REPLAY) switchToReplay();
            else if (selectedOption == MenuOption.EXIT) { engine.stop(); System.exit(0); }
        }

        Vector2 mp = inputManager.getMousePosition();
        if (inputManager.isMouseButtonJustPressed(0)) {
            float centerY = renderer.getHeight() / 2.0f;
            // three buttons vertical spacing
            float itemH = 80f;
            float buttonY1 = centerY - itemH; // START
            float buttonY2 = centerY;         // REPLAY
            float buttonY3 = centerY + itemH; // EXIT
            if (mp.y >= buttonY1 - 30 && mp.y <= buttonY1 + 30) { selectedIndex = 0; switchToGameScene(); }
            else if (mp.y >= buttonY2 - 30 && mp.y <= buttonY2 + 30) { selectedIndex = 1; switchToReplay(); }
            else if (mp.y >= buttonY3 - 30 && mp.y <= buttonY3 + 30) { engine.stop(); System.exit(0); }
        }
    }

    private void switchToGameScene() {
        try { new File("recordings").mkdirs(); } catch (Exception ignored) {}
        Scene game = new HuluSnakeScene(engine, true); // start game and auto-start recording
        engine.setScene(game);
    }

    private void switchToReplay() {
        Scene r = new ReplayScene(engine, null);
        engine.setScene(r);
    }

    @Override
    public void render() {
        if (renderer == null) return;
        int w = renderer.getWidth(); int h = renderer.getHeight();
        renderer.drawRect(0,0,w,h,0.25f,0.25f,0.35f,1.0f);
        renderMainMenu();
    }

    private void renderMainMenu() {
        int width = renderer.getWidth(); int height = renderer.getHeight();
        float centerX = width / 2.0f; float centerY = height / 2.0f;
        String title = "HULU SNAKE"; float titleWidth = title.length() * 18.0f; float titleX = centerX - titleWidth/2.0f; float titleY = 120.0f;
        renderer.drawRect((int)(centerX - titleWidth/2.0f - 20), (int)(titleY - 40), (int)(titleWidth + 40), 80, 0.4f,0.4f,0.5f,1.0f);
        renderer.drawText((int)titleX, (int)titleY, title, 1f,1f,1f,1f);

        String startText = "START GAME"; String replayText = "REPLAY"; String exitText = "EXIT";
        float sW = startText.length() * 18.0f; float sX = centerX - sW/2.0f; float sY = centerY - 80;
        float rW = replayText.length() * 18.0f; float rX = centerX - rW/2.0f; float rY = centerY;
        float eW = exitText.length() * 18.0f; float eX = centerX - eW/2.0f; float eY = centerY + 80;

        if (selectedIndex == 0) renderer.drawRect((int)(sX -20), (int)(sY -20), (int)(sW + 40), 50, 0.6f,0.5f,0.2f,0.9f);
        else renderer.drawRect((int)(sX -20), (int)(sY -20), (int)(sW + 40), 50, 0.2f,0.2f,0.3f,0.5f);
        renderer.drawText((int)sX, (int)sY, startText, 0.95f,0.95f,0.95f,1.0f);

        if (selectedIndex == 1) renderer.drawRect((int)(rX -20), (int)(rY -20), (int)(rW + 40), 50, 0.6f,0.5f,0.2f,0.9f);
        else renderer.drawRect((int)(rX -20), (int)(rY -20), (int)(rW + 40), 50, 0.2f,0.2f,0.3f,0.5f);
        renderer.drawText((int)rX, (int)rY, replayText, 0.95f,0.95f,0.95f,1.0f);

        if (selectedIndex == 2) renderer.drawRect((int)(eX -20), (int)(eY -20), (int)(eW + 40), 50, 0.6f,0.5f,0.2f,0.9f);
        else renderer.drawRect((int)(eX -20), (int)(eY -20), (int)(eW + 40), 50, 0.2f,0.2f,0.3f,0.5f);
        renderer.drawText((int)eX, (int)eY, exitText, 0.95f,0.95f,0.95f,1.0f);
    }
}
