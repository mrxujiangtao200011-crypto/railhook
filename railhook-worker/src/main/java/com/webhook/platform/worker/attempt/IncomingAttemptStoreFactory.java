package com.webhook.platform.worker.attempt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.common.security.EncryptionKeyRegistry;
import com.webhook.platform.worker.domain.entity.IncomingDestination;
import com.webhook.platform.worker.domain.entity.IncomingEvent;
import com.webhook.platform.worker.domain.entity.IncomingForwardAttempt;
import com.webhook.platform.worker.domain.repository.IncomingForwardAttemptRepository;
import com.webhook.platform.worker.service.PayloadTransformService;
import com.webhook.platform.worker.service.TransformationCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClient;

@Component
@RequiredArgsConstructor
public class IncomingAttemptStoreFactory {

    private final IncomingForwardAttemptRepository attemptRepository;
    private final ProjectStatusLookup projectStatusLookup;
    private final TransactionTemplate transactionTemplate;
    private final TransformationCacheService transformationCacheService;
    private final PayloadTransformService payloadTransformService;
    private final EncryptionKeyRegistry encryptionKeyRegistry;
    private final ObjectMapper objectMapper;
    @Qualifier("incomingForwardWebClient")
    private final WebClient incomingForwardWebClient;
    private final TargetFailureRecorder targetFailureRecorder;

    public IncomingAttemptStore create(IncomingForwardAttempt claimed, IncomingEvent event,
            IncomingDestination destination) {
        return new IncomingAttemptStore(
                attemptRepository, projectStatusLookup, transactionTemplate, transformationCacheService,
                payloadTransformService, encryptionKeyRegistry, objectMapper,
                incomingForwardWebClient, targetFailureRecorder,
                claimed, event, destination);
    }
}
