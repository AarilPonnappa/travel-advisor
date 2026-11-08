package com.example.jarvis.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record MoneySummary(BigDecimal transport, BigDecimal stays, BigDecimal activities,
                           BigDecimal budget, String currency) {

    public BigDecimal total() {
        return transport.add(stays).add(activities);
    }

    // share of each bucket in the bar, as a css width
    public int transportPct() {
        return pct(transport);
    }

    public int staysPct() {
        return pct(stays);
    }

    public int activitiesPct() {
        return pct(activities);
    }

    public boolean overBudget() {
        return budget != null && budget.signum() > 0 && total().compareTo(budget) > 0;
    }

    public int budgetUsedPct() {
        if (budget == null || budget.signum() <= 0) return 0;
        return total().multiply(BigDecimal.valueOf(100)).divide(budget, 0, RoundingMode.HALF_UP).intValue();
    }

    private int pct(BigDecimal part) {
        // against the budget when there is one, otherwise against the running total
        BigDecimal base = (budget != null && budget.signum() > 0 && budget.compareTo(total()) >= 0) ? budget : total();
        if (base.signum() <= 0) return 0;
        return part.multiply(BigDecimal.valueOf(100)).divide(base, 0, RoundingMode.HALF_UP).intValue();
    }
}
