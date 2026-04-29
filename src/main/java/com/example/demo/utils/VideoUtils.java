package com.example.demo.utils;

import lombok.extern.slf4j.Slf4j;
import net.bramp.ffmpeg.Ffmpeg;
import net.bramp.ffmpeg.Ffprobe;
import net.bramp.ffmpeg.builder.FFmpegBuilder;
import net.bramp.ffmpeg.job.FFmpegJob;
import net.bramp.ffmpeg.job.FFmpegSplitJob;
import net.bramp.ffmpeg.probe.FFmpegProbeResult;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

import static com.example.demo.DemoApplication.env;
import static com.example.demo.config.MyappConfig.*;

/**
 * 视频处理工具类
 * 
 * 修复说明：
 * 1. 移除 HtmlUnit 依赖，直接用 URL 下载
 * 2. 优化 FFmpeg 使用方式（支持新版本 API）
 * 3. 简化下载流程
 */
@Slf4j
public class VideoUtils {

    private static final String FFPROBE_PATH = FFMPEG_ROOT + "ffprobe";
    private static final String FFMPEG_PATH = FFMPEG_ROOT + "ffmpeg";

    /**
     * 下载并处理视频
     * 1. 下载 MP4（curl）
     * 2. 提取封面
     * 3. 切割大视频
     */
    public static void downloadAndConvert(String videoUrl, File jpgPath, File mp4Path) throws IOException, InterruptedException {
        // 1. 下载视频（用 curl，更稳定）
        log.info("开始下载视频: {}", videoUrl);
        downloadWithCurl(videoUrl, mp4Path);
        log.info("下载完成: {} ({} bytes)", mp4Path.getAbsolutePath(), mp4Path.length());

        // 2. 提取封面
        extractThumbnail(mp4Path, jpgPath);

        // 3. 检查大小
        double sizeMB = mp4Path.length() / (1024.0 * 1024.0);
        log.info("视频大小: {} MB", String.format("%.2f", sizeMB));

        if (sizeMB >= 50) {
            log.info("视频超过50MB，需要分割");
            splitVideo(mp4Path, 50 * 1024 * 1024L);
        }
    }

    /**
     * 用 curl 下载视频（支持代理、大文件）
     */
    private static void downloadWithCurl(String url, File output) throws IOException, InterruptedException {
        String proxyArg = "";
        if (ON_PROXY != null && ON_PROXY) {
            proxyArg = "-x " + PROXY_HOST + ":" + PROXY_PORT;
        }

        String cmd = String.format(
            "curl -L --max-time 600 -o %s -A %s -H %s -H %s %s %s",
            output.getAbsolutePath().replace(" ", "\\ "),
            "\"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36\"",
            "\"Referer: https://91porn.com/\"",
            "\"Accept: */*\"",
            proxyArg,
            url
        );

        log.info("执行: {}", cmd);
        Process process = Runtime.getRuntime().exec(new String[]{"bash", "-c", cmd});
        
        // 消费输出
        StreamConsumer outConsumer = new StreamConsumer(process.getInputStream(), "curl-out");
        StreamConsumer errConsumer = new StreamConsumer(process.getErrorStream(), "curl-err");
        outConsumer.start();
        errConsumer.start();

        int exitCode = process.waitFor();
        outConsumer.waitFor();
        errConsumer.waitFor();

        if (exitCode != 0) {
            throw new IOException("curl 下载失败，退出码: " + exitCode);
        }
    }

    /**
     * 提取视频封面（截取第1秒帧）
     */
    public static void extractThumbnail(File videoFile, File jpgFile) throws IOException, InterruptedException {
        if (!jpgFile.getParentFile().exists()) {
            jpgFile.getParentFile().mkdirs();
        }

        try {
            Ffprobe ffprobe = new Ffprobe(FFPROBE_PATH);
            FFmpeg ffmpeg = new Ffmpeg(FFMPEG_PATH);

            FFmpegProbeResult probe = ffprobe.probe(videoFile.getAbsolutePath());
            double duration = probe.getFormat().duration;

            FFmpegBuilder builder = new FFmpegBuilder()
                    .overrideOutputFiles(true)
                    .setInput(videoFile.getAbsolutePath())
                    .addOutput(jpgFile.getAbsolutePath())
                    .setFormat("image2")
                    .addExtraArgs("-vframes", "1", "-ss", duration > 10 ? "10" : "1")
                    .done();

            FFmpegJob job = ffmpeg.exec(builder);
            if (job.getState() == FFmpegJob.State.FAILED) {
                log.warn("封面提取失败（非致命）");
            } else {
                log.info("封面提取成功: {}", jpgFile.getAbsolutePath());
            }
        } catch (Exception e) {
            log.warn("封面提取失败（非致命）: {}", e.getMessage());
        }
    }

    /**
     * 切割大视频为多个小文件（每段不超过 maxSize 字节）
     */
    public static void splitVideo(File videoFile, long maxSize) throws IOException, InterruptedException {
        if (!videoFile.exists() || !videoFile.canRead()) {
            throw new IOException("视频文件无法读取: " + videoFile);
        }

        File dir = videoFile.getParentFile();
        String baseName = videoFile.getName().replace(".mp4", "");

        // MP4Box 切割
        String mp4boxCmd = MP4BOX_ROOT + " -splits " + maxSize + " " + videoFile.getAbsolutePath();
        log.info("MP4Box 切割: {}", mp4boxCmd);
        
        Process process = Runtime.getRuntime().exec(mp4boxCmd);
        StreamConsumer out = new StreamConsumer(process.getInputStream(), "MP4Box-out");
        StreamConsumer err = new StreamConsumer(process.getErrorStream(), "MP4Box-err");
        out.start();
        err.start();

        int exit = process.waitFor();
        out.waitFor();
        err.waitFor();

        if (exit == 0) {
            log.info("MP4Box 切割完成");
            // 删除原文件
            videoFile.delete();
        } else {
            log.warn("MP4Box 切割失败，退出码: {}", exit);
            // 备选：用 FFmpeg 按时间切割
            splitWithFFmpeg(videoFile, dir, baseName, maxSize);
        }
    }

    /**
     * FFmpeg 按时间切割（备选方案）
     */
    private static void splitWithFFmpeg(File videoFile, File dir, String baseName, long maxSize) throws IOException, InterruptedException {
        try {
            Ffprobe ffprobe = new Ffprobe(FFPROBE_PATH);
            FFmpeg ffmpeg = new Ffmpeg(FFMPEG_PATH);
            
            FFmpegProbeResult probe = ffprobe.probe(videoFile.getAbsolutePath());
            double durationSec = probe.getFormat().duration;
            long fileSizeBytes = videoFile.length();
            double sizePerSec = fileSizeBytes / durationSec;
            long segDurationSec = (long) (maxSize / sizePerSec);
            if (segDurationSec < 10) segDurationSec = 300; // 最小5分钟一段

            int partNum = 0;
            double startTime = 0;
            while (startTime < durationSec) {
                double endTime = Math.min(startTime + segDurationSec, durationSec);
                String outPath = new File(dir, baseName + "_part" + String.format("%02d", partNum) + ".mp4").getAbsolutePath();

                FFmpegBuilder builder = new FFmpegBuilder()
                        .overrideOutputFiles(true)
                        .setInput(videoFile.getAbsolutePath())
                        .addExtraArgs("-ss", String.valueOf(startTime), "-t", String.valueOf(endTime - startTime))
                        .addOutput(outPath)
                        .setFormat("mp4")
                        .done();

                FFmpegJob job = ffmpeg.exec(builder);
                if (job.getState() != FFmpegJob.State.FAILED) {
                    log.info("切割分片 {}/{}: {}s-{}s", partNum, outPath, startTime, endTime);
                }
                startTime = endTime;
                partNum++;
            }
            log.info("FFmpeg 切割完成，共 {} 段", partNum);
            videoFile.delete();
        } catch (Exception e) {
            log.error("FFmpeg 切割失败", e);
        }
    }

    /**
     * 获取视频信息
     */
    public static it.sauronsoftware.jave.MultimediaInfo getVideoInfo(File source) throws it.sauronsoftware.jave.EncoderException {
        it.sauronsoftware.jave.Encoder encoder = new it.sauronsoftware.jave.Encoder();
        return encoder.getInfo(source);
    }

    /**
     * 消费子进程输出（避免管道阻塞）
     */
    static class StreamConsumer extends Thread {
        private final java.io.InputStream is;
        private final String name;

        StreamConsumer(java.io.InputStream is, String name) {
            this.is = is;
            this.name = name;
        }

        @Override
        public void run() {
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(is))) {
                String line;
                while ((line = br.readLine()) != null) {
                    log.debug("[{}] {}", name, line);
                }
            } catch (IOException ignored) {}
        }
    }
}
