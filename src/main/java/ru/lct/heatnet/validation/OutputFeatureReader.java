package ru.lct.heatnet.validation;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.lct.heatnet.output.OutputFeature;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Reads an output GeoJSON back into {@link OutputFeature}s (for independent validation of a file). */
public final class OutputFeatureReader {

    private static final JsonFactory FACTORY = new JsonFactory();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public List<OutputFeature> read(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) { return read(in); }
    }

    public List<OutputFeature> read(InputStream in) throws IOException {
        List<OutputFeature> out = new ArrayList<>();
        try (JsonParser p = FACTORY.createParser(in)) {
            if (p.nextToken() != JsonToken.START_OBJECT) throw new IOException("not a JSON object");
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String f = p.getCurrentName();
                p.nextToken();
                if ("features".equals(f)) {
                    while (p.nextToken() == JsonToken.START_OBJECT) out.add(convert(MAPPER.readTree(p)));
                } else {
                    p.skipChildren();
                }
            }
        }
        return out;
    }

    /** HTTP analyses retain the result model; bound its raw representation before Jackson allocates it.
     * This is a resource limit, not an input-file/upload size limit. */
    public List<OutputFeature> readBounded(InputStream in, long byteLimit) throws IOException {
        if (byteLimit<=0) throw new IllegalArgumentException("positive result analysis budget required");
        return read(new java.io.FilterInputStream(in) {
            long count;
            private void charge(int n) {
                if(n>0 && (count+=n)>byteLimit)
                    throw new ru.lct.heatnet.routing.ComplexityLimitException("HTTP result analysis exceeds " + byteLimit + " input bytes; download remains available");
            }
            @Override public int read() throws IOException {int n=in.read();charge(n<0?0:1);return n;}
            @Override public int read(byte[] b,int off,int len) throws IOException {
                int n=in.read(b,off,(int)Math.min(len,Math.max(1,byteLimit-count+1)));charge(n);return n;
            }
        });
    }

    private static OutputFeature convert(JsonNode feature) {
        JsonNode geom = feature.get("geometry");
        OutputFeature of;
        if (geom == null || geom.isNull()) {
            of = OutputFeature.noGeometry();
        } else {
            String type = geom.path("type").asText("");
            JsonNode c = geom.get("coordinates");
            if ("Point".equals(type)) of = OutputFeature.point(new double[]{c.get(0).asDouble(), c.get(1).asDouble()});
            else if ("LineString".equals(type)) {
                List<double[]> cs = new ArrayList<>();
                for (JsonNode n : c) cs.add(new double[]{n.get(0).asDouble(), n.get(1).asDouble()});
                of = OutputFeature.line(cs);
            } else {
                of = OutputFeature.noGeometry().prop("_unsupported_geometry", type);
            }
        }
        JsonNode props = feature.path("properties");
        for (Iterator<Map.Entry<String, JsonNode>> it = props.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            of.prop(e.getKey(), toJava(e.getValue()));
        }
        return of;
    }

    private static Object toJava(JsonNode n) {
        if (n == null || n.isNull()) return null;
        if (n.isNumber()) return n.numberValue();
        if (n.isTextual()) return n.asText();
        if (n.isBoolean()) return n.asBoolean();
        if (n.isArray()) {
            List<Object> l = new ArrayList<>();
            for (JsonNode x : n) l.add(toJava(x));
            return l;
        }
        return n.toString();
    }
}
