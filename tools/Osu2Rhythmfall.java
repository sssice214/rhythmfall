import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * osu!mania (4K) 谱面转 rhythmfall JSON 便捷工具。
 * 双击运行 = 图形界面（支持拖拽文件）；命令行: java -jar osu2rhythmfall.jar <文件...>
 */
public class Osu2Rhythmfall {

    interface Log { void line(String s); }

    public static void main(String[] args) {
        if (args.length > 0) {
            try {
                System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true,
                        StandardCharsets.UTF_8));
            } catch (Exception ignored) {
            }
            Log log = System.out::println;
            for (String a : args) {
                process(Paths.get(a).toAbsolutePath(), log);
            }
            return;
        }
        SwingUtilities.invokeLater(Osu2Rhythmfall::openGui);
    }

    // ==================== 转换核心 ====================

    static void process(Path in, Log log) {
        String lower = in.getFileName().toString().toLowerCase();
        if (!lower.endsWith(".osz") && !lower.endsWith(".osu")) {
            log.line("跳过（仅支持 .osz / .osu）: " + in.getFileName());
            return;
        }
        try {
            String base = in.getFileName().toString().replaceAll("(?i)\\.(osz|osu)$", "");
            Path outDir = in.getParent().resolve(base + "_rhythmfall");
            Files.createDirectories(outDir);

            if (lower.endsWith(".osz")) {
                extractOsz(in, outDir);
                List<Path> osus = new ArrayList<>();
                try (var walk = Files.walk(outDir)) {
                    walk.filter(p -> p.toString().toLowerCase().endsWith(".osu"))
                            .sorted()
                            .forEach(osus::add);
                }
                for (Path osu : osus) {
                    convertInPlace(osu, log);
                }
            } else {
                convertStandalone(in, outDir, log);
            }
            log.line("输出目录: " + outDir);
        } catch (Exception e) {
            log.line("处理失败 " + in.getFileName() + ": " + e.getMessage());
        }
    }

    static void extractOsz(Path osz, Path out) throws IOException {
        try (ZipFile zf = new ZipFile(osz.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                Path dest = out.resolve(e.getName()).normalize();
                if (!dest.startsWith(out)) {
                    continue;
                }
                Files.createDirectories(dest.getParent());
                try (InputStream is = zf.getInputStream(e)) {
                    Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** .osz 场景：音频/封面已解包，谱面结构保留，json 写在每个 .osu 旁。 */
    static void convertInPlace(Path osu, Log log) throws IOException {
        Data d = parse(osu);
        String outName = osu.getFileName().toString().replaceAll("(?i)\\.osu$", ".json");
        writeJson(d, osu.getParent().resolve(outName), sanitize(d.audio),
                d.cover.isEmpty() ? "" : sanitize(d.cover));
        log.line("已转换: " + outName + summary(d));
    }

    /** 单个 .osu：把音频/封面一并拷进输出目录，产出可整包拷到服务端的文件夹。 */
    static void convertStandalone(Path osu, Path outDir, Log log) throws IOException {
        Data d = parse(osu);
        Path srcDir = osu.getParent();
        String audioField = copySibling(srcDir, outDir, d.audio);
        String coverField = d.cover.isEmpty() ? "" : copySibling(srcDir, outDir, d.cover);

        String outName = osu.getFileName().toString().replaceAll("(?i)\\.osu$", ".json");
        writeJson(d, outDir.resolve(outName), audioField, coverField);
        log.line("已转换: " + outName + summary(d));
    }

    static String copySibling(Path srcDir, Path outDir, String rel) throws IOException {
        String s = sanitize(rel);
        if (s.isEmpty()) {
            return "";
        }
        Path src = srcDir.resolve(s).normalize();
        if (!Files.isRegularFile(src)) {
            return s;
        }
        Path dst = outDir.resolve(Path.of(s).getFileName().toString());
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        return dst.getFileName().toString();
    }

    static String sanitize(String s) {
        if (s == null) {
            return "";
        }
        s = s.replace('\\', '/');
        while (s.startsWith("../")) {
            s = s.substring(3);
        }
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        return s;
    }

    // ==================== 解析与写 JSON ====================

    static class Data {
        String title = "Untitled";
        String artist = "";
        String version = "Normal";
        String audio = "audio.mp3";
        String cover = "";
        List<String[]> notes = new ArrayList<>();
    }

    static Data parse(Path osu) throws IOException {
        List<String> lines = Files.readAllLines(osu, StandardCharsets.UTF_8);
        String section = "";
        Data d = new Data();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line;
                continue;
            }
            if (line.isEmpty() || line.startsWith("//")) {
                continue;
            }
            switch (section) {
                case "[General]" -> {
                    if (line.startsWith("AudioFilename:")) {
                        d.audio = line.substring("AudioFilename:".length()).trim();
                    }
                }
                case "[Metadata]" -> {
                    if (line.startsWith("Title:")) {
                        d.title = line.substring("Title:".length()).trim();
                    } else if (line.startsWith("Artist:")) {
                        d.artist = line.substring("Artist:".length()).trim();
                    } else if (line.startsWith("Version:")) {
                        d.version = line.substring("Version:".length()).trim();
                    }
                }
                case "[Events]" -> {
                    if (d.cover.isEmpty() && line.startsWith("0,0,\"")) {
                        int a = line.indexOf('"') + 1;
                        int b = line.indexOf('"', a);
                        if (a > 0 && b > a) {
                            d.cover = line.substring(a, b);
                        }
                    }
                }
                case "[HitObjects]" -> {
                    String[] p = line.split(",");
                    if (p.length >= 5) {
                        int x = parseInt(p[0]);
                        long time = parseLong(p[2]);
                        int type = parseInt(p[3]);
                        int lane = Math.max(0, Math.min(3, x / 128));
                        boolean hold = (type & 128) != 0;
                        long endTime = 0;
                        if (hold && p.length >= 6) {
                            int colon = p[5].indexOf(':');
                            if (colon > 0) {
                                endTime = parseLong(p[5].substring(0, colon));
                            }
                        }
                        d.notes.add(new String[]{String.valueOf(lane), String.valueOf(time),
                                hold ? "hold" : "tap", String.valueOf(endTime)});
                    }
                }
                default -> {
                }
            }
        }
        return d;
    }

    static void writeJson(Data d, Path out, String audioField, String coverField) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"name\": ").append(q(d.title)).append(",\n");
        sb.append("  \"artist\": ").append(q(d.artist)).append(",\n");
        sb.append("  \"difficulty\": ").append(q(d.version)).append(",\n");
        sb.append("  \"audio\": ").append(q(audioField.isEmpty() ? "audio.mp3" : audioField)).append(",\n");
        if (!coverField.isEmpty()) {
            sb.append("  \"cover\": ").append(q(coverField)).append(",\n");
        }
        sb.append("  \"offset\": 0,\n");
        sb.append("  \"scrollSpeed\": 300,\n");
        sb.append("  \"notes\": [\n");
        for (int i = 0; i < d.notes.size(); i++) {
            String[] n = d.notes.get(i);
            sb.append("    { \"lane\": ").append(n[0])
                    .append(", \"time\": ").append(n[1])
                    .append(", \"type\": \"").append(n[2]).append('"');
            if ("hold".equals(n[2])) {
                sb.append(", \"endTime\": ").append(n[3]);
            }
            sb.append(" }");
            if (i < d.notes.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        sb.append("  ]\n");
        sb.append("}\n");
        Files.write(out, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    static String summary(Data d) {
        long tap = d.notes.stream().filter(n -> "tap".equals(n[2])).count();
        long hold = d.notes.stream().filter(n -> "hold".equals(n[2])).count();
        return "  (" + d.artist + " - " + d.title + " [" + d.version + "]  tap=" + tap
                + " hold=" + hold + " 共=" + (tap + hold) + ")";
    }

    static String q(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    static int parseInt(String s) {
        try {
            return (int) Double.parseDouble(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    static long parseLong(String s) {
        try {
            return (long) Double.parseDouble(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    // ==================== 图形界面 ====================

    static void openGui() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        JFrame f = new JFrame("osu! → rhythmfall 谱面转换器");
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        f.setSize(580, 460);
        f.setLocationRelativeTo(null);

        JTextArea log = new JTextArea();
        log.setEditable(false);
        log.setLineWrap(true);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane sp = new JScrollPane(log);

        JLabel hint = new JLabel("把 .osz / .osu 文件拖进窗口，或点「选择文件」", SwingConstants.CENTER);
        hint.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JButton choose = new JButton("选择文件…");
        JButton clear = new JButton("清空日志");
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        btns.add(clear);
        btns.add(choose);
        JPanel top = new JPanel(new BorderLayout());
        top.add(hint, BorderLayout.CENTER);
        top.add(btns, BorderLayout.EAST);

        f.add(top, BorderLayout.NORTH);
        f.add(sp, BorderLayout.CENTER);

        final Log logger = s -> SwingUtilities.invokeLater(() -> log.append(s + "\n"));
        final Consumer<List<File>> handle = files -> new Thread(() -> {
            for (File file : files) {
                SwingUtilities.invokeLater(() -> log.append("=== " + file.getName() + " ===\n"));
                process(file.toPath().toAbsolutePath(), logger);
                SwingUtilities.invokeLater(() -> log.append("\n"));
            }
        }).start();

        clear.addActionListener(e -> log.setText(""));
        choose.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setMultiSelectionEnabled(true);
            fc.setFileFilter(new FileNameExtensionFilter("osu 谱面 (.osz / .osu)", "osz", "osu"));
            if (fc.showOpenDialog(f) == JFileChooser.APPROVE_OPTION) {
                handle.accept(List.of(fc.getSelectedFiles()));
            }
        });

        new DropTarget(f, new DropTargetAdapter() {
            @Override
            public void drop(DropTargetDropEvent evt) {
                try {
                    evt.acceptDrop(DnDConstants.ACTION_COPY);
                    Transferable t = evt.getTransferable();
                    Object data = t.getTransferData(DataFlavor.javaFileListFlavor);
                    @SuppressWarnings("unchecked")
                    List<File> files = (List<File>) data;
                    handle.accept(files);
                } catch (Exception ex) {
                    log.append("拖拽失败: " + ex.getMessage() + "\n");
                }
            }
        });

        f.setVisible(true);
        log.append("把 .osz / .osu 拖进来即可转换。输出会生成在同目录的「<文件名>_rhythmfall」文件夹里。\n\n");
    }
}
