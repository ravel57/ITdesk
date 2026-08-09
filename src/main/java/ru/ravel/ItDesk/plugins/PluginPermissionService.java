package ru.ravel.ItDesk.plugins;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.model.PluginGrant;
import ru.ravel.ItDesk.repository.PluginGrantRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;


/**
 * Права плагина: что он запросил в манифесте и что ему одобрил администратор.
 * <p>
 * Установка плагина не даёт ему ничего. Манифест — это заявка: строки прав
 * появляются со статусом «запрошено», и до явного одобрения брокер отклоняет
 * любые обращения наружу. При обновлении плагина ранее одобренные права
 * сохраняются только если они не изменились: новая или изменённая строка
 * снова требует решения администратора.
 */
@Service
public class PluginPermissionService {

	private final Logger logger = LoggerFactory.getLogger(getClass());

	private final PluginGrantRepository repository;
	private final PluginEgressPolicy egressPolicy;


	public PluginPermissionService(
			PluginGrantRepository repository,
			PluginEgressPolicy egressPolicy
	) {
		this.repository = repository;
		this.egressPolicy = egressPolicy;
	}


	/**
	 * Синхронизирует заявку плагина с базой при загрузке или обновлении.
	 * Некорректная строка права роняет загрузку плагина: опечатка должна быть
	 * видна администратору сразу, а не превращаться в молчаливый отказ брокера.
	 */
	@Transactional
	public void syncRequested(String pluginKey, Collection<String> requested) {
		Set<String> canonical = new LinkedHashSet<>();
		if (requested != null) {
			for (String raw : requested) {
				if (raw == null || raw.isBlank()) {
					continue;
				}
				canonical.add(PluginPermission.parse(raw).canonical());
			}
		}

		List<PluginGrant> existing = repository.findAllByPluginKeyOrderByPermissionAsc(pluginKey);
		Set<String> known = new LinkedHashSet<>();

		for (PluginGrant grant : existing) {
			if (canonical.contains(grant.getPermission())) {
				known.add(grant.getPermission());
				continue;
			}
			logger.info("Plugin {}: permission {} is no longer requested, removing", pluginKey, grant.getPermission());
			repository.delete(grant);
		}

		for (String permission : canonical) {
			if (known.contains(permission)) {
				continue;
			}
			repository.save(PluginGrant.builder()
					.pluginKey(pluginKey)
					.permission(permission)
					.approved(false)
					.requestedAt(Instant.now())
					.build());
			logger.info("Plugin {}: permission {} requires administrator approval", pluginKey, permission);
		}
	}


	@Transactional(readOnly = true)
	public List<PluginGrant> findAll(String pluginKey) {
		return repository.findAllByPluginKeyOrderByPermissionAsc(pluginKey);
	}


	@Transactional(readOnly = true)
	public Set<String> approvedPermissions(String pluginKey) {
		Set<String> result = new LinkedHashSet<>();
		for (PluginGrant grant : repository.findAllByPluginKeyAndApprovedTrue(pluginKey)) {
			result.add(grant.getPermission());
		}
		return Set.copyOf(result);
	}


	@Transactional(readOnly = true)
	public boolean hasPending(String pluginKey) {
		return repository.findAllByPluginKeyOrderByPermissionAsc(pluginKey)
				.stream()
				.anyMatch(grant -> !grant.isApproved());
	}


	/**
	 * Одобрить можно только то, что плагин действительно запросил: иначе
	 * администратор мог бы выдать доступ, которого нет в манифесте, и апгрейд
	 * плагина молча получил бы лишние возможности.
	 */
	@Transactional
	public void approve(String pluginKey, Collection<String> permissions, String actor) {
		for (String raw : requireNotEmpty(permissions)) {
			PluginPermission parsed = PluginPermission.parse(raw);
			PluginGrant grant = repository
					.findByPluginKeyAndPermission(pluginKey, parsed.canonical())
					.orElseThrow(() -> new IllegalArgumentException(
							"Плагин не запрашивал право: " + parsed.canonical()
					));

			if (parsed.kind() == PluginPermission.Kind.HTTP) {
				egressPolicy.validatePrefix(parsed.prefix());
			}

			grant.setApproved(true);
			grant.setApprovedAt(Instant.now());
			grant.setApprovedBy(actor);
			repository.save(grant);
			logger.info("Plugin {}: permission {} approved by {}", pluginKey, parsed.canonical(), actor);
		}
	}


	@Transactional
	public void revoke(String pluginKey, Collection<String> permissions, String actor) {
		for (String raw : requireNotEmpty(permissions)) {
			String canonical = PluginPermission.parse(raw).canonical();
			Optional<PluginGrant> found = repository.findByPluginKeyAndPermission(pluginKey, canonical);
			if (found.isEmpty()) {
				continue;
			}
			PluginGrant grant = found.get();
			grant.setApproved(false);
			grant.setApprovedAt(null);
			grant.setApprovedBy(null);
			repository.save(grant);
			logger.info("Plugin {}: permission {} revoked by {}", pluginKey, canonical, actor);
		}
	}


	@Transactional
	public void removePlugin(String pluginKey) {
		repository.deleteAllByPluginKey(pluginKey);
	}


	/** Представление для интерфейса администратора. */
	@Transactional(readOnly = true)
	public List<Map<String, Object>> describe(String pluginKey) {
		return findAll(pluginKey).stream()
				.map(grant -> Map.<String, Object>of(
						"permission", grant.getPermission(),
						"approved", grant.isApproved(),
						"requestedAt", String.valueOf(grant.getRequestedAt()),
						"approvedAt", String.valueOf(grant.getApprovedAt()),
						"approvedBy", String.valueOf(grant.getApprovedBy()),
						"description", describePermission(grant.getPermission())
				))
				.toList();
	}


	private String describePermission(String permission) {
		PluginPermission parsed = PluginPermission.tryParse(permission).orElse(null);
		if (parsed == null) {
			return permission;
		}
		if (parsed.kind() == PluginPermission.Kind.SECRET) {
			return "Использовать секрет «" + parsed.secretName() + "» (значение задаёт администратор)";
		}
		if (parsed.kind() == PluginPermission.Kind.URL_SCHEME) {
			return "Открывать внешнюю URI-схему «" + parsed.urlScheme() + "» из интерфейса";
		}
		if (parsed.kind() == PluginPermission.Kind.RESOURCE_READ) {
			return "Читать данные текущего ресурса «" + parsed.resourceName() + "» в контексте плагина";
		}
		return "Запросы " + parsed.method().toUpperCase() + " на " + parsed.prefix();
	}


	private Collection<String> requireNotEmpty(Collection<String> permissions) {
		if (permissions == null || permissions.isEmpty()) {
			throw new IllegalArgumentException("Список прав пуст");
		}
		return permissions;
	}
}
