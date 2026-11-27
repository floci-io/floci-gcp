package io.floci.gcp.services.sts;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.api.expr.v1alpha1.Decl;
import jakarta.enterprise.context.ApplicationScoped;
import org.projectnessie.cel.Ast;
import org.projectnessie.cel.Env;
import org.projectnessie.cel.EnvOption;
import org.projectnessie.cel.Program;
import org.projectnessie.cel.ProgramOption;
import org.projectnessie.cel.checker.Decls;
import org.projectnessie.cel.common.types.Err;
import org.projectnessie.cel.common.types.StringT;
import org.projectnessie.cel.common.types.ref.Val;
import org.projectnessie.cel.interpreter.functions.Overload;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluates Workload Identity Pool Provider CEL {@code attributeMapping} and
 * {@code attributeCondition} expressions against an external token assertion.
 */
@ApplicationScoped
public class WorkloadIdentityCelEvaluator {

    private static final String EXTRACT_FUNCTION = "extract";
    private static final String EXTRACT_OVERLOAD = "string_extract_string";
    private static final int MAX_SUBJECT_BYTES = 127;

    private final Env mappingEnv;
    private final Env conditionEnv;

    public WorkloadIdentityCelEvaluator() {
        Decl extractDecl = Decls.newFunction(EXTRACT_FUNCTION,
                Decls.newInstanceOverload(EXTRACT_OVERLOAD,
                        List.of(Decls.String, Decls.String), Decls.String));
        this.mappingEnv = Env.newEnv(EnvOption.declarations(
                Decls.newVar("assertion", Decls.newMapType(Decls.String, Decls.Dyn)),
                extractDecl));
        this.conditionEnv = Env.newEnv(EnvOption.declarations(
                Decls.newVar("assertion", Decls.newMapType(Decls.String, Decls.Dyn)),
                Decls.newVar("attribute", Decls.newMapType(Decls.String, Decls.String)),
                Decls.newVar("google", Decls.newMapType(Decls.String, Decls.Dyn)),
                extractDecl));
    }

    public MappedAttributes evaluate(Map<String, Object> assertion, Map<String, String> attributeMapping,
            String attributeCondition) {
        Map<String, Object> safeAssertion = assertion == null ? Map.of() : assertion;
        Map<String, String> attributes = new LinkedHashMap<>();
        String subject = null;
        List<String> groups = new ArrayList<>();

        if (attributeMapping != null) {
            for (Map.Entry<String, String> entry : attributeMapping.entrySet()) {
                String target = entry.getKey();
                String expression = entry.getValue();
                Object value = evalExpression(mappingEnv, expression, Map.of("assertion", safeAssertion));
                if ("google.subject".equals(target)) {
                    subject = asNonEmptyString(value, "google.subject");
                } else if ("google.groups".equals(target)) {
                    groups.addAll(asStringList(value));
                } else if (target.startsWith("attribute.")) {
                    String attrName = target.substring("attribute.".length());
                    if (value != null) {
                        attributes.put(attrName, String.valueOf(value));
                    }
                }
            }
        }

        if (subject == null || subject.isBlank()) {
            throw new StsException("invalid_grant",
                    "Attribute mapping did not produce a non-empty google.subject.");
        }
        if (subject.getBytes(StandardCharsets.UTF_8).length > MAX_SUBJECT_BYTES) {
            throw new StsException("invalid_grant",
                    "Mapped google.subject exceeds maximum length of " + MAX_SUBJECT_BYTES + " bytes.");
        }

        if (attributeCondition != null && !attributeCondition.isBlank()) {
            Map<String, Object> google = new LinkedHashMap<>();
            google.put("subject", subject);
            google.put("groups", groups);
            Object conditionResult = evalExpression(conditionEnv, attributeCondition, Map.of(
                    "assertion", safeAssertion,
                    "attribute", attributes,
                    "google", google));
            if (!(conditionResult instanceof Boolean allowed) || !allowed) {
                throw new StsException("invalid_grant",
                        "The given credential is rejected by the attribute condition.");
            }
        }

        return new MappedAttributes(subject, List.copyOf(groups), Map.copyOf(attributes));
    }

    public static Map<String, String> readAttributeMapping(JsonNode provider) {
        Map<String, String> mapping = new LinkedHashMap<>();
        JsonNode node = provider.get("attributeMapping");
        if (node != null && node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (field.getValue().isTextual()) {
                    mapping.put(field.getKey(), field.getValue().asText());
                }
            }
        }
        return mapping;
    }

    private Object evalExpression(Env env, String expression, Map<String, Object> vars) {
        try {
            Env.AstIssuesTuple compiled = env.compile(expression);
            if (compiled.hasIssues() || compiled.getAst() == null) {
                throw new StsException("invalid_grant",
                        "Failed to compile CEL expression: " + expression);
            }
            Ast ast = compiled.getAst();
            Program program = env.program(ast, ProgramOption.functions(extractOverload()));
            Program.EvalResult result = program.eval(vars);
            Val val = result.getVal();
            if (val == null || Err.isError(val)) {
                throw new StsException("invalid_grant",
                        "Failed to evaluate CEL expression: " + expression);
            }
            return unwrap(val.value());
        } catch (StsException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new StsException("invalid_grant",
                    "Failed to evaluate CEL expression: " + e.getMessage());
        }
    }

    private static Object unwrap(Object value) {
        if (value instanceof Val celVal) {
            return unwrap(celVal.value());
        }
        if (value instanceof List<?> list) {
            List<Object> unwrapped = new ArrayList<>(list.size());
            for (Object item : list) {
                unwrapped.add(unwrap(item));
            }
            return unwrapped;
        }
        return value;
    }

    private static String asNonEmptyString(Object value, String name) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new StsException("invalid_grant",
                    "Attribute mapping for " + name + " must evaluate to a non-empty string.");
        }
        return text;
    }

    private static List<String> asStringList(Object value) {
        if (value instanceof String text && !text.isBlank()) {
            return List.of(text);
        }
        if (value instanceof List<?> list) {
            List<String> items = new ArrayList<>();
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    items.add(String.valueOf(item));
                }
            }
            return items;
        }
        return List.of();
    }

    private static Overload extractOverload() {
        return Overload.binary(EXTRACT_OVERLOAD, (lhs, rhs) -> {
            if (!(lhs instanceof StringT target) || !(rhs instanceof StringT template)) {
                return Err.newErr("extract requires string arguments");
            }
            return StringT.stringOf(extract(String.valueOf(target.value()), String.valueOf(template.value())));
        });
    }

    static String extract(String value, String template) {
        int open = template.indexOf('{');
        int close = open >= 0 ? template.indexOf('}', open + 1) : -1;
        if (open < 0 || close < 0) {
            return "";
        }
        String prefix = template.substring(0, open);
        String suffix = template.substring(close + 1);
        int start = prefix.isEmpty() ? 0 : value.indexOf(prefix);
        if (start < 0) {
            return "";
        }
        int captureStart = start + prefix.length();
        if (suffix.isEmpty()) {
            return value.substring(captureStart);
        }
        int captureEnd = value.indexOf(suffix, captureStart);
        if (captureEnd < 0) {
            return "";
        }
        return value.substring(captureStart, captureEnd);
    }

    public record MappedAttributes(String subject, List<String> groups, Map<String, String> attributes) {
    }
}
