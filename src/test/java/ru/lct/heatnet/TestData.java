package ru.lct.heatnet;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Locations of the competition datasets (relative to the project root). */
public final class TestData {
    private TestData() {}

    public static final Path CORRECTED = Paths.get("ТЗ", "Датасет скорректированный.geojson");

    public static boolean correctedAvailable() { return Files.exists(CORRECTED); }
}
