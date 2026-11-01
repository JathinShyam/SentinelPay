package com.sentinelpay.outbox.config;

import com.sentinelpay.common.config.OutboxProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    public static final String LEDGER_TOPIC = "ledger.transactions.v1";
    public static final String DEAD_LETTER_TOPIC = "ledger.transactions.v1.DLT";

    @Bean
    NewTopic ledgerTransactionsTopic(OutboxProperties properties) {
        return TopicBuilder.name(properties.topic())
                .partitions(6)
                .replicas(1)
                .build();
    }

    @Bean
    NewTopic ledgerTransactionsDeadLetterTopic() {
        return TopicBuilder.name(DEAD_LETTER_TOPIC)
                .partitions(6)
                .replicas(1)
                .build();
    }
}
