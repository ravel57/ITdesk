package ru.ravel.ItDesk.Mappers;

import org.springframework.jdbc.core.RowMapper;
import ru.ravel.ItDesk.Models.Client;

import java.sql.ResultSet;
import java.sql.SQLException;

public class ClientMapper implements RowMapper<Client> {

    public Client mapRow(ResultSet rs, int rowNum) throws SQLException {
        return Client.builder()
                .id(rs.getLong("id"))
                .firstName(rs.getString("firstname"))
                .lastName(rs.getString("lastname"))
                .organization(rs.getString("Organization"))
                .telegramId(rs.getLong("telegram_id"))
                .whatsappId(rs.getLong("whatsapp_id"))
                .cabinetNumber(rs.getString("cabinet_number"))
                .phoneNumber(rs.getString("phone_number"))
                .email(rs.getString("email"))
                .build();
    }
}
