
// Launcher.java
import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

public class Launcher {
    public static void main(String[] args) throws Exception {
        String nativesDir = Files.createTempDirectory("lwjgl-natives").toString();

        // Extract DLLs from inside the JAR's natives/ folder
        try (ZipInputStream zip = new ZipInputStream(Launcher.class.getResourceAsStream("/Game.jar"))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().startsWith("natives/") && entry.getName().endsWith(".dll")) {
                    File outFile = new File(nativesDir, new File(entry.getName()).getName());
                    try (OutputStream out = new FileOutputStream(outFile)) {
                        zip.transferTo(out);
                    }
                }
            }
        }

        // Launch the game with native path set
        ProcessBuilder pb = new ProcessBuilder(
                "java",
                "-Djava.library.path=" + nativesDir,
                "-cp", "Game.jar",
                "Main");
        pb.inheritIO();
        pb.start().waitFor();
    }
}
