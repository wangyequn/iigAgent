package com.sinosoft.amp.openapi.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunEventBusTest {

    @Test
    void publishAndList() {
        RunEventBus bus = new RunEventBus();
        bus.publish("r1", "RUN_STARTED", null, "start", null);
        bus.publish("r1", "STEP_STARTED", "s1", "step", Map.of("k", "v"));
        List<RunEventBus.RunEvent> events = bus.list("r1");
        assertEquals(2, events.size());
        assertEquals("RUN_STARTED", events.get(0).type());
        assertEquals("s1", events.get(1).stepId());
        assertTrue(events.get(1).data().containsKey("k"));
    }

    @Test
    void capPerRun() {
        RunEventBus bus = new RunEventBus();
        for (int i = 0; i < 250; i++) {
            bus.publish("r2", "STEP_PROGRESS", "s", "m" + i, null);
        }
        assertEquals(200, bus.list("r2").size());
    }
}
