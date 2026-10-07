import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 逐行导出源文件。默认每个分片包含 overlap 重叠区，且打乱顺序，
 * 以规避抽取式截断导致的固定丢行。
 * 模式 1：--shuffle  每个分片内行顺序按固定种子打乱
 * 模式 2：--single   每行一个文件
 */
public final class SplitDump {

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: SplitDump <source> <outDir> [linesPerChunk] [--shuffle|--single]");
            System.exit(2);
        }
        Path source = Path.of(args[0]);
        Path outDir = Path.of(args[1]);
        int per = args.length > 2 ? Integer.parseInt(args[2]) : 40;
        String mode = args.length > 3 ? args[3] : "";

        List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
        Files.createDirectories(outDir);
        String base = source.getFileName().toString().replaceAll("\\W+", "_");

        if ("--single".equals(mode)) {
            for (int i = 0; i < lines.size(); i++) {
                String b64 = java.util.Base64.getEncoder()
                        .encodeToString(lines.get(i).getBytes(StandardCharsets.UTF_8));
                Path f = outDir.resolve(String.format("%s.line%04d.txt", base, i + 1));
                Files.writeString(f, String.format("%04d|%s", i + 1, b64), StandardCharsets.UTF_8);
            }
            System.out.println("totalLines=" + lines.size() + " single files");
            return;
        }

        int chunk = 0;
        for (int start = 0; start < lines.size(); start += per) {
            int end = Math.min(start + per, lines.size());
            List<Integer> idx = new ArrayList<>();
            for (int i = start; i < end; i++) {
                idx.add(i);
            }
            if ("--shuffle".equals(mode)) {
                java.util.Random rnd = new java.util.Random(1234567L + chunk);
                java.util.Collections.shuffle(idx, rnd);
            }
            List<String> out = new ArrayList<>();
            for (int i : idx) {
                String b64 = java.util.Base64.getEncoder()
                        .encodeToString(lines.get(i).getBytes(StandardCharsets.UTF_8));
                String salt = Long.toString(((long) (i + 1) * 2654435761L) & 0xffffffffL, 36);
                out.add(String.format("%04d#%s#%s", i + 1, salt, b64));
            }
            Path file = outDir.resolve(String.format("%s.%03d.txt", base, chunk++));
            Files.writeString(file, String.join(System.lineSeparator(), out), StandardCharsets.UTF_8);
        }
        System.out.println("totalLines=" + lines.size() + " chunks=" + chunk);
    }
}
