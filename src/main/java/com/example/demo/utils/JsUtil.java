package com.example.demo.utils;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;

/**
 * 91Porn 解密工具
 * 
 * 修复说明：
 * strencode2() 函数实际上就是 unescape / URL decode，
 * 不再需要 JS 引擎，直接用 Java 实现即可。
 */
public class JsUtil {

    /**
     * strencode2 解密
     * 原始实现：unescape(str)
     * Java 等价：URLDecoder.decode 两次（内容是双重URL编码）
     */
    public static String strencode(String str1) {
        if (str1 == null || str1.isEmpty()) {
            return "";
        }
        try {
            // 第一次 decode（去掉 %3x 外层）
            String decoded1 = URLDecoder.decode(str1, "UTF-8");
            // 第二次 decode（处理内层编码）
            String decoded2 = URLDecoder.decode(decoded1, "UTF-8");
            return decoded2;
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("URL decode failed", e);
        }
    }
}
