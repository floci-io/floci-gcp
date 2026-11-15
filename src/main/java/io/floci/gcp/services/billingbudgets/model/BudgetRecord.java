package io.floci.gcp.services.billingbudgets.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A stored budget: the {@code Budget} resource as returned on the wire, plus the order it was
 * created in, which {@code ListBudgets} follows.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class BudgetRecord {

    private long sequence;
    private Map<String, Object> budget = new LinkedHashMap<>();

    public BudgetRecord() {}

    public BudgetRecord(long sequence, Map<String, Object> budget) {
        this.sequence = sequence;
        this.budget = budget;
    }

    public long getSequence() { return sequence; }

    public void setSequence(long sequence) { this.sequence = sequence; }

    public Map<String, Object> getBudget() { return budget; }

    public void setBudget(Map<String, Object> budget) { this.budget = budget; }
}
