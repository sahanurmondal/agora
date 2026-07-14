package com.agora.notification;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@SpringBootApplication
public class NotificationApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationApplication.class, args);
    }

    /**
     * Retry-then-DLQ policy: 3 attempts with exponential backoff, then the
     * poison message goes to <topic>.DLT and the partition MOVES ON — one bad
     * message must not block the log (head-of-line poison is chaos exp 02's
     * lesson). Redrive = consume the DLT and republish (endpoint below).
     */
    @Bean
    DefaultErrorHandler errorHandler(KafkaTemplate<Object, Object> template) {
        var backOff = new ExponentialBackOff(200, 2.0);
        backOff.setMaxElapsedTime(3_000);
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (ConsumerRecord<?, ?> rec, Exception ex) ->
                        new org.apache.kafka.common.TopicPartition(rec.topic() + ".DLT", rec.partition()));
        return new DefaultErrorHandler(recoverer, backOff);
    }
}
