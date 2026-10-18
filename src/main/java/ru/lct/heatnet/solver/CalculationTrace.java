package ru.lct.heatnet.solver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Bounded, ordered list of calculation decisions of one variant. */
public final class CalculationTrace {
    public static final int MAX_DECISIONS = 600;
    private final List<CalculationDecision> decisions = new ArrayList<>();
    private int dropped;

    public void add(String type, String objectId, String decision, String reason, Map<String, Object> inputs, List<String> alternatives) {
        if (decisions.size() >= MAX_DECISIONS) { dropped++; return; }
        decisions.add(new CalculationDecision(type, objectId, decision, reason, inputs, alternatives));
    }

    public static final class Checkpoint {
        private final int size, dropped;
        private Checkpoint(int size, int dropped) { this.size = size; this.dropped = dropped; }
    }
    public Checkpoint checkpoint() { return new Checkpoint(decisions.size(), dropped); }
    public void rollback(Checkpoint checkpoint) {
        decisions.subList(checkpoint.size, decisions.size()).clear();
        dropped = checkpoint.dropped;
    }
    /** Keep the final ranking explanation even when earlier decisions filled the bounded journal. */
    public void addFinal(String type, String objectId, String decision, String reason, Map<String, Object> inputs, List<String> alternatives) {
        if (decisions.size() == MAX_DECISIONS) { decisions.remove(decisions.size() - 1); dropped++; }
        add(type, objectId, decision, reason, inputs, alternatives);
    }

    public List<CalculationDecision> decisions() { return Collections.unmodifiableList(decisions); }
    public int dropped() { return dropped; }

    public static Map<String, Object> inputs(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }
}
