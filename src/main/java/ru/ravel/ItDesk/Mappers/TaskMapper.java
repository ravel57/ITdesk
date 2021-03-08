package ru.ravel.ItDesk.Mappers;

import org.springframework.jdbc.core.RowMapper;
import ru.ravel.ItDesk.Models.Task;

import java.sql.ResultSet;
import java.sql.SQLException;

public class TaskMapper implements RowMapper<Task> {
    public Task mapRow(ResultSet rs, int rowNum) throws SQLException {
        return Task.builder()
                .id(rs.getLong("id"))
                .clientId(rs.getLong("client_id"))
                .name(rs.getString("text"))
                .build();
    }
}
