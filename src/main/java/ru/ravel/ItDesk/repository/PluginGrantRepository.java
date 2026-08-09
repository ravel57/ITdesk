package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.PluginGrant;

import java.util.List;
import java.util.Optional;


@Repository
public interface PluginGrantRepository extends JpaRepository<PluginGrant, Long> {

	List<PluginGrant> findAllByPluginKeyOrderByPermissionAsc(String pluginKey);

	List<PluginGrant> findAllByPluginKeyAndApprovedTrue(String pluginKey);

	Optional<PluginGrant> findByPluginKeyAndPermission(String pluginKey, String permission);

	void deleteAllByPluginKey(String pluginKey);
}
