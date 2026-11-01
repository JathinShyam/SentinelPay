package com.sentinelpay.settlement.config;

import com.sentinelpay.outbox.config.KafkaTopicConfig;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Bounded consumer retries followed by publication to the shared dead-letter
 * topic. Poison records never block the partition indefinitely.
 */
@Configuration
public class SettlementConsumerConfig {

    @Bean
    CommonErrorHandler settlementErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new TopicPartition(
                        KafkaTopicConfig.DEAD_LETTER_TOPIC, record.partition()));
        return new DefaultErrorHandler(recoverer, new FixedBackOff(200L, 2L));
    }
}
