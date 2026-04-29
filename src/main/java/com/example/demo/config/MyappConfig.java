package com.example.demo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 配置类
 * 
 * 修复说明：
 * 移除了 HtmlUnit 相关配置，新增代理配置。
 */
@Data
@ConfigurationProperties(prefix = "myappconfig")
@Component
public class MyappConfig {

    /** 是否启用代理 */
    public static Boolean ON_PROXY;

    /** FFmpeg 路径 */
    public static String FFMPEG_ROOT;

    /** MP4Box 路径 */
    public static String MP4BOX_ROOT;

    /** 临时文件存放路径 */
    public static String FILE_ROOT;

    /** Telegram 接收人 ID */
    public static String CHAT_ID;

    /** Telegram Bot Token */
    public static String BOT_TOKEN;

    @Data
    public static class Config {
        private Boolean proxyOn;
        private String ffmpegRoot;
        private String mp4boxRoot;
        private String fileroot;
        private String chatId;
        private String botToken;
    }
}
