package com.example.demo.utils;

import lombok.extern.slf4j.Slf4j;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * 子进程输出消费线程
 * 防止管道缓冲区满导致进程阻塞
 */
@Slf4j
public class RunThread extends Thread {
    private final InputStream is;
    private final String name;

    public RunThread(InputStream is, String name) {
        this.is = is;
        this.name = name;
    }

    @Override
    public void run() {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = br.readLine()) != null) {
                log.debug("[{}] {}", name, line);
            }
        } catch (Exception e) {
            // ignore
        }
    }
}
