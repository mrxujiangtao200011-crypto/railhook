package com.webhook.platform.api.domain.entity;

import java.time.Duration;
import java.time.Period;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum BillingInterval {
    MONTHLY(Period.ofMonths(1)),
    YEARLY(Period.ofYears(1));

    private final Period period;
}
