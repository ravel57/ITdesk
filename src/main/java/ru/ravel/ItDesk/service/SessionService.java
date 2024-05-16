package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class SessionService {

	private final JdbcIndexedSessionRepository sessionRepository;
	private final JdbcTemplate jdbcTemplate;


	public List<String> getAllActiveSessions() {
		String query = """
				SELECT principal_name
				FROM SPRING_SESSION
				WHERE EXPIRY_TIME > EXTRACT(EPOCH FROM NOW()) * 1000
					AND principal_name != ''
				""";
		return jdbcTemplate.queryForList(query, String.class);
	}
}


