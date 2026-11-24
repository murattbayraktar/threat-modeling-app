package com.bank.tm.core.rules;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiPredicate;
import java.util.regex.Pattern;

/** Varsayılan operatörler ve genişletme noktası. */
public class OperatorRegistry {

    private final Map<String, Operator> operators = new TreeMap<>();

    public OperatorRegistry() {
        register(simple("eq", OperatorRegistry::equalsLoose));
        register(simple("ne", (a, e) -> !equalsLoose(a, e)));
        register(simple("in", (a, e) -> asCollection(e).stream().anyMatch(x -> equalsLoose(a, x))));
        register(simple("notIn", (a, e) -> asCollection(e).stream().noneMatch(x -> equalsLoose(a, x))));
        register(simple("contains", (a, e) -> a instanceof Collection<?> c && c.stream().anyMatch(x -> equalsLoose(x, e))));
        register(simple("containsAny", (a, e) -> a instanceof Collection<?> c
                && asCollection(e).stream().anyMatch(x -> c.stream().anyMatch(y -> equalsLoose(y, x)))));
        register(simple("containsAll", (a, e) -> a instanceof Collection<?> c
                && asCollection(e).stream().allMatch(x -> c.stream().anyMatch(y -> equalsLoose(y, x)))));
        register(simple("gt", (a, e) -> compare(a, e) > 0));
        register(simple("gte", (a, e) -> compare(a, e) >= 0));
        register(simple("lt", (a, e) -> compare(a, e) < 0 && a != null));
        register(simple("lte", (a, e) -> compare(a, e) <= 0 && a != null));
        register(simple("matches", (a, e) -> a != null && Pattern.compile(String.valueOf(e), Pattern.CASE_INSENSITIVE)
                .matcher(String.valueOf(a)).find()));
        register(unary("isNull", Objects::isNull));
        register(unary("notNull", Objects::nonNull));
        register(unary("isTrue", a -> Boolean.TRUE.equals(a)));
        register(unary("isFalse", a -> Boolean.FALSE.equals(a)));
        register(unary("isEmpty", a -> a == null || (a instanceof Collection<?> c && c.isEmpty())
                || (a instanceof String s && s.isBlank())));
        register(unary("notEmpty", a -> !(a == null || (a instanceof Collection<?> c && c.isEmpty())
                || (a instanceof String s && s.isBlank()))));
    }

    public final void register(Operator op) {
        operators.put(op.name(), op);
    }

    public Operator get(String name) {
        Operator op = operators.get(name);
        if (op == null) throw new IllegalArgumentException("Bilinmeyen operatör: " + name + " (tanımlı: " + operators.keySet() + ")");
        return op;
    }

    public Set<String> names() {
        return operators.keySet();
    }

    private static Operator simple(String name, BiPredicate<Object, Object> p) {
        return new Operator() {
            public String name() { return name; }
            public boolean test(Object actual, Object expected) { return p.test(actual, expected); }
        };
    }

    private static Operator unary(String name, java.util.function.Predicate<Object> p) {
        return new Operator() {
            public String name() { return name; }
            public boolean test(Object actual, Object expected) { return p.test(actual); }
            public boolean requiresValue() { return false; }
        };
    }

    static boolean equalsLoose(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (a instanceof Number x && b instanceof Number y) return x.doubleValue() == y.doubleValue();
        if (a instanceof Enum<?> e) a = e.name();
        if (b instanceof Enum<?> e) b = e.name();
        return a.toString().equals(b.toString());
    }

    private static int compare(Object a, Object e) {
        if (a == null || e == null) return Integer.MIN_VALUE;
        if (a instanceof Number x && e instanceof Number y) return Double.compare(x.doubleValue(), y.doubleValue());
        return a.toString().compareTo(e.toString());
    }

    private static Collection<?> asCollection(Object e) {
        if (e instanceof Collection<?> c) return c;
        return e == null ? List.of() : List.of(e);
    }
}
