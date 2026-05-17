package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import ru.ravel.ItDesk.model.AppSettings;

public interface AppSettingsRepository extends JpaRepository<AppSettings, Long> {

	@Modifying
	@Query(value = """
			insert into app_settings (
			    id,
			    timezone,
			    working_time_enabled,
			    workday_start,
			    workday_end,
			    monday_enabled,
			    tuesday_enabled,
			    wednesday_enabled,
			    thursday_enabled,
			    friday_enabled,
			    saturday_enabled,
			    sunday_enabled
			)
			values (
			    1,
			    'Europe/Moscow',
			    true,
			    '09:00',
			    '18:00',
			    true,
			    true,
			    true,
			    true,
			    true,
			    false,
			    false
			)
			on conflict (id) do nothing
			""", nativeQuery = true)
	void insertDefaultIfNotExists();
}