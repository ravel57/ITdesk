package ru.ravel.ItDesk.Mappers;

import org.springframework.jdbc.core.RowMapper;
import ru.ravel.ItDesk.Models.Supporter;

import java.sql.ResultSet;
import java.sql.SQLException;

public class SupporterMapper implements RowMapper<Supporter> {
    public Supporter mapRow(ResultSet rs, int rowNum) throws SQLException {
        return Supporter.builder()
                .id(rs.getLong("id"))
                .name(rs.getString("name"))
//                .telegramId(rs.getLong("telegram_id"))
                .login(rs.getString("login"))
                .build();
    }
}