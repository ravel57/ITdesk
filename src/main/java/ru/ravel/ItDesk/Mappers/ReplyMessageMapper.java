package ru.ravel.ItDesk.Mappers;

import org.springframework.jdbc.core.RowMapper;
import ru.ravel.ItDesk.Models.ReplayMessage;

import java.sql.ResultSet;
import java.sql.SQLException;

public class ReplyMessageMapper implements RowMapper<ReplayMessage> {
    public ReplayMessage mapRow(ResultSet rs, int rowNum) throws SQLException {
        return ReplayMessage.builder()
                .id(rs.getLong("id"))
                .clientId(rs.getLong("client_id"))
                .supportId(rs.getLong("support_id"))
                .text(rs.getString("text"))
                .date(rs.getDate("date_time"))
                .build();
    }
}

