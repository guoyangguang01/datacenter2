package com.sdncustom.server.config;

import com.sdncustom.server.service.ChannelService;
import com.sdncustom.server.service.HistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AppStartupRunner implements CommandLineRunner {

    private final ChannelService channelService;
    private final HistoryService historyService;

    @Override
    public void run(String... args) {
        log.info("Initializing SDNCustom application...");

        // 初始化 TDengine
        historyService.init();

        // 异步自动连接 Channel（守护线程，不阻塞 JVM 关闭）
        Thread autoConnectThread = new Thread(() -> {
            try {
                Thread.sleep(2000); // 等待应用完全启动
                channelService.autoConnectAll();
            } catch (Exception e) {
                log.error("Auto-connect failed", e);
            }
        }, "auto-connect");
        autoConnectThread.setDaemon(true);
        autoConnectThread.start();

        log.info("SDNCustom application initialized");
    }
}
