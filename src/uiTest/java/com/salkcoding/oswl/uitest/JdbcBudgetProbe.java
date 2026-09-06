package com.salkcoding.oswl.uitest;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Counts physical JDBC rows on the measured service thread, excluding scheduler traffic. */
@TestConfiguration(proxyBeanMethods = false)
class JdbcBudgetProbe {
    static final ThreadLocal<Sample> CURRENT = new ThreadLocal<>();
    static final class Sample {
        long statements, rows, nanos;
        final String name;
        final boolean plans;
        final Set<String> explained = new HashSet<>();
        Sample(String name, boolean plans) { this.name = name; this.plans = plans; }
    }
    static Sample start(String name, boolean plans) {
        var sample = new Sample(name, plans);
        CURRENT.set(sample);
        return sample;
    }

    @Bean static BeanPostProcessor measuringDataSource() {
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                if (!(bean instanceof DataSource source)) return bean;
                return new DelegatingDataSource(source) {
                    @Override public Connection getConnection() throws SQLException { return connection(super.getConnection()); }
                    @Override public Connection getConnection(String user, String password) throws SQLException {
                        return connection(super.getConnection(user, password));
                    }
                };
            }
        };
    }

    private static Connection connection(Connection actual) {
        return proxy(Connection.class, actual, (method, args) -> {
            Object value = invoke(actual, method, args);
            if (value instanceof PreparedStatement statement && args != null && args[0] instanceof String sql)
                return statement(actual, statement, sql);
            return value;
        });
    }

    private static PreparedStatement statement(Connection connection, PreparedStatement actual, String sql) {
        Map<Integer, Map.Entry<Method, Object[]>> bindings = new LinkedHashMap<>();
        return proxy(PreparedStatement.class, actual, (method, args) -> {
            if (method.getName().startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Integer index)
                bindings.put(index, Map.entry(method, args.clone()));
            Sample sample = CURRENT.get();
            boolean execute = method.getName().startsWith("execute") && sample != null;
            if (execute && sample.plans && sql.stripLeading().toLowerCase(Locale.ROOT).startsWith("select")
                    && (sql.contains("libraries") || sql.contains("scan_components")) && sample.explained.add(sql)) {
                Path out = Path.of("build/reports/performance/plans", sample.name + "-" + sample.explained.size() + ".txt");
                Files.createDirectories(out.getParent());
                // This opt-in fixture is H2-only; do not label these as PostgreSQL plans.
                try (PreparedStatement explain = connection.prepareStatement("EXPLAIN ANALYZE " + sql)) {
                    for (var binding : bindings.values()) invoke(explain, binding.getKey(), binding.getValue());
                    try (ResultSet rows = explain.executeQuery()) {
                        StringBuilder plan = new StringBuilder("H2 EXPLAIN ANALYZE\n" + sql + "\n");
                        while (rows.next()) plan.append(rows.getString(1)).append('\n');
                        Files.writeString(out, plan);
                    }
                }
            }
            long start = System.nanoTime();
            Object result;
            try { result = invoke(actual, method, args); }
            finally { if (execute) { sample.statements++; sample.nanos += System.nanoTime() - start; } }
            if (result instanceof ResultSet rows && sample != null) return proxy(ResultSet.class, rows, (m, a) -> {
                long fetchStart = System.nanoTime();
                Object fetched = invoke(rows, m, a);
                if (m.getName().equals("next")) {
                    sample.nanos += System.nanoTime() - fetchStart;
                    if (Boolean.TRUE.equals(fetched)) sample.rows++;
                }
                return fetched;
            });
            return result;
        });
    }

    @FunctionalInterface private interface Invocation { Object call(Method method, Object[] args) throws Throwable; }
    private static <T> T proxy(Class<T> type, T target, Invocation call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, method, args) -> {
            if (method.getName().equals("equals")) return p == args[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(p);
            return call.call(method, args);
        }));
    }
    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }
}
