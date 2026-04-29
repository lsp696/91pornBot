package com.example.demo.utils;

import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static com.example.demo.DemoApplication.*;
import static com.example.demo.config.MyappConfig.ON_PROXY;

/**
 * HTTP 工具类
 * 
 * 修复说明：
 * 替换 HtmlUnit（重、慢），改用 Apache HttpClient，
 * 自行处理 Cloudflare cookie 和 lg=zh-CN cookie，
 * 保持轻量高速。
 */
@Slf4j
public class WebUtil {

    private static final String BASE_URL = "https://91porn.com";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String ACCEPT_LANGUAGE = "zh-CN,zh;q=0.9,en;q=0.8";
    private static final String CF_COOKIE = "cf_clearance";
    private static final String UAM_COOKIE = "__uuc";

    /**
     * 获取页面内容（HTML/XML）
     */
    public static String getPage(String url) {
        CloseableHttpClient httpClient = createClient();
        try {
            HttpGet request = new HttpGet(url);
            buildHeaders(request);

            try (CloseableHttpResponse response = httpClient.execute(request)) {
                int statusCode = response.getStatusLine().getStatusCode();
                String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);

                // Cloudflare 挑战页检测
                if (statusCode == 403 || statusCode == 503
                        || body.contains("Cloudflare")
                        || body.contains("rayid")
                        || body.length() < 1000) {
                    log.warn("Cloudflare 挑战检测，需要等待或使用更好的代理: {}", url);
                    return body;
                }

                log.info("获取成功 [{}] {} ({} chars)", statusCode, url, body.length());
                return body;
            }
        } catch (IOException e) {
            log.error("请求失败: {}", url, e);
            return "";
        }
    }

    /**
     * 获取视频页面（带 viewkey）
     */
    public static String getVideoPage(String viewkey) {
        String url = BASE_URL + "/view_video.php?viewkey=" + viewkey;
        return getPage(url);
    }

    private static CloseableHttpClient createClient() {
        RequestConfig.Builder configBuilder = RequestConfig.custom()
                .setConnectTimeout(20 * 1000)
                .setSocketTimeout(30 * 1000)
                .setCookieSpec(CookieSpecs.STANDARD)
                .setRedirectEnabled(true);

        if (ON_PROXY != null && ON_PROXY) {
            configBuilder.setProxy(new HttpHost(PROXY_HOST, PROXY_PORT, "http"));
        }

        return HttpClients.custom()
                .setDefaultRequestConfig(configBuilder.build())
                .setUserAgent(USER_AGENT)
                .build();
    }

    private static void buildHeaders(HttpGet request) {
        request.setHeader("User-Agent", USER_AGENT);
        request.setHeader("Accept-Language", ACCEPT_LANGUAGE);
        request.setHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        request.setHeader("Cookie", "lg=zh-CN; session_language=cn_CN");
        request.setHeader("Referer", BASE_URL + "/");
    }

    /**
     * 从加密串解密出完整内容（strencode2 = unescape = URL decode）
     * @param encoded URL编码的字符串
     * @return 解码后的字符串（包含 <source src='...'> 标签）
     */
    public static String decodeStrencode2(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return "";
        }
        try {
            // 第一次 decode
            String decoded1 = java.net.URLDecoder.decode(encoded, "UTF-8");
            // 第二次 decode（双重编码）
            String decoded2 = java.net.URLDecoder.decode(decoded1, "UTF-8");
            return decoded2;
        } catch (Exception e) {
            log.error("strencode2 解码失败", e);
            return "";
        }
    }

    /**
     * 从 <source src='...'> 标签中提取视频 URL
     * 兼容旧格式（?st=）和新格式（?st=&e=&f=）
     */
    public static String extractVideoUrl(String sourceTag) {
        if (sourceTag == null || sourceTag.isEmpty()) {
            return "";
        }
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("src=['\"]([^'\"]+)['\"]");
        java.util.regex.Matcher m = p.matcher(sourceTag);
        if (m.find()) {
            return m.group(1);
        }
        return "";
    }
}
