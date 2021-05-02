package ru.ravel.ItDesk.Mappers;

import org.springframework.jdbc.core.RowMapper;
import ru.ravel.ItDesk.Models.ClientTask;

import java.sql.ResultSet;
import java.sql.SQLException;

public class ClientTaskMapper implements RowMapper<ClientTask> {

    public ClientTask mapRow(ResultSet rs, int rowNum) throws SQLException {
        return ClientTask.builder()
                .id(rs.getLong("id"))
                .firstName(rs.getString("firstname"))
                .lastName(rs.getString("lastname"))
                .organization(rs.getString("Organization"))
                .telegramId(rs.getString("telegram_id"))
                .lastMessageDateTime(rs.getTimestamp("last_message"))
                .build();
    }
}