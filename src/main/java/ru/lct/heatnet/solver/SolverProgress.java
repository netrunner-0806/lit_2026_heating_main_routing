package ru.lct.heatnet.solver;

import java.util.*;
import java.util.function.Consumer;

/** Job-local observation only. Publish at phase/strategy boundaries, never inside routing loops. */
final class SolverProgress {
    private final Consumer<String> sink;
    private final String mode;
    private final List<String> order = new ArrayList<>();
    private final Set<String> active = new HashSet<>(), completed = new HashSet<>(), formed = new HashSet<>();
    private String phase = "Building routing graph";
    private int validated;
    private boolean recovery;
    SolverProgress(SolverConfig config, List<Strategy> strategies, Consumer<String> sink) {
        this.sink = sink; this.mode = config.executionMode.name();
        for (Strategy s : strategies) order.add(s.name());
    }
    synchronized void phase(String value) { phase = value; publish(); }
    synchronized void start(Strategy s) { active.add(s.name()); if (!phase.startsWith("Validating")) phase = "Generating variants"; publish(); }
    synchronized void finish(Strategy s, boolean hasResult) {
        active.remove(s.name()); completed.add(s.name());
        if (hasResult) formed.add(s.name());
        publish();
    }
    synchronized void recovery() { recovery = true; publish(); }
    synchronized void validated(int count) { validated = count; publish(); }
    private void publish() {
        if (sink == null) return;
        List<String> names = new ArrayList<>();
        for (String name : order) if (active.contains(name)) names.add(name);
        String value = phase + "; mode=" + mode + "; completed=" + completed.size() + "; total=" + order.size()
                + "; active=" + String.join(",", names) + "; formed=" + formed.size()
                + "; validated=" + validated + "; recovery=" + recovery;
        // A failed observer must not change routing, budgets, or worker termination.
        try { sink.accept(value); }
        catch (RuntimeException ex) { org.slf4j.LoggerFactory.getLogger(SolverProgress.class).warn("Progress observer failed", ex); }
    }
}
