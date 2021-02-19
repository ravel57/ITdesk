package ru.ravel.ItDesk.Mappers;

import org.springframework.jdbc.core.RowMapper;
import ru.ravel.ItDesk.Models.Client;


import java.sql.ResultSet;
import java.sql.SQLException;

public class ClientMapper implements RowMapper<Client> {

    public Client mapRow(ResultSet rs, int rowNum) throws SQLException {
        Client client = new Client();
        client.setId(rs.getLong("id"));
        client.setFirstName(rs.getString("first_name"));
        client.setLastName(rs.getString("last_name"));
        client.setOrganizationId(rs.getLong("Organization_id"));
        client.setTelegramId(rs.getLong("telegram_id"));
        client.setWhatsappId(rs.getLong("whatsapp_id"));
        client.setCabinetNumber(rs.getString("cabinet_number"));
        client.setPhoneNumber(rs.getString("phone_number"));
        client.setEmail(rs.getString("email"));
        return client;
    }
}
