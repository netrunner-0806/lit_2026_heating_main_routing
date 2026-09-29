package ru.lct.heatnet.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Collected input/processing diagnostics with severity. */
public final class Diagnostics {

    public enum Severity { INFO, WARNING, ERROR }

    public static final class Message {
        private final Severity severity;
        private final String code;
        private final String text;
        private final Integer featureIndex;
        private final String featureId;

        public Message(Severity severity, String code, String text, Integer featureIndex, String featureId) {
            this.severity = severity;
            this.code = code;
            this.text = text;
            this.featureIndex = featureIndex;
            this.featureId = featureId;
        }

        public Severity getSeverity() { return severity; }
        public String getCode() { return code; }
        public String getText() { return text; }
        public Integer getFeatureIndex() { return featureIndex; }
        public String getFeatureId() { return featureId; }

        @Override
        public String toString() {
            return severity + " [" + code + "] " + text + (featureIndex != null ? " (feature #" + featureIndex + (featureId != null ? ", id=" + featureId : "") + ")" : "");
        }
    }

    private final List<Message> messages = new ArrayList<>();

    public void info(String code, String text) { messages.add(new Message(Severity.INFO, code, text, null, null)); }
    public void warn(String code, String text) { messages.add(new Message(Severity.WARNING, code, text, null, null)); }
    public void warn(String code, String text, Integer featureIndex, JsonId id) {
        messages.add(new Message(Severity.WARNING, code, text, featureIndex, id == null ? null : id.text()));
    }
    public void error(String code, String text) { messages.add(new Message(Severity.ERROR, code, text, null, null)); }
    public void error(String code, String text, Integer featureIndex, JsonId id) {
        messages.add(new Message(Severity.ERROR, code, text, featureIndex, id == null ? null : id.text()));
    }

    public List<Message> messages() { return Collections.unmodifiableList(messages); }
    public boolean hasErrors() { return messages.stream().anyMatch(m -> m.severity == Severity.ERROR); }
    public long warningCount() { return messages.stream().filter(m -> m.severity == Severity.WARNING).count(); }
    public List<Message> errors() {
        List<Message> r = new ArrayList<>();
        for (Message m : messages) if (m.severity == Severity.ERROR) r.add(m);
        return r;
    }
}
