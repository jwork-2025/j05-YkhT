package com.gameengine.core;

import com.gameengine.components.TransformComponent;
import com.gameengine.components.PhysicsComponent;
import com.gameengine.core.GameObject;
import com.gameengine.input.InputManager;
import com.gameengine.math.Vector2;
import com.gameengine.scene.Scene;

import java.util.List;

/**
 * 游戏逻辑类，处理具体的游戏规则
 */
public class GameLogic {
    private Scene scene;
    private InputManager inputManager;
    
    public GameLogic(Scene scene) {
        this.scene = scene;
        this.inputManager = InputManager.getInstance();
    }
    
    /**
     * 处理玩家输入
     */
    public void handlePlayerInput() {
        List<GameObject> players = scene.findGameObjectsByComponent(TransformComponent.class);
        if (players.isEmpty()) return;
        
        GameObject player = players.get(0);
        TransformComponent transform = player.getComponent(TransformComponent.class);
        PhysicsComponent physics = player.getComponent(PhysicsComponent.class);
        
        if (transform == null || physics == null) return;
        
        Vector2 movement = new Vector2();
        
        if (inputManager.isKeyPressed(87) || inputManager.isKeyPressed(38)) { // W或上箭头
            movement.y -= 1;
        }
        if (inputManager.isKeyPressed(83) || inputManager.isKeyPressed(40)) { // S或下箭头
            movement.y += 1;
        }
        if (inputManager.isKeyPressed(65) || inputManager.isKeyPressed(37)) { // A或左箭头
            movement.x -= 1;
        }
        if (inputManager.isKeyPressed(68) || inputManager.isKeyPressed(39)) { // D或右箭头
            movement.x += 1;
        }
        
        if (movement.magnitude() > 0) {
            movement = movement.normalize().multiply(200);
            physics.setVelocity(movement);
        }
        
        // 边界检查
        Vector2 pos = transform.getPosition();
        if (pos.x < 0) pos.x = 0;
        if (pos.y < 0) pos.y = 0;
        if (pos.x > 800 - 20) pos.x = 800 - 20;
        if (pos.y > 600 - 20) pos.y = 600 - 20;
        transform.setPosition(pos);
    }
    
    /**
     * 更新物理系统
     */
    public void updatePhysics() {
        List<PhysicsComponent> physicsComponents = scene.getComponents(PhysicsComponent.class);
        for (PhysicsComponent physics : physicsComponents) {
            // 边界反弹
            TransformComponent transform = physics.getOwner().getComponent(TransformComponent.class);
            if (transform != null) {
                Vector2 pos = transform.getPosition();
                Vector2 velocity = physics.getVelocity();
                
                if (pos.x <= 0 || pos.x >= 800 - 15) {
                    velocity.x = -velocity.x;
                    physics.setVelocity(velocity);
                }
                if (pos.y <= 0 || pos.y >= 600 - 15) {
                    velocity.y = -velocity.y;
                    physics.setVelocity(velocity);
                }
                
                // 确保在边界内
                if (pos.x < 0) pos.x = 0;
                if (pos.y < 0) pos.y = 0;
                if (pos.x > 800 - 15) pos.x = 800 - 15;
                if (pos.y > 600 - 15) pos.y = 600 - 15;
                transform.setPosition(pos);
            }
        }
    }
    
    /**
     * 检查碰撞
     */
    public void checkCollisions() {
        // 直接查找玩家对象
        List<GameObject> players = scene.findGameObjectsByComponent(TransformComponent.class);
        if (players.isEmpty()) return;
        
        GameObject player = players.get(0);
        TransformComponent playerTransform = player.getComponent(TransformComponent.class);
        if (playerTransform == null) return;
        
        // 直接查找所有游戏对象，然后过滤出敌人
        for (GameObject obj : scene.getGameObjects()) {
            if (obj.getName().equals("Enemy")) {
                TransformComponent enemyTransform = obj.getComponent(TransformComponent.class);
                if (enemyTransform != null) {
                    float distance = playerTransform.getPosition().distance(enemyTransform.getPosition());
                    if (distance < 25) {
                        // 碰撞！重置玩家位置
                        playerTransform.setPosition(new Vector2(400, 300));
                        break;
                    }
                }
            }
        }
    }
}
