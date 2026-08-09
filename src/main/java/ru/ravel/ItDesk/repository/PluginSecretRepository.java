package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.PluginSecretValue;

import java.util.List;
import java.util.Optional;


@Repository
public interface PluginSecretRepository extends JpaRepository<PluginSecretValue, Long> {

	Optional<PluginSecretValue> findByPluginKeyAndName(String pluginKey, String name);

	List<PluginSecretValue> findAllByPluginKey(String pluginKey);

	void deleteAllByPluginKey(String pluginKey);
}
