package io.wifi.rhythmfall.client;

import com.mojang.blaze3d.platform.NativeImage;
import io.wifi.rhythmfall.Rhythmfall;
import io.wifi.rhythmfall.beatmap.Beatmap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.stb.STBImage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 封面纹理管理：后台线程解码（PNG/JPG），主线程注册 DynamicTexture，
 * 游玩/结算/选曲界面共用，带 LRU 上限避免纹理无限增长。
 */
public final class CoverTextures {
    public record Tex(ResourceLocation loc, int width, int height) {
    }

    private static final int MAX_ENTRIES = 24;
    private static final AtomicInteger SEQ = new AtomicInteger();

    private static final Map<String, Entry> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
            if (size() > MAX_ENTRIES) {
                eldest.getValue().evict();
                return true;
            }
            return false;
        }
    };

    private static final class Entry {
        volatile ResourceLocation loc;
        volatile int w = 1;
        volatile int h = 1;
        volatile boolean loading = true;

        synchronized void evict() {
            loading = false; // 阻止后续注册
            ResourceLocation l = loc;
            loc = null;
            if (l != null) {
                Minecraft.getInstance().execute(() -> Minecraft.getInstance().getTextureManager().release(l));
            }
        }
    }

    private CoverTextures() {
    }

    /** 取封面纹理；未就绪返回 null（首次调用触发后台解码）。 */
    public static Tex get(Beatmap b) {
        if (b == null || b.id == null || b.coverData == null || b.coverData.length == 0) {
            return null;
        }
        Entry e;
        synchronized (CACHE) {
            e = CACHE.get(b.id);
            if (e == null) {
                e = new Entry();
                CACHE.put(b.id, e);
                startDecode(b.id, e, b.coverData);
            }
        }
        if (e.loading || e.loc == null) {
            return null;
        }
        return new Tex(e.loc, e.w, e.h);
    }

    private static void startDecode(String id, Entry e, byte[] data) {
        Rhythmfall.IO.execute(() -> {
            try {
                NativeImage img = decode(data);
                int w = img.getWidth();
                int h = img.getHeight();
                Minecraft.getInstance().execute(() -> {
                    synchronized (e) {
                        if (!e.loading) {
                            img.close(); // 已被驱逐，放弃注册
                            return;
                        }
                        try {
                            ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(Rhythmfall.MOD_ID,
                                    "cover/" + Long.toHexString(id.hashCode() & 0xFFFFFFFFL)
                                            + "-" + SEQ.incrementAndGet());
                            Minecraft.getInstance().getTextureManager().register(loc, new DynamicTexture(img));
                            e.w = w;
                            e.h = h;
                            e.loc = loc;
                            e.loading = false;
                        } catch (Exception ex) {
                            Rhythmfall.LOGGER.warn("Cover register failed for {}", id, ex);
                            img.close();
                            e.loading = false;
                        }
                    }
                });
            } catch (Exception ex) {
                Rhythmfall.LOGGER.warn("Failed to decode cover for {}", id, ex);
                Minecraft.getInstance().execute(() -> {
                    synchronized (e) {
                        e.loading = false;
                    }
                });
            }
        });
    }

    /** 解码封面：1.21.1 的 NativeImage.read 只支持 PNG；JPEG 等走 LWJGL STB 解码。 */
    private static NativeImage decode(byte[] data) throws IOException {
        try (InputStream in = new ByteArrayInputStream(data)) {
            return NativeImage.read(in); // PNG 快路径
        } catch (IOException ignored) {
            // 不是 PNG，走 STB 通用解码
        }
        ByteBuffer buf = ByteBuffer.allocateDirect(data.length);
        buf.put(data).flip();
        IntBuffer w = org.lwjgl.BufferUtils.createIntBuffer(1);
        IntBuffer h = org.lwjgl.BufferUtils.createIntBuffer(1);
        IntBuffer comp = org.lwjgl.BufferUtils.createIntBuffer(1);
        ByteBuffer pixels = STBImage.stbi_load_from_memory(buf, w, h, comp, 4);
        if (pixels == null) {
            throw new IOException("Image decode failed: " + STBImage.stbi_failure_reason());
        }
        try {
            int width = w.get(0);
            int height = h.get(0);
            NativeImage img = new NativeImage(NativeImage.Format.RGBA, width, height, false);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int i = (y * width + x) * 4;
                    int r = pixels.get(i) & 0xFF;
                    int g = pixels.get(i + 1) & 0xFF;
                    int b = pixels.get(i + 2) & 0xFF;
                    int a = pixels.get(i + 3) & 0xFF;
                    // mojmap 的 setPixelRGBA 内部为 ABGR 布局：打包 (a<<24)|(b<<16)|(g<<8)|r
                    img.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
                }
            }
            return img;
        } finally {
            STBImage.stbi_image_free(pixels);
        }
    }
}
