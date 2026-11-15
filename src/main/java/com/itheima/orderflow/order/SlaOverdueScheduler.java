package com.itheima.orderflow.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class SlaOverdueScheduler {

    private static final Logger log = LoggerFactory.getLogger(SlaOverdueScheduler.class);

    private final OrderMapper orderMapper;
    private final OrderService orderService;
    private final int batchSize;
    private final boolean scanEnabled;

    SlaOverdueScheduler(OrderMapper orderMapper, OrderService orderService,
                        @Value("${indusflow.fulfillment.overdue-scan-batch-size}") int batchSize,
                        @Value("${indusflow.fulfillment.scan-enabled}") boolean scanEnabled) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("Overdue scan batch size must be positive");
        }
        this.orderMapper = orderMapper;
        this.orderService = orderService;
        this.batchSize = batchSize;
        this.scanEnabled = scanEnabled;
    }

    @Scheduled(fixedDelayString = "${indusflow.fulfillment.overdue-scan-interval-ms}",
            initialDelayString = "${indusflow.fulfillment.overdue-scan-interval-ms}")
    void scheduledScan() {
        if (scanEnabled) {
            scanOnce(OrderService.businessNow());
        }
    }

    public void scanOnce(LocalDateTime now) {
        for (Order candidate : orderMapper.findOverdueCandidates(now, batchSize)) {
            // The scheduler has no transaction. Each call to the service gets its own.
            try {
                orderService.markOverdue(candidate.getId(), candidate.getVersion(),
                        candidate.getAcceptanceDeadlineAt(), now);
            } catch (RuntimeException exception) {
                log.error("Could not mark order {} overdue", candidate.getId(), exception);
            }
        }
    }
}
