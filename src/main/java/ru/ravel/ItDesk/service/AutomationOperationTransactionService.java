package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.function.Supplier;

/** A node and its receipt commit together. A failed node cannot poison the error branch's transaction. */
@Service
@RequiredArgsConstructor
public class AutomationOperationTransactionService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public JsonNode read(Supplier<JsonNode> query) { return query.get(); }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public JsonNode once(String key, String operation, Supplier<JsonNode> work) {
        int claimed = jdbc.update("insert into automation_operation_receipt(execution_key, operation) values (?, ?) on conflict do nothing", key, operation);
        if (claimed == 0) {
            String saved = jdbc.queryForObject("select result::text from automation_operation_receipt where execution_key = ? for update", String.class, key);
            try { return mapper.readTree(saved == null ? "null" : saved); }
            catch (Exception e) { throw new IllegalStateException("Повреждён сохранённый результат шага", e); }
        }
        JsonNode result = work.get();
        jdbc.update("update automation_operation_receipt set result = cast(? as jsonb) where execution_key = ?", result.toString(), key);
        return result;
    }
}
