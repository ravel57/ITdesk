package ru.ravel.ItDesk.Mappers;

import org.springframework.jdbc.core.RowMapper;
import ru.ravel.ItDesk.Models.Message;

import java.sql.ResultSet;
import java.sql.SQLException;


public class MessageMapper implements RowMapper<Message>  {
    public Message mapRow(ResultSet rs, int rowNum) throws SQLException {
        return Message.builder()
                .id(rs.getLong("id"))
                .clientId(rs.getLong("client_id"))
                .text(rs.getString("text"))
                .date(rs.getTimestamp("date_time"))
                .messageType(rs.getString("message_type"))
                .supportId(rs.getLong("support_id"))
                .build();
    }
}
