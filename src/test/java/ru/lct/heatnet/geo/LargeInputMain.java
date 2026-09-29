package ru.lct.heatnet.geo;

import ru.lct.heatnet.domain.InputModel;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates a synthetic GeoJSON much larger than the JVM heap it is run with and parses it with the streaming reader.
 * Used by {@link LargeInputStreamingTest} in a child JVM (-Xmx256m) and by scripts/large-file-smoke.sh.
 * The file contains a small valid problem plus many padded features of an unsupported object_type: the raw bytes
 * never fit into the heap while the normalised model stays tiny.
 */
public final class LargeInputMain {

    public static void main(String[] args) throws IOException {
        long targetBytes = Long.parseLong(args[0]) * 1024L * 1024L;
        Path file = args.length > 1 ? Path.of(args[1]) : Files.createTempFile("heatnet-large-", ".geojson");
        long written = generate(file, targetBytes);
        long t0 = System.currentTimeMillis();
        InputModel m = new GeoJsonStreamReader(ParseOptions.defaults()).read(file);
        long ms = System.currentTimeMillis() - t0;
        Runtime rt = Runtime.getRuntime();
        System.out.printf("file=%d MB features=%d networkLines=%d points=%d restrictions=%d parseMs=%d maxHeap=%d MB%n",
                written / (1024 * 1024), m.featureCount(), m.networkLines().size(), m.connectionPoints().size(), m.restrictions().size(), ms, rt.maxMemory() / (1024 * 1024));
        if (args.length <= 1) Files.deleteIfExists(file);
        if (m.networkLines().size() != 1 || m.connectionPoints().size() != 1) System.exit(3);
    }

    /** Writes the file feature by feature (never holding it in memory) and returns the byte count. */
    public static long generate(Path file, long targetBytes) throws IOException {
        String pad = "x".repeat(4000);
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write("{\"type\":\"FeatureCollection\",\"features\":[\n");
            w.write("{\"type\":\"Feature\",\"properties\":{\"id\":1,\"object_type\":\"heat_network\",\"diameter\":300},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[37.6,55.75],[37.605,55.75]]}},\n");
            w.write("{\"type\":\"Feature\",\"properties\":{\"id\":2,\"object_type\":\"oks_connection_point\",\"flow_tph\":10},\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.6025,55.7515]}},\n");
            w.write("{\"type\":\"Feature\",\"properties\":{\"id\":3,\"object_type\":\"restriction\",\"restriction_type\":\"oks\"},\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[37.6023,55.7513],[37.6027,55.7513],[37.6027,55.7517],[37.6023,55.7517],[37.6023,55.7513]]]}}");
            long bytes = 0;
            long i = 0;
            while (bytes < targetBytes) {
                String f = ",\n{\"type\":\"Feature\",\"properties\":{\"id\":\"pad-" + i + "\",\"object_type\":\"unsupported_padding\",\"description\":\"" + pad + "\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.61,55.76]}}";
                w.write(f);
                bytes += f.length();
                i++;
            }
            w.write("\n]}\n");
        }
        return Files.size(file);
    }
}
