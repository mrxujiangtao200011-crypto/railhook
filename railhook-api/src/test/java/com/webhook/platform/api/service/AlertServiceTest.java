package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.AlertRule;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.repository.AlertEventRepository;
import com.webhook.platform.api.domain.repository.AlertRuleRepository;
import com.webhook.platform.api.domain.repository.DeliveryRepository;
import com.webhook.platform.api.domain.repository.MembershipRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.dto.AlertRuleRequest;
import com.webhook.platform.api.exception.DomainException;
import com.webhook.platform.api.service.alert.channel.AlertChannelConfigs;
import com.webhook.platform.api.service.alert.channel.AlertChannelRegistry;
import com.webhook.platform.api.service.alert.channel.AlertHttpClient;
import com.webhook.platform.api.service.alert.channel.EmailChannel;
import com.webhook.platform.api.service.alert.channel.InAppChannel;
import com.webhook.platform.api.service.alert.channel.WebhookChannel;
import com.webhook.platform.api.service.alert.condition.AlertConditionRegistry;
import com.webhook.platform.api.service.alert.condition.FailureRateCondition;
import com.webhook.platform.api.tenancy.TenantContext;
import com.webhook.platform.common.exception.InvalidUrlException;
import com.webhook.platform.common.security.EncryptionKeyRegistry;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AlertServiceTest {

    @Mock private AlertRuleRepository ruleRepository;
    @Mock private AlertEventRepository eventRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private IncidentService incidentService;
    @Mock private EncryptionKeyRegistry encryptionKeyRegistry;
    @Mock private AlertNotificationService notificationService;
    @Mock private MembershipRepository membershipRepository;

    private AlertService service;

    private final UUID organizationId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID ruleId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.set(organizationId);
        AlertHttpClient http = mock(AlertHttpClient.class);
        AlertChannelRegistry channels = new AlertChannelRegistry(List.of(new InAppChannel(),
                new EmailChannel(mock(EmailService.class), membershipRepository), new WebhookChannel(http)));
        service = new AlertService(ruleRepository, eventRepository, projectRepository, incidentService,
                notificationService, channels,
                new AlertChannelConfigs(encryptionKeyRegistry, false, Collections.emptyList()),
                new AlertConditionRegistry(List.of(new FailureRateCondition(mock(DeliveryRepository.class)))));

        when(projectRepository.findById(projectId))
                .thenReturn(Optional.of(Project.builder().id(projectId).organizationId(organizationId).name("p").build()));
        when(ruleRepository.save(any(AlertRule.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ruleRepository.findByIdAndProjectId(ruleId, projectId))
                .thenReturn(Optional.of(AlertRule.builder()
                        .id(ruleId).projectId(projectId).name("existing")
                        .alertType("FAILURE_RATE").build()));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    // Any address was accepted, making alert rules a way to mail anyone from this domain.
    @Nested
    @DisplayName("Alert rule email recipients are addresses of the organization's verified members, at most ten")
    class EmailRecipients {

        @Nested
        class RequestValidation {

            @BeforeEach
            void everyAddressIsAMember() {
                when(membershipRepository.findVerifiedMemberEmailsIn(anyCollection()))
                        .thenAnswer(inv -> List.copyOf(inv.<Collection<String>>getArgument(0)));
            }

            static Stream<Arguments> recipients() {
                return Stream.of(
                        Arguments.of("ops@company.com, dev@company.com", true),
                        Arguments.of(null, true),
                        Arguments.of("", true),
                        Arguments.of("ops@company.com, not-an-address", false),
                        Arguments.of("ops@company.com,,dev@company.com", false),
                        Arguments.of(addresses(10), true),
                        Arguments.of(addresses(11), false));
            }

            private static String addresses(int count) {
                return IntStream.rangeClosed(1, count)
                        .mapToObj(i -> "member" + i + "@company.com")
                        .collect(Collectors.joining(","));
            }

            // No recipients is how an update clears them.
            @ParameterizedTest
            @MethodSource("recipients")
            void acceptsUpToTenAddressesOrNone(String recipients, boolean valid) {
                ThrowingCallable create = () -> service.createRule(projectId, emailRequest(recipients));
                if (valid) {
                    assertThatCode(create).doesNotThrowAnyException();
                } else {
                    assertThatThrownBy(create).isInstanceOf(DomainException.class);
                }
            }
        }

        @Nested
        @DisplayName("the service")
        class MembershipRestriction {

            @BeforeEach
            void stubVerifiedMembers() {
                Set<String> members = Set.of("ops@company.com", "dev@company.com");
                when(membershipRepository.findVerifiedMemberEmailsIn(anyCollection())).thenAnswer(inv -> {
                    Collection<String> asked = inv.getArgument(0);
                    return asked.stream().filter(members::contains).toList();
                });
            }

            @Test
            void refusesAnAddressThatIsNotAVerifiedMemberOnCreate() {
                assertThatThrownBy(() -> service.createRule(projectId, request("ops@company.com, victim@elsewhere.com")))
                        .isInstanceOfSatisfying(DomainException.class,
                                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

                verify(ruleRepository, never()).save(any());
            }

            @Test
            void refusesAnAddressThatIsNotAVerifiedMemberOnUpdate() {
                assertThatThrownBy(() -> service.updateRule(projectId, ruleId, request("victim@elsewhere.com")))
                        .isInstanceOfSatisfying(DomainException.class,
                                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

                verify(ruleRepository, never()).save(any());
            }

            @Test
            void acceptsMembersAddresses_matchedWithoutRegardToCase_andStoresThemNormalized() {
                service.createRule(projectId, request(" Ops@Company.com ,dev@company.com"));

                ArgumentCaptor<AlertRule> saved = ArgumentCaptor.forClass(AlertRule.class);
                verify(ruleRepository).save(saved.capture());
                assertThat(saved.getValue().getChannelConfig().get("recipients")).isEqualTo("ops@company.com,dev@company.com");
            }

            private AlertRuleRequest request(String recipients) {
                return emailRequest(recipients);
            }
        }
    }

    // The notification webhook is fetched server-side, so it is an SSRF sink.
    @Nested
    @DisplayName("AlertService — a rule's notification URL is validated like any other outbound URL")
    class WebhookUrlValidation {

        @Test
        void metadataEndpointRefusedOnCreate() {
            assertThatThrownBy(() -> service.createRule(projectId, request("http://169.254.169.254/latest/meta-data/")))
                    .isInstanceOf(InvalidUrlException.class);

            verify(ruleRepository, never()).save(any());
        }

        @Test
        @DisplayName("a private address is refused on update too — the hole is not only on create")
        void privateAddressRefusedOnUpdate() {
            assertThatThrownBy(() -> service.updateRule(projectId, ruleId, request("http://127.0.0.1:8080/admin")))
                    .isInstanceOf(InvalidUrlException.class);

            verify(ruleRepository, never()).save(any());
        }

        @Test
        @DisplayName("a rule with no notification URL is unaffected")
        void nullUrlIsFine() {
            assertThatCode(() -> service.createRule(projectId, request(null))).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("clearing the URL with a blank string is not a validation failure")
        void blankUrlClearsRatherThanFails() {
            assertThatCode(() -> service.updateRule(projectId, ruleId, request("  ")))
                    .doesNotThrowAnyException();
        }

        private AlertRuleRequest request(String webhookUrl) {
            AlertRuleRequest r = new AlertRuleRequest();
            r.setName("rule");
            r.setAlertType("FAILURE_RATE");
            r.setThresholdValue(50.0);
            r.setChannel("WEBHOOK");
            r.setChannelConfig(webhookUrl == null ? Map.of() : Map.of("url", webhookUrl));
            return r;
        }
    }

    private static AlertRuleRequest emailRequest(String recipients) {
        return AlertRuleRequest.builder()
                .name("rule").alertType("FAILURE_RATE").thresholdValue(10.0)
                .channel("EMAIL").channelConfig(recipients == null ? Map.of() : Map.of("recipients", recipients))
                .build();
    }
}
