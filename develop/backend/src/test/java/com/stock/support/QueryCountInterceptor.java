package com.stock.support;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.stereotype.Component;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only MyBatis interceptor that counts how many times a chosen mapper namespace is queried.
 * mybatis-spring-boot-starter auto-registers every {@link Interceptor} bean it finds in the context
 * onto the SqlSessionFactory, so simply being a Spring bean is enough — no extra wiring needed.
 * Used to assert the strategy scan's price reads stay at a fixed query count regardless of how many
 * stocks are scanned (specs/backend/strategy-scan.md, "行情讀取必須批次進行").
 */
@Component
@Intercepts({
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class})
})
public class QueryCountInterceptor implements Interceptor {

    private final AtomicInteger count = new AtomicInteger();
    private volatile String watchedNamespacePrefix;

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        MappedStatement ms = (MappedStatement) invocation.getArgs()[0];
        String prefix = watchedNamespacePrefix;
        if (prefix != null && ms.getId().startsWith(prefix)) {
            count.incrementAndGet();
        }
        return invocation.proceed();
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {
        // no configurable properties
    }

    public void reset(String namespacePrefix) {
        this.watchedNamespacePrefix = namespacePrefix;
        count.set(0);
    }

    public int getCount() {
        return count.get();
    }
}
