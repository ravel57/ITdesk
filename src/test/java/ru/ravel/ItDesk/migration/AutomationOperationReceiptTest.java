package ru.ravel.ItDesk.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import ru.ravel.ItDesk.service.AutomationOperationTransactionService;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AutomationOperationReceiptTest {
    @Test
    void concurrentReplaysExecuteOnceAndFailedAttemptsDoNotLeaveReceipts() throws Exception {
        String url=System.getenv("ITDESK_TEST_POSTGRES_URL");
        assumeTrue(url!=null&&!url.isBlank(),"Set ITDESK_TEST_POSTGRES_URL to test PostgreSQL receipts");
        String schema="automation_receipts_"+UUID.randomUUID().toString().replace("-","");
        try(var admin=DriverManager.getConnection(url);var sql=admin.createStatement()) {
            sql.execute("create schema "+schema);
            try {
                sql.execute("create table "+schema+".automation_operation_receipt(execution_key text primary key,operation text not null,result jsonb)");
                var source=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema);
                var mapper=new ObjectMapper();var target=new AutomationOperationTransactionService(new JdbcTemplate(source),mapper);
                var proxy=new ProxyFactory(target);proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));
                var service=(AutomationOperationTransactionService)proxy.getProxy();
                var calls=new AtomicInteger();var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(4);
                try {
                    List<Future<String>> results=new ArrayList<>();
                    for(int i=0;i<4;i++) results.add(pool.submit(()->{start.await();return service.once("event:node:item-7","task.create",()->{calls.incrementAndGet();return mapper.createObjectNode().put("id",7);}).toString();}));
                    start.countDown();for(var result:results)assertThat(result.get(20,TimeUnit.SECONDS)).isEqualTo("{\"id\":7}");
                    assertThat(calls.get()).isEqualTo(1);
                } finally {pool.shutdownNow();}
                assertThatThrownBy(()->service.once("failure","task.create",()->{throw new IllegalArgumentException("expected");})).hasMessageContaining("expected");
                assertThat(service.once("failure","task.create",()->mapper.createObjectNode().put("id",8)).path("id").asInt()).isEqualTo(8);
            } finally {sql.execute("drop schema "+schema+" cascade");}
        }
    }
}
