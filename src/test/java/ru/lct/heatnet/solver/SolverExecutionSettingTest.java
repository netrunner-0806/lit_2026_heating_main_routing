package ru.lct.heatnet.solver;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import ru.lct.heatnet.config.HeatnetProperties;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SolverExecutionSettingTest {
    @Test void defaultRemainsSequentialAndExplicitSettingBinds() {
        assertEquals(SolverConfig.ExecutionMode.SEQUENTIAL, new HeatnetProperties().getSolverExecutionMode());
        var binder = new Binder(new MapConfigurationPropertySource(Map.of("heatnet.solver-execution-mode", "PARALLEL_2")));
        var props = binder.bind("heatnet", Bindable.of(HeatnetProperties.class)).get();
        assertEquals(SolverConfig.ExecutionMode.PARALLEL_2, props.getSolverExecutionMode());
    }
    @Test void invalidModeCannotSilentlyEnableOrDisableParallelism() {
        var binder = new Binder(new MapConfigurationPropertySource(Map.of("heatnet.solver-execution-mode", "PARALLEL_5")));
        assertThrows(org.springframework.boot.context.properties.bind.BindException.class,
                () -> binder.bind("heatnet", Bindable.of(HeatnetProperties.class)));
    }
}
