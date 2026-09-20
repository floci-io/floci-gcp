package io.floci.gcp.services.bigquery.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One entry of {@code Dataset.access[]}, a dataset-level access control.
 *
 * <p>Exactly one principal field is set alongside {@code role}: one of
 * {@code userByEmail}, {@code groupByEmail}, {@code domain},
 * {@code specialGroup}, {@code iamMember}, {@code view}, {@code dataset} or
 * {@code routine}, optionally with a {@code condition}. The API does not reject
 * entries that set more than one principal, so neither does this. The emulator
 * stores what it is given and returns it unchanged.
 *
 * <p><b>Scope.</b> This is storage fidelity, not authorization. Access entries
 * round-trip so that clients and the {@code hashicorp/google} Terraform
 * provider see a stable resource; nothing here evaluates them, not even a
 * {@code condition}, and an unauthenticated read of a dataset is still allowed.
 * That matches the rest of the BigQuery emulator today, and callers should not
 * read a successful query as evidence that a grant permitted it.
 *
 * @see <a href="https://cloud.google.com/bigquery/docs/reference/rest/v2/datasets">
 *      datasets resource</a>
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DatasetAccessEntry {

    private String role;
    private String userByEmail;
    private String groupByEmail;
    private String domain;
    private String specialGroup;
    private String iamMember;
    private TableReference view;
    private RoutineReference routine;
    private DatasetAccessEntryTarget dataset;
    private Expr condition;

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getUserByEmail() { return userByEmail; }
    public void setUserByEmail(String userByEmail) { this.userByEmail = userByEmail; }

    public String getGroupByEmail() { return groupByEmail; }
    public void setGroupByEmail(String groupByEmail) { this.groupByEmail = groupByEmail; }

    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }

    public String getSpecialGroup() { return specialGroup; }
    public void setSpecialGroup(String specialGroup) { this.specialGroup = specialGroup; }

    public String getIamMember() { return iamMember; }
    public void setIamMember(String iamMember) { this.iamMember = iamMember; }

    public TableReference getView() { return view; }
    public void setView(TableReference view) { this.view = view; }

    public RoutineReference getRoutine() { return routine; }
    public void setRoutine(RoutineReference routine) { this.routine = routine; }

    public DatasetAccessEntryTarget getDataset() { return dataset; }
    public void setDataset(DatasetAccessEntryTarget dataset) { this.dataset = dataset; }

    public Expr getCondition() { return condition; }
    public void setCondition(Expr condition) { this.condition = condition; }
}
