package com.chatapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 聊天应用主启动类
 */
@SpringBootApplication
@EnableAsync
public class ChatAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatAppApplication.class, args);
        System.out.println("聊天应用后端服务启动成功！");
        System.out.println("API 地址: http://localhost:8080/api");
        System.out.println("WebSocket 地址: ws://localhost:8080/api/ws");
    }
} 