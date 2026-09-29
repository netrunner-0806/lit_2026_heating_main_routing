package ru.lct.heatnet.output;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Streams output features as a GeoJSON FeatureCollection (WGS84). */
public final class GeoJsonResultWriter {

    private static final JsonFactory FACTORY = new JsonFactory();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public void write(Path file, List<OutputFeature> features) throws IOException {
        try (OutputStream out = Files.newOutputStream(file)) {
            write(out, features);
        }
    }

    public void write(OutputStream out, List<OutputFeature> features) throws IOException {
        try (JsonGenerator g = FACTORY.createGenerator(out)) {
            g.setCodec(MAPPER);
            g.useDefaultPrettyPrinter();
            g.writeStartObject();
            g.writeStringField("type", "FeatureCollection");
            g.writeStringField("name", "heat_network_variants");
            g.writeObjectFieldStart("crs");
            g.writeStringField("type", "name");
            g.writeObjectFieldStart("properties");
            g.writeStringField("name", "urn:ogc:def:crs:OGC:1.3:CRS84");
            g.writeEndObject();
            g.writeEndObject();
            g.writeArrayFieldStart("features");
            for (OutputFeature f : features) writeFeature(g, f);
            g.writeEndArray();
            g.writeEndObject();
        }
    }

    private void writeFeature(JsonGenerator g, OutputFeature f) throws IOException {
        g.writeStartObject();
        g.writeStringField("type", "Feature");
        g.writeObjectFieldStart("properties");
        for (Map.Entry<String, Object> e : f.properties().entrySet()) {
            g.writeFieldName(e.getKey());
            g.writeObject(e.getValue());
        }
        g.writeEndObject();
        g.writeFieldName("geometry");
        switch (f.geomType()) {
            case POINT:
                g.writeStartObject();
                g.writeStringField("type", "Point");
                g.writeFieldName("coordinates");
                writeCoord(g, f.point());
                g.writeEndObject();
                break;
            case LINESTRING:
                g.writeStartObject();
                g.writeStringField("type", "LineString");
                g.writeArrayFieldStart("coordinates");
                for (double[] c : f.line()) writeCoord(g, c);
                g.writeEndArray();
                g.writeEndObject();
                break;
            default:
                g.writeNull();
        }
        g.writeEndObject();
    }

    private static void writeCoord(JsonGenerator g, double[] c) throws IOException {
        g.writeStartArray();
        g.writeNumber(c[0]);
        g.writeNumber(c[1]);
        g.writeEndArray();
    }
}
