package io.floci.gcp.services.bigquery.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * The {@code dataset} variant of an access entry, granting to another dataset
 * rather than to a principal.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DatasetAccessEntryTarget {

    private DatasetReference dataset;
    private List<String> targetTypes;

    public DatasetReference getDataset() { return dataset; }
    public void setDataset(DatasetReference dataset) { this.dataset = dataset; }

    public List<String> getTargetTypes() { return targetTypes; }
    public void setTargetTypes(List<String> targetTypes) { this.targetTypes = targetTypes; }
}
