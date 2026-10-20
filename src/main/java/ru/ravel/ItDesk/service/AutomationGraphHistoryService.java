package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AutomationGraphHistoryService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String executionId, Long triggerId, Object step) {
        try {
            jdbc.update("insert into automation_graph_trace(execution_id, trigger_id, step) values (?, ?, cast(? as jsonb))",
                    executionId, triggerId, mapper.writeValueAsString(step));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalArgumentException(e); }
    }

    @Transactional(readOnly = true)
    public List<Map<String,Object>> backups(Long triggerId) {
        return jdbc.queryForList("select migration_version, saved_at, definition::text as definition from automation_workflow_backup where trigger_id = ? order by migration_version desc", triggerId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> recent(Long triggerId) {
        return jdbc.queryForList("select execution_id, created_at, step::text as step from automation_graph_trace where trigger_id = ? order by id desc limit 500", triggerId);
    }
}
