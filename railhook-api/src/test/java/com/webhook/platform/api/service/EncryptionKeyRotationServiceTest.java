package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.AlertRule;
import com.webhook.platform.api.domain.entity.Endpoint;
import com.webhook.platform.api.domain.entity.IncomingDestination;
import com.webhook.platform.api.domain.entity.IncomingSource;
import com.webhook.platform.api.domain.repository.AlertRuleRepository;
import com.webhook.platform.api.domain.repository.EndpointRepository;
import com.webhook.platform.api.domain.repository.IncomingDestinationRepository;
import com.webhook.platform.api.domain.repository.IncomingSourceRepository;
import com.webhook.platform.common.security.EncryptionKeyRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import com.webhook.platform.common.security.SecretEncryption;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EncryptionKeyRotationServiceTest {

    private static final String KEY_V1 = "old_master_key_32_chars_long_pad";
    private static final String KEY_V2 = "new_master_key_32_chars_long_pad";
    private static final String SALT = "test_salt_value";

    @Mock private EndpointRepository endpointRepository;
    @Mock private IncomingSourceRepository incomingSourceRepository;
    @Mock private IncomingDestinationRepository incomingDestinationRepository;
    @Mock private AlertRuleRepository alertRuleRepository;

    private EncryptionKeyRegistry registry;
    private EncryptionKeyRotationService service;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() throws Exception {
        registry = buildRegistry("", "1:" + KEY_V1 + ",2:" + KEY_V2, 2, SALT);
        meterRegistry = new SimpleMeterRegistry();

        TransactionTemplate txTemplate = mock(TransactionTemplate.class);
        lenient().doAnswer(inv -> {
            inv.<Consumer<TransactionStatus>>getArgument(0).accept(null);
            return null;
        }).when(txTemplate).executeWithoutResult(any());

        SimpleLock simpleLock = mock(SimpleLock.class);
        LockProvider lockProvider = mock(LockProvider.class);
        lenient().when(lockProvider.lock(any(LockConfiguration.class))).thenReturn(Optional.of(simpleLock));
        LockingTaskExecutor lockExecutor = new DefaultLockingTaskExecutor(lockProvider);
        lenient().when(alertRuleRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        service = new EncryptionKeyRotationService(
                endpointRepository, incomingSourceRepository, incomingDestinationRepository,
                alertRuleRepository, registry, txTemplate, lockExecutor, meterRegistry
        );
    }

    private static EncryptionKeyRegistry buildRegistry(String singleKey, String multiKeys,
                                                        int activeVersion, String salt) throws Exception {
        EncryptionKeyRegistry reg = new EncryptionKeyRegistry();
        setField(reg, "singleKey", singleKey);
        setField(reg, "multiKeys", multiKeys);
        setField(reg, "configuredActiveVersion", activeVersion);
        setField(reg, "salt", salt);
        var init = reg.getClass().getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(reg);
        return reg;
    }

    private static void setField(Object obj, String fieldName, Object value) throws Exception {
        Field f = obj.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(obj, value);
    }

    private Page<Endpoint> endpointPage(List<Endpoint> items) {
        return new PageImpl<>(items);
    }

    private Page<IncomingSource> sourcePage(List<IncomingSource> items) {
        return new PageImpl<>(items);
    }

    private Page<IncomingDestination> destPage(List<IncomingDestination> items) {
        return new PageImpl<>(items);
    }

    // Left on the old key, a PagerDuty or Opsgenie rule stops paging once that key is retired.
    @Test
    void reEncryptsAnAlertRulesOnCallKey() {
        SecretEncryption.EncryptedData key = SecretEncryption.encrypt("routing-key", KEY_V1, SALT, 1);
        AlertRule rule = AlertRule.builder()
                .id(UUID.randomUUID())
                .channelConfigEncrypted(Map.of("routingKey",
                        Map.of("ciphertext", key.getCiphertext(), "iv", key.getIv())))
                .encryptionKeyVersion(1)
                .build();
        when(endpointRepository.findAll(any(Pageable.class))).thenReturn(endpointPage(Collections.emptyList()));
        when(incomingSourceRepository.findAll(any(Pageable.class))).thenReturn(sourcePage(Collections.emptyList()));
        when(incomingDestinationRepository.findAll(any(Pageable.class))).thenReturn(destPage(Collections.emptyList()));
        when(alertRuleRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(rule)));

        EncryptionKeyRotationService.RotationResult result = service.rotateAll();

        assertThat(result.alertRulesRotated()).isEqualTo(1);
        assertThat(rule.getEncryptionKeyVersion()).isEqualTo(2);
        Map<String, String> sealed = rule.getChannelConfigEncrypted().get("routingKey");
        assertThat(SecretEncryption.decrypt(sealed.get("ciphertext"), sealed.get("iv"), KEY_V2, SALT))
                .isEqualTo("routing-key");
    }

    @Nested
    class EndpointRotation {

        @Test
        void rotatesEndpointSecret() {
            SecretEncryption.EncryptedData secret = SecretEncryption.encrypt("my-secret", KEY_V1, SALT, 1);

            Endpoint endpoint = Endpoint.builder()
                    .id(UUID.randomUUID())
                    .projectId(UUID.randomUUID())
                    .url("https://example.com")
                    .secretEncrypted(secret.getCiphertext())
                    .secretIv(secret.getIv())
                    .encryptionKeyVersion(1)
                    .build();

            when(endpointRepository.findAll(any(Pageable.class)))
                    .thenReturn(endpointPage(List.of(endpoint)))
                    .thenReturn(endpointPage(Collections.emptyList()));
            when(incomingSourceRepository.findAll(any(Pageable.class))).thenReturn(sourcePage(Collections.emptyList()));
            when(incomingDestinationRepository.findAll(any(Pageable.class))).thenReturn(destPage(Collections.emptyList()));
            when(endpointRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            EncryptionKeyRotationService.RotationResult result = service.rotateAll();

            assertThat(result.targetVersion()).isEqualTo(2);
            assertThat(result.endpointsRotated()).isEqualTo(1);
            assertThat(result.errors()).isZero();

            ArgumentCaptor<Endpoint> captor = ArgumentCaptor.forClass(Endpoint.class);
            verify(endpointRepository).save(captor.capture());

            Endpoint saved = captor.getValue();
            assertThat(saved.getEncryptionKeyVersion()).isEqualTo(2);
            assertThat(saved.getSecretEncrypted()).isNotEqualTo(secret.getCiphertext());

            String decrypted = SecretEncryption.decrypt(
                    saved.getSecretEncrypted(), saved.getSecretIv(), KEY_V2, SALT);
            assertThat(decrypted).isEqualTo("my-secret");
        }

        @Test
        void skipsAlreadyRotatedEndpoint() {
            Endpoint endpoint = Endpoint.builder()
                    .id(UUID.randomUUID())
                    .projectId(UUID.randomUUID())
                    .url("https://example.com")
                    .secretEncrypted("cipher")
                    .secretIv("iv")
                    .encryptionKeyVersion(2)
                    .build();

            when(endpointRepository.findAll(any(Pageable.class)))
                    .thenReturn(endpointPage(List.of(endpoint)))
                    .thenReturn(endpointPage(Collections.emptyList()));
            when(incomingSourceRepository.findAll(any(Pageable.class))).thenReturn(sourcePage(Collections.emptyList()));
            when(incomingDestinationRepository.findAll(any(Pageable.class))).thenReturn(destPage(Collections.emptyList()));

            EncryptionKeyRotationService.RotationResult result = service.rotateAll();

            assertThat(result.endpointsRotated()).isZero();
            verify(endpointRepository, never()).save(any());
        }

        @Test
        void rotatesClientCertAndKey() {
            SecretEncryption.EncryptedData secret = SecretEncryption.encrypt("sec", KEY_V1, SALT, 1);
            SecretEncryption.EncryptedData cert = SecretEncryption.encrypt("cert-pem", KEY_V1, SALT, 1);
            SecretEncryption.EncryptedData key = SecretEncryption.encrypt("key-pem", KEY_V1, SALT, 1);

            Endpoint endpoint = Endpoint.builder()
                    .id(UUID.randomUUID())
                    .projectId(UUID.randomUUID())
                    .url("https://example.com")
                    .secretEncrypted(secret.getCiphertext()).secretIv(secret.getIv())
                    .clientCertEncrypted(cert.getCiphertext()).clientCertIv(cert.getIv())
                    .clientKeyEncrypted(key.getCiphertext()).clientKeyIv(key.getIv())
                    .encryptionKeyVersion(1)
                    .build();

            when(endpointRepository.findAll(any(Pageable.class)))
                    .thenReturn(endpointPage(List.of(endpoint)))
                    .thenReturn(endpointPage(Collections.emptyList()));
            when(incomingSourceRepository.findAll(any(Pageable.class))).thenReturn(sourcePage(Collections.emptyList()));
            when(incomingDestinationRepository.findAll(any(Pageable.class))).thenReturn(destPage(Collections.emptyList()));
            when(endpointRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.rotateAll();

            ArgumentCaptor<Endpoint> captor = ArgumentCaptor.forClass(Endpoint.class);
            verify(endpointRepository).save(captor.capture());
            Endpoint saved = captor.getValue();

            assertThat(SecretEncryption.decrypt(saved.getClientCertEncrypted(), saved.getClientCertIv(), KEY_V2, SALT))
                    .isEqualTo("cert-pem");
            assertThat(SecretEncryption.decrypt(saved.getClientKeyEncrypted(), saved.getClientKeyIv(), KEY_V2, SALT))
                    .isEqualTo("key-pem");
        }
    }

    @Nested
    @DisplayName("IncomingSource rotation")
    class SourceRotation {

        @Test
        void rotatesHmacSecret() {
            SecretEncryption.EncryptedData hmac = SecretEncryption.encrypt("hmac-secret", KEY_V1, SALT, 1);

            IncomingSource source = IncomingSource.builder()
                    .id(UUID.randomUUID())
                    .projectId(UUID.randomUUID())
                    .name("test").slug("test")
                    .ingressPathToken("tok")
                    .hmacSecretEncrypted(hmac.getCiphertext())
                    .hmacSecretIv(hmac.getIv())
                    .encryptionKeyVersion(1)
                    .build();

            when(endpointRepository.findAll(any(Pageable.class))).thenReturn(endpointPage(Collections.emptyList()));
            when(incomingSourceRepository.findAll(any(Pageable.class)))
                    .thenReturn(sourcePage(List.of(source)))
                    .thenReturn(sourcePage(Collections.emptyList()));
            when(incomingDestinationRepository.findAll(any(Pageable.class))).thenReturn(destPage(Collections.emptyList()));
            when(incomingSourceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            EncryptionKeyRotationService.RotationResult result = service.rotateAll();

            assertThat(result.sourcesRotated()).isEqualTo(1);

            ArgumentCaptor<IncomingSource> captor = ArgumentCaptor.forClass(IncomingSource.class);
            verify(incomingSourceRepository).save(captor.capture());
            IncomingSource saved = captor.getValue();

            assertThat(saved.getEncryptionKeyVersion()).isEqualTo(2);
            assertThat(SecretEncryption.decrypt(saved.getHmacSecretEncrypted(), saved.getHmacSecretIv(), KEY_V2, SALT))
                    .isEqualTo("hmac-secret");
        }

        @Test
        void skipsSourceWithoutHmacSecret() {
            IncomingSource source = IncomingSource.builder()
                    .id(UUID.randomUUID())
                    .projectId(UUID.randomUUID())
                    .name("test").slug("test")
                    .ingressPathToken("tok")
                    .hmacSecretEncrypted(null)
                    .encryptionKeyVersion(1)
                    .build();

            when(endpointRepository.findAll(any(Pageable.class))).thenReturn(endpointPage(Collections.emptyList()));
            when(incomingSourceRepository.findAll(any(Pageable.class)))
                    .thenReturn(sourcePage(List.of(source)))
                    .thenReturn(sourcePage(Collections.emptyList()));
            when(incomingDestinationRepository.findAll(any(Pageable.class))).thenReturn(destPage(Collections.emptyList()));

            EncryptionKeyRotationService.RotationResult result = service.rotateAll();

            assertThat(result.sourcesRotated()).isZero();
            verify(incomingSourceRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("IncomingDestination rotation")
    class DestinationRotation {

        @Test
        void rotatesAuthConfig() {
            SecretEncryption.EncryptedData auth = SecretEncryption.encrypt("{\"type\":\"bearer\"}", KEY_V1, SALT, 1);

            IncomingDestination dest = IncomingDestination.builder()
                    .id(UUID.randomUUID())
                    .incomingSourceId(UUID.randomUUID())
                    .url("https://dest.com")
                    .authConfigEncrypted(auth.getCiphertext())
                    .authConfigIv(auth.getIv())
                    .encryptionKeyVersion(1)
                    .build();

            when(endpointRepository.findAll(any(Pageable.class))).thenReturn(endpointPage(Collections.emptyList()));
            when(incomingSourceRepository.findAll(any(Pageable.class))).thenReturn(sourcePage(Collections.emptyList()));
            when(incomingDestinationRepository.findAll(any(Pageable.class)))
                    .thenReturn(destPage(List.of(dest)))
                    .thenReturn(destPage(Collections.emptyList()));
            when(incomingDestinationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            EncryptionKeyRotationService.RotationResult result = service.rotateAll();

            assertThat(result.destinationsRotated()).isEqualTo(1);

            ArgumentCaptor<IncomingDestination> captor = ArgumentCaptor.forClass(IncomingDestination.class);
            verify(incomingDestinationRepository).save(captor.capture());
            IncomingDestination saved = captor.getValue();

            assertThat(saved.getEncryptionKeyVersion()).isEqualTo(2);
            assertThat(SecretEncryption.decrypt(saved.getAuthConfigEncrypted(), saved.getAuthConfigIv(), KEY_V2, SALT))
                    .isEqualTo("{\"type\":\"bearer\"}");
        }
    }

    @Nested
    class ErrorHandling {

        @Test
        void countsErrorsAndContinues() {
            Endpoint badEndpoint = Endpoint.builder()
                    .id(UUID.randomUUID())
                    .projectId(UUID.randomUUID())
                    .url("https://example.com")
                    .secretEncrypted("garbage_cipher")
                    .secretIv("garbage_iv")
                    .encryptionKeyVersion(1)
                    .build();

            SecretEncryption.EncryptedData good = SecretEncryption.encrypt("good", KEY_V1, SALT, 1);
            Endpoint goodEndpoint = Endpoint.builder()
                    .id(UUID.randomUUID())
                    .projectId(UUID.randomUUID())
                    .url("https://example.com")
                    .secretEncrypted(good.getCiphertext())
                    .secretIv(good.getIv())
                    .encryptionKeyVersion(1)
                    .build();

            when(endpointRepository.findAll(any(Pageable.class)))
                    .thenReturn(endpointPage(List.of(badEndpoint, goodEndpoint)))
                    .thenReturn(endpointPage(Collections.emptyList()));
            when(incomingSourceRepository.findAll(any(Pageable.class))).thenReturn(sourcePage(Collections.emptyList()));
            when(incomingDestinationRepository.findAll(any(Pageable.class))).thenReturn(destPage(Collections.emptyList()));
            when(endpointRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            EncryptionKeyRotationService.RotationResult result = service.rotateAll();

            assertThat(result.errors()).isEqualTo(1);
            assertThat(result.endpointsRotated()).isEqualTo(1);
            assertThat(meterRegistry.find("encryption_rotation_partial_failures_total").counter())
                    .as("a partial failure reaches the observability counter, not just the count field")
                    .isNotNull()
                    .extracting(Counter::count)
                    .isEqualTo(1.0);
        }
    }

    @Nested
    class DistributedLock {

        @Test
        void throwsWhenLockNotAcquired() throws Exception {
            LockProvider noLockProvider = mock(LockProvider.class);
            when(noLockProvider.lock(any(LockConfiguration.class))).thenReturn(Optional.empty());
            LockingTaskExecutor noopLockExecutor = new DefaultLockingTaskExecutor(noLockProvider);

            TransactionTemplate txTemplate = mock(TransactionTemplate.class);

            EncryptionKeyRotationService lockedService = new EncryptionKeyRotationService(
                    endpointRepository, incomingSourceRepository, incomingDestinationRepository,
                    alertRuleRepository,
                    registry, txTemplate, noopLockExecutor, meterRegistry
            );

            assertThatThrownBy(lockedService::rotateAll)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already in progress");
        }
    }
}
