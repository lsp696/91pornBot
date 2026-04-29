package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.example.demo.config.MyappConfig;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import lombok.extern.slf4j.Slf4j;
import javax.annotation.PostConstruct;

/**
 * 91Porn Telegram Bot - Spring Boot 启动类
 */
@SpringBootApplication
@EnableConfigurationProperties(MyappConfig.class)
@Slf4j
public class DemoApplication {

    public static String env;
    public static String BOT_NAME;
    public static String PROXY_HOST = "192.168.10.6";
    public static int PROXY_PORT = 7890;
    public static final String VIDEO_MP4 = ".mp4";
    public static final String VIDEO_JPEG = ".jpg";

    @PostConstruct
    public void init() {
        env = System.getenv().getOrDefault("SPRING_PROFILES_ACTIVE", "prod");
        log.info("启动环境: {}", env);
    }

    public static void main(String[] args) throws TelegramApiException {
        SpringApplication.run(DemoApplication.class, args);
        
        // 注册 Bot
        MyAmazingBot bot = new MyAmazingBot(new org.telegram.telegrambots.bots.DefaultBotOptions());
        TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
        botsApi.registerBot(bot);
        log.info("91Porn Bot 注册成功!");
    }
}
