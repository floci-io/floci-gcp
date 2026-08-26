package io.floci.gcp.services.iam;

import com.google.api.expr.v1alpha1.Decl;
import io.floci.gcp.core.common.GcpException;
import jakarta.enterprise.context.ApplicationScoped;
import org.projectnessie.cel.Ast;
import org.projectnessie.cel.Env;
import org.projectnessie.cel.EnvOption;
import org.projectnessie.cel.Program;
import org.projectnessie.cel.ProgramOption;
import org.projectnessie.cel.checker.Decls;
import org.projectnessie.cel.common.operators.Operator;
import org.projectnessie.cel.common.types.Overloads;
import org.projectnessie.cel.interpreter.functions.Overload;

import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Nessie CEL-Java implementation of Floci's Version 1 IAM Conditions profile.
 *
 * <p>The custom environment intentionally has no standard library. Only declarations and runtime
 * overloads listed here are available, so unsupported functions fail type checking without
 * Floci-side source or AST filtering.</p>
 */
@ApplicationScoped
public class NessieIamConditionEvaluator implements IamConditionEvaluator {

    private static final int DEFAULT_CACHE_CAPACITY = 256;
    private static final Set<String> RUNTIME_OPERATORS = Set.of(
            Operator.LogicalNot.id,
            Operator.Less.id,
            Overloads.TypeConvertTimestamp);

    private final Env environment;
    private final Clock clock;
    private final int cacheCapacity;
    private final Map<String, Program> programs;

    public NessieIamConditionEvaluator() {
        this(Clock.systemUTC(), DEFAULT_CACHE_CAPACITY);
    }

    NessieIamConditionEvaluator(Clock clock, int cacheCapacity) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (cacheCapacity <= 0) {
            throw new IllegalArgumentException("cacheCapacity must be positive");
        }
        this.cacheCapacity = cacheCapacity;
        this.environment = Env.newCustomEnv(EnvOption.declarations(profileDeclarations()));
        this.programs = new LinkedHashMap<>(cacheCapacity, 0.75f, true);
    }

    @Override
    public void validate(IamCondition condition) {
        programFor(condition);
    }

    @Override
    public boolean matches(IamCondition condition, IamResource resource) {
        Objects.requireNonNull(resource, "resource");
        try {
            Program.EvalResult result = programFor(condition).eval(Map.of(
                    "resource.service", resource.service(),
                    "resource.type", resource.type(),
                    "resource.name", resource.name(),
                    "request.time", clock.instant()));
            return result.getVal().value() instanceof Boolean value && value;
        } catch (RuntimeException e) {
            return false;
        }
    }

    int cachedProgramCount() {
        synchronized (programs) {
            return programs.size();
        }
    }

    private Program programFor(IamCondition condition) {
        Objects.requireNonNull(condition, "condition");
        String expression = condition.expression();
        synchronized (programs) {
            Program cached = programs.get(expression);
            if (cached != null) {
                return cached;
            }
            Program compiled = compile(expression);
            if (programs.size() == cacheCapacity) {
                programs.remove(programs.keySet().iterator().next());
            }
            programs.put(expression, compiled);
            return compiled;
        }
    }

    private Program compile(String expression) {
        try {
            Env.AstIssuesTuple result = environment.compile(expression);
            if (result.hasIssues() || result.getAst() == null) {
                throw GcpException.invalidArgument("Unsupported IAM condition expression");
            }
            Ast ast = result.getAst();
            return environment.program(ast, ProgramOption.functions(profileOverloads()));
        } catch (GcpException e) {
            throw e;
        } catch (RuntimeException e) {
            throw GcpException.invalidArgument("Unsupported IAM condition expression");
        }
    }

    private static List<Decl> profileDeclarations() {
        return List.of(
                Decls.newFunction(Operator.LogicalAnd.id,
                        Decls.newOverload(Overloads.LogicalAnd, List.of(Decls.Bool, Decls.Bool), Decls.Bool)),
                Decls.newFunction(Operator.LogicalOr.id,
                        Decls.newOverload(Overloads.LogicalOr, List.of(Decls.Bool, Decls.Bool), Decls.Bool)),
                Decls.newFunction(Operator.LogicalNot.id,
                        Decls.newOverload(Overloads.LogicalNot, List.of(Decls.Bool), Decls.Bool)),
                Decls.newFunction(Operator.Equals.id,
                        Decls.newOverload(Overloads.Equals, List.of(Decls.String, Decls.String), Decls.Bool)),
                Decls.newFunction(Operator.NotEquals.id,
                        Decls.newOverload(Overloads.NotEquals, List.of(Decls.String, Decls.String), Decls.Bool)),
                Decls.newFunction(Operator.Less.id,
                        Decls.newOverload(Overloads.LessTimestamp,
                                List.of(Decls.Timestamp, Decls.Timestamp), Decls.Bool)),
                Decls.newFunction(Overloads.StartsWith,
                        Decls.newInstanceOverload(Overloads.StartsWithString,
                                List.of(Decls.String, Decls.String), Decls.Bool)),
                Decls.newFunction(Overloads.EndsWith,
                        Decls.newInstanceOverload(Overloads.EndsWithString,
                                List.of(Decls.String, Decls.String), Decls.Bool)),
                Decls.newFunction(Overloads.TypeConvertTimestamp,
                        Decls.newOverload(Overloads.StringToTimestamp, List.of(Decls.String), Decls.Timestamp)),
                Decls.newVar("resource.service", Decls.String),
                Decls.newVar("resource.type", Decls.String),
                Decls.newVar("resource.name", Decls.String),
                Decls.newVar("request.time", Decls.Timestamp));
    }

    private static Overload[] profileOverloads() {
        return Arrays.stream(Overload.standardOverloads())
                .filter(overload -> RUNTIME_OPERATORS.contains(overload.operator))
                .toArray(Overload[]::new);
    }
}
