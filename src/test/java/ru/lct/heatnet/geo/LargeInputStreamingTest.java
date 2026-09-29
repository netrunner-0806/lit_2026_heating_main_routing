package ru.lct.heatnet.geo;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the streaming parser path: a child JVM limited to 256 MB heap parses a 600 MB GeoJSON. A reader that
 * loaded the file into a byte[] or a full JSON tree would fail with OutOfMemoryError.
 */
class LargeInputStreamingTest {

    @Test
    void parsesAFileLargerThanTheHeap() throws Exception {
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        List<String> cmd = new ArrayList<>();
        cmd.add(java);
        cmd.add("-Xmx256m");
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(LargeInputMain.class.getName());
        cmd.add("600");
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) out.append(line).append('\n');
        }
        int code = p.waitFor();
        System.out.println(out);
        assertEquals(0, code, "child JVM (-Xmx256m) must parse the 600 MB file: " + out);
        assertTrue(out.toString().contains("file=") && out.toString().contains("points=1"));
    }
}
