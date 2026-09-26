package com.webhook.platform.worker.config;

import com.webhook.platform.common.constants.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

// Only kafka-init created topics, once; a recreated broker lost the DLQ topics for good.
class KafkaTopicsConfigTest {

    @Test
    void declaresEveryTopicWithTheConfiguredPartitionCount() {
        Map<String, NewTopic> byName = KafkaTopicsConfig.topics(12).stream()
                .collect(Collectors.toMap(NewTopic::name, Function.identity()));
        assertThat(byName.keySet()).containsExactlyInAnyOrder(
                KafkaTopics.DELIVERIES_DISPATCH,
                KafkaTopics.DELIVERIES_RETRY_1M, KafkaTopics.DELIVERIES_RETRY_5M,
                KafkaTopics.DELIVERIES_RETRY_15M, KafkaTopics.DELIVERIES_RETRY_1H,
                KafkaTopics.DELIVERIES_RETRY_6H, KafkaTopics.DELIVERIES_RETRY_24H,
                KafkaTopics.DELIVERIES_DLQ,
                KafkaTopics.INCOMING_FORWARD_DISPATCH, KafkaTopics.INCOMING_FORWARD_RETRY,
                KafkaTopics.INCOMING_FORWARD_DLQ);
        assertThat(byName.values()).allSatisfy(topic -> assertThat(topic.numPartitions()).isEqualTo(12));
    }

    // The API can send before the worker declares topics; a name missing here gets broker defaults.
    @Test
    void composeAndHelmCreateTheSameTopics() throws IOException {
        List<String> declared = KafkaTopicsConfig.topics(1).stream().map(NewTopic::name).toList();
        for (Path creator : List.of(Path.of("..", "docker-compose.yml"),
                Path.of("..", "deploy", "helm", "railhook", "templates", "kafka-topics-job.yaml"))) {
            String text = Files.readString(creator);
            assertThat(declared).as(creator.toString()).allSatisfy(topic ->
                    assertThat(text).containsPattern("(?<![\\w.])" + Pattern.quote(topic) + "(?![\\w.])"));
        }
    }
}
