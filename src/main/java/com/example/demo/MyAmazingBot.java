package com.example.demo;


import com.example.demo.utils.DealStrSub;
import com.example.demo.utils.JsUtil;
import com.example.demo.utils.VideoUtils;
import com.example.demo.utils.WebUtil;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.ActionType;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.example.demo.DemoApplication.*;
import static com.example.demo.config.MyappConfig.*;
import static com.example.demo.utils.WebUtil.*;

/**
 * 91Porn Telegram Bot
 * 
 * 修复说明：
 * 1. strencode2 = unescape = URL decode，无需 JS 引擎
 * 2. 替换 HtmlUnit 为轻量 HTTP 请求
 * 3. 视频 URL 提取正则更新，兼容新格式（?st=&e=&f=）
 * 4. 支持代理配置
 */
@Slf4j
public class MyAmazingBot extends TelegramLongPollingBot {

    // viewkey 正则：从 URL 或页面中提取
    private static final Pattern VIEWKEY_PATTERN = Pattern.compile("viewkey=([a-zA-Z0-9]{10,})");
    // 加密串正则
    private static final Pattern STRENCODE_PATTERN = Pattern.compile("strencode2\\(\"([^\"]+)\"\\)");
    // 视频 URL 正则（兼容新旧格式）
    private static final Pattern VIDEO_URL_PATTERN = Pattern.compile("src=['\"]([^'\"]+\\.mp4[^'\"]*)['\"]");

    public MyAmazingBot(DefaultBotOptions options) {
        super(options);
    }

    @Override
    public String getBotUsername() {
        return BOT_NAME;
    }

    @Override
    public String getBotToken() {
        return BOT_TOKEN;
    }

    @SneakyThrows
    @Override
    public void onUpdateReceived(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }

        String text = update.getMessage().getText();
        String chatId = update.getMessage().getChatId().toString();

        // 发送打字中状态
        SendChatAction typingAction = new SendChatAction();
        typingAction.setChatId(chatId);
        typingAction.setAction(ActionType.TYPING);
        try { execute(typingAction); } catch (TelegramApiException ignored) {}

        if (text.equals("/start")) {
            SendMessage sendMessage = new SendMessage(
                chatId,
                "📺 91视频下载机器人\n\n向我发送91视频链接，我来下载并发送给你。\n视频超过50MB会自动分割发送。"
            );
            execute(sendMessage);
            return;
        }

        // ========== 处理视频链接 ==========
        SendChatAction recordAction = new SendChatAction();
        recordAction.setChatId(chatId);
        recordAction.setAction(ActionType.RECORDVIDEONOTE);
        try { execute(recordAction); } catch (TelegramApiException ignored) {}

        // 提取 viewkey
        Matcher vkMatcher = VIEWKEY_PATTERN.matcher(text);
        if (!vkMatcher.find()) {
            SendMessage errMsg = new SendMessage(chatId, "❌ 无法识别链接中的视频ID，请检查链接是否正确。");
            execute(errMsg);
            return;
        }
        String viewkey = vkMatcher.group(1);
        log.info("收到视频请求 viewkey={}", viewkey);

        // 获取视频页面
        String pageHtml = getPage(BASE_URL + "/view_video.php?viewkey=" + viewkey);
        if (pageHtml == null || pageHtml.isEmpty() || pageHtml.length() < 5000) {
            SendMessage errMsg = new SendMessage(chatId, "❌ 页面获取失败，可能被Cloudflare拦截或需要代理。");
            execute(errMsg);
            return;
        }

        // 提取标题
        String videoName = extractTitle(pageHtml);
        if (videoName == null || videoName.isEmpty()) {
            videoName = "视频_" + viewkey;
        }
        videoName = sanitizeFileName(videoName);

        // 提取视频 URL
        String videoUrl = extractVideoUrlFromPage(pageHtml);
        if (videoUrl == null || videoUrl.isEmpty()) {
            SendMessage errMsg = new SendMessage(chatId,
                "❌ 视频URL提取失败，可能是视频已下架或链接格式已变。");
            execute(errMsg);
            return;
        }

        log.warn("真实地址：{}", videoUrl);

        // 发送下载提示
        SendMessage downloadingMsg = new SendMessage(chatId,
            "📥 正在下载，请稍候...\n" +
            "视频标题：" + videoName + "\n" +
            "真实地址：" + videoUrl.substring(0, Math.min(videoUrl.length(), 80)) + "...");
        execute(downloadingMsg);

        // 创建保存目录
        String savePath = (env.equals("dev") ? FILE_ROOT : FILE_ROOT) + videoName + "/";
        File saveDir = new File(savePath);
        if (!saveDir.exists()) {
            saveDir.mkdirs();
        }

        String mp4File = savePath + videoName + ".mp4";
        String jpgFile = savePath + videoName + ".jpg";

        // 下载 + 转换 + 切割
        try {
            VideoUtils.downloadAndConvert(videoUrl, new File(jpgFile), new File(mp4File));
            // 发送视频
            sendVideo(chatId, videoName, mp4File, jpgFile, savePath);
            log.info("视频发送完成: {}", videoName);
        } catch (Exception e) {
            log.error("下载/转换失败: {}", videoName, e);
            SendMessage failMsg = new SendMessage(chatId, "❌ 下载失败: " + e.getMessage());
            execute(failMsg);
        }
    }

    /**
     * 从页面 HTML 中提取视频 URL
     * 优先用 strencode2 解码，否则直接匹配 <source> 标签
     */
    private String extractVideoUrlFromPage(String html) {
        // 方法1: strencode2 解码
        Matcher m = STRENCODE_PATTERN.matcher(html);
        if (m.find()) {
            String encoded = m.group(1);
            String decoded = JsUtil.strencode(encoded);
            Matcher urlMatcher = VIDEO_URL_PATTERN.matcher(decoded);
            if (urlMatcher.find()) {
                return urlMatcher.group(1);
            }
            // 备选：直接返回 decoded 内容中的完整 URL
            int srcIdx = decoded.indexOf("src='");
            if (srcIdx >= 0) {
                int start = srcIdx + 5;
                int end = decoded.indexOf("'", start);
                if (end > start) {
                    return decoded.substring(start, end);
                }
            }
        }

        // 方法2: 直接从页面 HTML 匹配 <source> 标签
        Matcher sourceMatcher = Pattern.compile("<source\\s+src=['\"]([^'\"]+\\.mp4[^'\"]*)['\"]").matcher(html);
        if (sourceMatcher.find()) {
            return sourceMatcher.group(1);
        }

        return "";
    }

    /**
     * 从页面提取标题
     */
    private String extractTitle(String html) {
        // 优先从 <title>
        Pattern titlePattern = Pattern.compile("<title>\\s*([^<\\n]+?)\\s*- 91porn", Pattern.CASE_INSENSITIVE);
        Matcher m = titlePattern.matcher(html);
        if (m.find()) {
            return m.group(1).trim();
        }
        // 备选：.login_register_header
        Pattern headerPattern = Pattern.compile("login_register_header[^>]*>\\s*([^<]+)", Pattern.CASE_INSENSITIVE);
        m = headerPattern.matcher(html);
        if (m.find()) {
            return m.group(1).trim().replaceAll("\\s+", "");
        }
        return null;
    }

    /**
     * 发送视频给用户
     */
    public void sendVideo(String chatId, String fileName, String filePath, String jpgPath, String fileRoot) {
        File mediaFile = new File(filePath);
        if (!mediaFile.exists() || mediaFile.length() == 0) {
            log.error("视频文件不存在或为空: {}", filePath);
            return;
        }

        long fileSizeMB = mediaFile.length() / (1024 * 1024);
        log.info("视频文件大小: {} MB", fileSizeMB);

        InputFile video = new InputFile(mediaFile);
        InputFile thumb = new InputFile(new File(jpgPath));

        // 检查是否需要分割（> 50MB）
        if (fileSizeMB >= 50) {
            log.info("视频超过50MB，需要分割发送");
            sendVideoSplit(chatId, fileName, fileRoot, jpgPath);
            return;
        }

        SendVideo.SendVideoBuilder builder = SendVideo.builder()
                .chatId(chatId)
                .video(video)
                .supportsStreaming(true)
                .thumb(thumb)
                .caption(fileName);

        try {
            SendVideo videoMsg = builder.build();
            execute(videoMsg);
        } catch (TelegramApiException e) {
            log.error("发送视频失败", e);
        } finally {
            // 清理临时文件
            cleanUp(fileRoot);
        }
    }

    /**
     * 分割发送大视频（每段 50MB）
     */
    private void sendVideoSplit(String chatId, String fileName, String fileRoot, String jpgPath) {
        // 使用 VideoUtils.splitVideo() 分割
        try {
            File mp4File = new File(fileRoot + fileName + ".mp4");
            VideoUtils.splitVideo(mp4File, 50 * 1024 * 1024L);
            InputFile thumb = new InputFile(new File(jpgPath));

            File[] parts = new File(fileRoot).listFiles((dir, name) -> 
                name.startsWith(fileName + "_part") && name.endsWith(".mp4"));
            
            if (parts != null) {
                java.util.Arrays.sort(parts);
                for (File part : parts) {
                    log.info("发送分片: {}", part.getName());
                    SendVideo.builder()
                        .chatId(chatId)
                        .video(new InputFile(part))
                        .supportsStreaming(false)
                        .thumb(thumb)
                        .caption(fileName + " [" + part.getName() + "]")
                        .build();
                    execute(SendVideo.builder()
                        .chatId(chatId)
                        .video(new InputFile(part))
                        .supportsStreaming(false)
                        .thumb(thumb)
                        .caption(fileName + " [" + part.getName() + "]")
                        .build());
                }
            }
        } catch (Exception e) {
            log.error("分割/发送失败", e);
        } finally {
            cleanUp(fileRoot);
        }
    }

    private void cleanUp(String path) {
        try {
            File dir = new File(path);
            if (dir.exists()) {
                FileUtils.deleteDirectory(dir);
                log.info("临时文件已清理: {}", path);
            }
        } catch (IOException e) {
            log.warn("清理临时文件失败", e);
        }
    }

    private String sanitizeFileName(String name) {
        if (name == null) return "video";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim().substring(0, Math.min(name.length(), 100));
    }
}
