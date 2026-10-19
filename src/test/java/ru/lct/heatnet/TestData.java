package ru.lct.heatnet;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Locations of the competition datasets (relative to the project root). */
public final class TestData {
    private TestData() {}

    public static final Path CORRECTED = Paths.get(System.getProperty("heatnet.corrected.input", "src/test/resources/fixtures/competition-corrected.geojson"));

    public static boolean correctedAvailable() { return Files.exists(CORRECTED); }
}
