package io.floci.gcp.core.common;

import com.google.datastore.v1.CompositeFilter;
import com.google.datastore.v1.Filter;
import com.google.datastore.v1.GqlQuery;
import com.google.datastore.v1.GqlQueryParameter;
import com.google.datastore.v1.KindExpression;
import com.google.datastore.v1.PropertyFilter;
import com.google.datastore.v1.PropertyReference;
import com.google.datastore.v1.Query;
import com.google.datastore.v1.Value;
import com.google.protobuf.Int32Value;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal GQL-to-StructuredQuery converter for Datastore.
 * Handles the subset of GQL used by typical SDK and console queries.
 */
public final class GqlParser {

    private static final Pattern FROM_PATTERN =
            Pattern.compile("(?i)\\bFROM\\s+`?(\\w+)`?");
    private static final Pattern LIMIT_PATTERN =
            Pattern.compile("(?i)\\bLIMIT\\s+(\\d+)");
    private static final Pattern OFFSET_PATTERN =
            Pattern.compile("(?i)\\bOFFSET\\s+(\\d+)");
    private static final Pattern WHERE_BLOCK =
            Pattern.compile("(?i)\\bWHERE\\s+(.+?)(?:\\s+(?:ORDER\\s+BY|LIMIT|OFFSET)|$)");
    private static final Pattern CONDITION_PATTERN =
            Pattern.compile("(`?\\w+`?)\\s*(=|!=|<|<=|>|>=)\\s*(@\\w+|'[^']*'|\"[^\"]*\"|true|false|null|-?\\d+(?:\\.\\d+)?)");

    private GqlParser() {}

    public static Query toStructuredQuery(GqlQuery gql) {
        String qs = gql.getQueryString();
        Query.Builder query = Query.newBuilder();

        Matcher fromMatcher = FROM_PATTERN.matcher(qs);
        if (fromMatcher.find()) {
            query.addKind(KindExpression.newBuilder().setName(fromMatcher.group(1)).build());
        }

        Matcher limitMatcher = LIMIT_PATTERN.matcher(qs);
        if (limitMatcher.find()) {
            query.setLimit(Int32Value.of(Integer.parseInt(limitMatcher.group(1))));
        }

        Matcher offsetMatcher = OFFSET_PATTERN.matcher(qs);
        if (offsetMatcher.find()) {
            query.setOffset(Integer.parseInt(offsetMatcher.group(1)));
        }

        Matcher whereMatcher = WHERE_BLOCK.matcher(qs);
        if (whereMatcher.find()) {
            String whereClause = whereMatcher.group(1).trim();
            Filter filter = parseWhereClause(whereClause, gql.getNamedBindingsMap(),
                    gql.getPositionalBindingsList());
            if (filter != null) {
                query.setFilter(filter);
            }
        }

        return query.build();
    }

    private static Filter parseWhereClause(String clause,
            Map<String, GqlQueryParameter> namedBindings,
            List<GqlQueryParameter> positionalBindings) {
        String[] andParts = clause.split("(?i)\\bAND\\b");
        List<Filter> filters = new ArrayList<>();
        for (String part : andParts) {
            Filter f = parseSingleCondition(part.trim(), namedBindings, positionalBindings);
            if (f != null) {
                filters.add(f);
            }
        }
        if (filters.isEmpty()) {
            return null;
        }
        if (filters.size() == 1) {
            return filters.get(0);
        }
        return Filter.newBuilder()
                .setCompositeFilter(CompositeFilter.newBuilder()
                        .setOp(CompositeFilter.Operator.AND)
                        .addAllFilters(filters)
                        .build())
                .build();
    }

    private static Filter parseSingleCondition(String cond,
            Map<String, GqlQueryParameter> namedBindings,
            List<GqlQueryParameter> positionalBindings) {
        Matcher m = CONDITION_PATTERN.matcher(cond);
        if (!m.find()) {
            return null;
        }
        String propName = m.group(1).replace("`", "");
        String op = m.group(2);
        String rawValue = m.group(3);

        Value value = resolveValue(rawValue, namedBindings, positionalBindings);
        if (value == null) {
            return null;
        }

        PropertyFilter.Operator operator = switch (op) {
            case "=" -> PropertyFilter.Operator.EQUAL;
            case "!=" -> PropertyFilter.Operator.NOT_EQUAL;
            case "<" -> PropertyFilter.Operator.LESS_THAN;
            case "<=" -> PropertyFilter.Operator.LESS_THAN_OR_EQUAL;
            case ">" -> PropertyFilter.Operator.GREATER_THAN;
            case ">=" -> PropertyFilter.Operator.GREATER_THAN_OR_EQUAL;
            default -> PropertyFilter.Operator.EQUAL;
        };

        return Filter.newBuilder()
                .setPropertyFilter(PropertyFilter.newBuilder()
                        .setProperty(PropertyReference.newBuilder().setName(propName).build())
                        .setOp(operator)
                        .setValue(value)
                        .build())
                .build();
    }

    private static Value resolveValue(String raw,
            Map<String, GqlQueryParameter> namedBindings,
            List<GqlQueryParameter> positionalBindings) {
        if (raw.startsWith("@")) {
            String paramName = raw.substring(1);
            if (namedBindings.containsKey(paramName)) {
                return namedBindings.get(paramName).getValue();
            }
            try {
                int idx = Integer.parseInt(paramName) - 1;
                if (idx >= 0 && idx < positionalBindings.size()) {
                    return positionalBindings.get(idx).getValue();
                }
            } catch (NumberFormatException ignored) {}
            return null;
        }
        if (raw.startsWith("'") || raw.startsWith("\"")) {
            return Value.newBuilder()
                    .setStringValue(raw.substring(1, raw.length() - 1))
                    .build();
        }
        if ("true".equalsIgnoreCase(raw)) {
            return Value.newBuilder().setBooleanValue(true).build();
        }
        if ("false".equalsIgnoreCase(raw)) {
            return Value.newBuilder().setBooleanValue(false).build();
        }
        if ("null".equalsIgnoreCase(raw)) {
            return Value.newBuilder().setNullValue(com.google.protobuf.NullValue.NULL_VALUE).build();
        }
        try {
            if (raw.contains(".")) {
                return Value.newBuilder().setDoubleValue(Double.parseDouble(raw)).build();
            }
            return Value.newBuilder().setIntegerValue(Long.parseLong(raw)).build();
        } catch (NumberFormatException e) {
            return Value.newBuilder().setStringValue(raw).build();
        }
    }
}
