import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** 归一化换行后比较两个文件。 */
public final class DiffCheck {

    public static void main(String[] args) throws Exception {
        String a = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8).replace("\r\n", "\n");
        String b = Files.readString(Path.of(args[1]), StandardCharsets.UTF_8).replace("\r\n", "\n");
        System.out.println("lenA=" + a.length() + " lenB=" + b.length());
        if (a.equals(b)) {
            System.out.println("IDENTICAL(after LF normalize)");
            return;
        }
        String[] la = a.split("\n", -1);
        String[] lb = b.split("\n", -1);
        for (int i = 0; i < Math.max(la.length, lb.length); i++) {
            String x = i < la.length ? la[i] : "<none>";
            String y = i < lb.length ? lb[i] : "<none>";
            if (!x.equals(y)) {
                System.out.println("first diff at line " + (i + 1));
                System.out.println("A: [" + x + "]");
                System.out.println("B: [" + y + "]");
                return;
            }
        }
        System.out.println("prefix equal, length differs");
    }
}
