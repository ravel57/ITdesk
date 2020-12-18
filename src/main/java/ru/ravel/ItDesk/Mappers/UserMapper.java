package ru.ravel.ItDesk.Mappers;

import org.springframework.jdbc.core.RowMapper;
import ru.ravel.ItDesk.Models.User;


import java.sql.ResultSet;
import java.sql.SQLException;

public class UserMapper implements RowMapper<User> {

    public User mapRow(ResultSet rs, int rowNum) throws SQLException {
        User user = new User();
        user.setId(rs.getLong("id"));
        user.setFirstName(rs.getString("first_name"));
        user.setLastName(rs.getString("last_name"));
        user.setTelegramId(rs.getString("telegram_id"));
        return user;
    }
}
