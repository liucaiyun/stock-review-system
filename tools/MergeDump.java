import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.TreeMap;

/** 读回 SplitDump 生成的分片，重建原始文件。 */
public final class MergeDump {

    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args[0]);
        Path out = Path.of(args[1]);
        String prefix = args.length > 2 ? args[2] : "";

        TreeMap<Integer, String> lines = new TreeMap<>();
        try (var s = Files.list(dir)) {
            for (Path f : (Iterable<Path>) s::iterator) {
                String name = f.getFileName().toString();
                if (!name.startsWith(prefix) || !name.endsWith(".txt")) {
                    continue;
                }
                for (String raw : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    int hash1 = raw.indexOf('#');
                    int hash2 = raw.indexOf('#', hash1 + 1);
                    if (hash1 < 0 || hash2 < 0) {
                        continue;
                    }
                    int no = Integer.parseInt(raw.substring(0, hash1));
                    String b64 = raw.substring(hash2 + 1);
                    lines.put(no, new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8));
                }
            }
        }
        List<String> outLines = new ArrayList<>();
        int missing = 0;
        for (int i = 1; i <= lines.lastKey(); i++) {
            if (lines.containsKey(i)) {
                outLines.add(lines.get(i));
            } else {
                outLines.add("<<MISSING " + i + ">>");
                missing++;
            }
        }
        Files.writeString(out, String.join("\n", outLines) + "\n", StandardCharsets.UTF_8);
        System.out.println("rebuilt lines=" + outLines.size() + " missing=" + missing);
    }
}
