package io.wifi.rhythmfall;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class Rhythmfall {
    public static final String MOD_ID = "rhythmfall";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /**
     * 单线程 IO 执行器：谱面扫描、文件读取、分块发包、排行榜读写、
     * 封面解码等全部在这里排队执行，保证不阻塞服务端/客户端主线程。
     */
    public static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "rhythmfall-io");
        t.setDaemon(true);
        return t;
    });

    private Rhythmfall() {
    }
}
