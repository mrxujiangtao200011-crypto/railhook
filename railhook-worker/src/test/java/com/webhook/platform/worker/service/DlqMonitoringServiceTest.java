package com.webhook.platform.worker.service;

import com.webhook.platform.worker.domain.repository.DeliveryRepository;
import com.webhook.platform.worker.domain.repository.IncomingForwardAttemptRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;


import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

/** DLQ depth comes from the database per direction; one failing direction must not blank the other. */
class DlqMonitoringServiceTest {

    private DlqMonitoringService service;

    @Test
    void monitorDlqDepth_actionableDepth_returnsToZero_afterBacklogCleared() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        DeliveryRepository deliveryRepository = Mockito.mock(DeliveryRepository.class);
        IncomingForwardAttemptRepository forwardRepository = Mockito.mock(IncomingForwardAttemptRepository.class);
        when(deliveryRepository.countDlqTotal()).thenReturn(12L);
        when(forwardRepository.countDlqTotal()).thenReturn(3L);
        service = new DlqMonitoringService(deliveryRepository, forwardRepository, meterRegistry);

        service.monitorDlqDepth();
        var gauge = meterRegistry.find("webhook_dlq_depth").gauge();
        var incomingGauge = meterRegistry.find("incoming_forward_dlq_depth").gauge();
        assertNotNull(gauge);
        assertNotNull(incomingGauge);
        assertEquals(12.0, gauge.value(), "depth must reflect the DLQ backlog while it exists");
        assertEquals(3.0, incomingGauge.value(), "depth must reflect the Forward DLQ backlog while it exists");

        when(deliveryRepository.countDlqTotal()).thenReturn(0L);
        when(forwardRepository.countDlqTotal()).thenReturn(0L);
        service.monitorDlqDepth();

        assertEquals(0.0, gauge.value(), "depth must return to 0 once the DLQ backlog is cleared");
        assertEquals(0.0, incomingGauge.value(), "depth must return to 0 once the Forward DLQ backlog is cleared");
    }

    @Test
    void monitorDlqDepth_failingDeliveryCount_stillReportsForwardBacklog() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        DeliveryRepository deliveryRepository = Mockito.mock(DeliveryRepository.class);
        IncomingForwardAttemptRepository forwardRepository = Mockito.mock(IncomingForwardAttemptRepository.class);
        when(deliveryRepository.countDlqTotal()).thenThrow(new IllegalStateException("db down"));
        when(forwardRepository.countDlqTotal()).thenReturn(5L);
        service = new DlqMonitoringService(deliveryRepository, forwardRepository, meterRegistry);

        assertDoesNotThrow(() -> service.monitorDlqDepth());

        var incomingGauge = meterRegistry.find("incoming_forward_dlq_depth").gauge();
        assertNotNull(incomingGauge);
        assertEquals(5.0, incomingGauge.value(), "a failing Delivery count must not suppress the Forward gauge");
    }

    @Test
    void monitorDlqDepth_failingForwardCount_stillReportsDeliveryBacklog() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        DeliveryRepository deliveryRepository = Mockito.mock(DeliveryRepository.class);
        IncomingForwardAttemptRepository forwardRepository = Mockito.mock(IncomingForwardAttemptRepository.class);
        when(deliveryRepository.countDlqTotal()).thenReturn(9L);
        when(forwardRepository.countDlqTotal()).thenThrow(new IllegalStateException("db down"));
        service = new DlqMonitoringService(deliveryRepository, forwardRepository, meterRegistry);

        assertDoesNotThrow(() -> service.monitorDlqDepth());

        var gauge = meterRegistry.find("webhook_dlq_depth").gauge();
        assertNotNull(gauge);
        assertEquals(9.0, gauge.value(), "a failing Forward count must not suppress the Delivery gauge");
    }
}
