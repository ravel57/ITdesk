package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.dto.OrganizationServiceAssignmentRequest;
import ru.ravel.ItDesk.dto.ServiceSaveRequest;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.repository.*;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@org.springframework.stereotype.Service
@RequiredArgsConstructor
public class ServiceCatalogService {

	private final ServiceRepository serviceRepository;
	private final ServiceCriticalityRepository criticalityRepository;
	private final OrganizationServiceAssignmentRepository assignmentRepository;
	private final OrganizationRepository organizationRepository;
	private final UserRepository userRepository;
	private final SupportLineRepository supportLineRepository;
	private final PriorityRepository priorityRepository;
	private final TaskTypeRepository taskTypeRepository;
	private final ZabbixIntegrationRepository zabbixIntegrationRepository;
	private final TaskRepository taskRepository;
	private final IncidentRepository incidentRepository;
	private final GlobalSearchService globalSearchService;

	@Transactional(readOnly = true)
	public List<Map<String, Object>> getServices(boolean includeInactive) {
		return serviceRepository.findAll().stream()
				.filter(service -> includeInactive || !Boolean.FALSE.equals(service.getActive()))
				.sorted()
				.map(this::toDto)
				.toList();
	}

	@Transactional(readOnly = true)
	public Map<String, Object> getService(Long id) {
		return toDto(requireService(id));
	}

	@Transactional
	public Map<String, Object> create(ServiceSaveRequest request) {
		validateRequest(request, null);
		ru.ravel.ItDesk.model.Service service = new ru.ravel.ItDesk.model.Service();
		service.setOrderNumber(request.getOrderNumber() == null ? (int) serviceRepository.count() : request.getOrderNumber());
		apply(service, request);
		ru.ravel.ItDesk.model.Service saved = serviceRepository.save(service);
		globalSearchService.indexService(saved);
		return toDto(saved);
	}

	@Transactional
	public Map<String, Object> update(Long id, ServiceSaveRequest request) {
		validateRequest(request, id);
		ru.ravel.ItDesk.model.Service service = requireService(id);
		apply(service, request);
		ru.ravel.ItDesk.model.Service saved = serviceRepository.save(service);
		globalSearchService.indexService(saved);
		return toDto(saved);
	}

	@Transactional
	public void delete(Long id) {
		ru.ravel.ItDesk.model.Service service = requireService(id);
		boolean referenced = taskRepository.existsByServiceId(id)
				|| assignmentRepository.existsByServiceId(id)
				|| incidentRepository.existsByServicesId(id)
				|| serviceRepository.existsByDependentServicesId(id);
		if (referenced) {
			service.setActive(false);
			serviceRepository.save(service);
			globalSearchService.indexService(service);
			return;
		}
		serviceRepository.delete(service);
		globalSearchService.deleteService(id);
	}

	@Transactional
	public List<Map<String, Object>> resort(List<Long> orderedIds) {
		if (orderedIds == null) return getServices(true);
		for (int i = 0; i < orderedIds.size(); i++) {
			Long id = orderedIds.get(i);
			if (id == null) continue;
			int orderNumber = i;
			serviceRepository.findById(id).ifPresent(service -> {
				service.setOrderNumber(orderNumber);
				serviceRepository.save(service);
			});
		}
		return getServices(true);
	}

	@Transactional(readOnly = true)
	public List<ServiceCriticality> getCriticalities() {
		return criticalityRepository.findAll().stream().sorted().toList();
	}

	@Transactional
	public ServiceCriticality saveCriticality(ServiceCriticality request) {
		if (request == null || request.getName() == null || request.getName().isBlank()) {
			throw new IllegalArgumentException("Название критичности обязательно");
		}
		Long id = request.getId();
		if (criticalityRepository.existsByNameIgnoreCaseAndIdNot(request.getName().trim(), id == null ? -1L : id)) {
			throw new IllegalArgumentException("Критичность с таким названием уже существует");
		}
		ServiceCriticality entity = id == null ? new ServiceCriticality() : criticalityRepository.findById(id).orElseThrow();
		entity.setName(request.getName().trim());
		entity.setOrderNumber(request.getOrderNumber() == null ? (int) criticalityRepository.count() : request.getOrderNumber());
		entity.setDefaultSelection(Boolean.TRUE.equals(request.getDefaultSelection()));
		if (Boolean.TRUE.equals(entity.getDefaultSelection())) {
			criticalityRepository.findAll().stream()
					.filter(item -> !Objects.equals(item.getId(), entity.getId()))
					.forEach(item -> item.setDefaultSelection(false));
		}
		return criticalityRepository.save(entity);
	}

	@Transactional
	public void deleteCriticality(Long id) {
		ServiceCriticality criticality = criticalityRepository.findById(id).orElseThrow();
		serviceRepository.findAll().stream()
				.filter(service -> service.getCriticality() != null && Objects.equals(service.getCriticality().getId(), id))
				.forEach(service -> service.setCriticality(null));
		criticalityRepository.delete(criticality);
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> getAssignments(Long organizationId, Long serviceId) {
		List<OrganizationServiceAssignment> assignments;
		if (organizationId != null) {
			assignments = assignmentRepository.findAllByOrganizationIdOrderByDefaultForOrganizationDescIdAsc(organizationId);
		} else if (serviceId != null) {
			assignments = assignmentRepository.findAllByServiceIdOrderByOrganizationNameAsc(serviceId);
		} else {
			assignments = assignmentRepository.findAll();
		}
		return assignments.stream().map(this::toAssignmentDto).toList();
	}

	@Transactional
	public Map<String, Object> createAssignment(OrganizationServiceAssignmentRequest request) {
		validateAssignmentRequest(request);
		OrganizationServiceAssignment assignment = new OrganizationServiceAssignment();
		applyAssignment(assignment, request);
		return toAssignmentDto(assignmentRepository.save(assignment));
	}

	@Transactional
	public Map<String, Object> updateAssignment(Long id, OrganizationServiceAssignmentRequest request) {
		validateAssignmentRequest(request);
		OrganizationServiceAssignment assignment = assignmentRepository.findById(id).orElseThrow();
		applyAssignment(assignment, request);
		return toAssignmentDto(assignmentRepository.save(assignment));
	}

	@Transactional
	public void deleteAssignment(Long id) {
		assignmentRepository.deleteById(id);
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> getAvailableServices(Long organizationId) {
		Organization organization = organizationId == null ? null : organizationRepository.findById(organizationId).orElseThrow();
		List<OrganizationServiceAssignment> configured = organization == null
				? List.of()
				: assignmentRepository.findAllByOrganizationIdOrderByDefaultForOrganizationDescIdAsc(organizationId);
		List<OrganizationServiceAssignment> effective = configured.stream()
				.filter(item -> !Boolean.FALSE.equals(item.getActive()))
				.filter(item -> item.isEffectiveOn(LocalDate.now()))
				.filter(item -> item.getService() != null && !Boolean.FALSE.equals(item.getService().getActive()))
				.toList();
		// Legacy installations may not have contract assignments yet. In that case
		// all active services remain available. Once at least one assignment is
		// configured, expired/disabled contracts must not silently grant access to
		// the entire catalog.
		if (configured.isEmpty()) {
			return serviceRepository.findAllByActiveTrueOrderByOrderNumberAscNameAsc().stream().map(this::toCompactDto).toList();
		}
		return effective.stream()
				.map(OrganizationServiceAssignment::getService)
				.filter(Objects::nonNull)
				.filter(service -> !Boolean.FALSE.equals(service.getActive()))
				.distinct()
				.sorted()
				.map(this::toCompactDto)
				.toList();
	}

	@Transactional(readOnly = true)
	public ru.ravel.ItDesk.model.Service resolveService(ru.ravel.ItDesk.model.Service requestService, Organization organization) {
		if (requestService == null || requestService.getId() == null) {
			if (organization == null || organization.getId() == null) return null;
			List<OrganizationServiceAssignment> configured = configuredAssignments(organization.getId());
			List<OrganizationServiceAssignment> effective = configured.stream()
					.filter(item -> !Boolean.FALSE.equals(item.getActive()))
					.filter(item -> item.isEffectiveOn(LocalDate.now()))
					.filter(item -> item.getService() != null && !Boolean.FALSE.equals(item.getService().getActive()))
					.toList();
			Optional<OrganizationServiceAssignment> defaultAssignment = effective.stream()
					.filter(item -> Boolean.TRUE.equals(item.getDefaultForOrganization()))
					.max(Comparator.comparing(OrganizationServiceAssignment::getId, Comparator.nullsFirst(Long::compareTo)));
			if (defaultAssignment.isPresent()) return defaultAssignment.get().getService();
			return effective.size() == 1 ? effective.getFirst().getService() : null;
		}
		ru.ravel.ItDesk.model.Service service = requireService(requestService.getId());
		if (Boolean.FALSE.equals(service.getActive())) {
			throw new IllegalArgumentException("Сервис неактивен: " + service.getName());
		}
		if (organization != null && organization.getId() != null) {
			List<OrganizationServiceAssignment> configured = configuredAssignments(organization.getId());
			List<OrganizationServiceAssignment> effective = effectiveAssignments(organization.getId());
			if (!configured.isEmpty() && effective.stream().noneMatch(item -> Objects.equals(item.getService().getId(), service.getId()))) {
				throw new IllegalArgumentException("Сервис недоступен для организации " + organization.getName());
			}
		}
		return service;
	}

	@Transactional(readOnly = true)
	public void applyTaskDefaults(Task task, Organization organization) {
		if (task == null || task.getService() == null) return;
		ru.ravel.ItDesk.model.Service service = task.getService();
		if (task.getType() != null && service.getAllowedTaskTypes() != null && !service.getAllowedTaskTypes().isEmpty()
				&& service.getAllowedTaskTypes().stream().noneMatch(type -> Objects.equals(type.getId(), task.getType().getId()))) {
			throw new IllegalArgumentException("Тип заявки недоступен для сервиса " + service.getName());
		}
		if (task.getExecutor() != null && service.getExecutors() != null && !service.getExecutors().isEmpty()
				&& service.getExecutors().stream().noneMatch(user -> Objects.equals(user.getId(), task.getExecutor().getId()))) {
			throw new IllegalArgumentException("Исполнитель не входит в команду сервиса " + service.getName());
		}
		if (task.getSupportLine() == null && Boolean.TRUE.equals(service.getAutoAssignDefaultLine())) {
			task.setSupportLine(service.getDefaultSupportLine());
		}
		if (task.getPriority() == null) {
			task.setPriority(resolvePriority(service, task));
		}
	}

	@Transactional(readOnly = true)
	public Priority resolvePriority(ru.ravel.ItDesk.model.Service service, Task task) {
		if (service == null) return null;
		String text = (Objects.toString(task == null ? null : task.getName(), "") + " "
				+ Objects.toString(task == null ? null : task.getDescription(), "")).toLowerCase(Locale.ROOT);
		Long taskTypeId = task == null || task.getType() == null ? null : task.getType().getId();
		for (ServicePriorityRule rule : safeList(service.getPriorityRules()).stream()
				.filter(rule -> rule != null && !Boolean.FALSE.equals(rule.getActive()))
				.sorted(Comparator.comparing(rule -> Objects.requireNonNullElse(rule.getOrderNumber(), Integer.MAX_VALUE)))
				.toList()) {
			if (rule.getTaskTypeId() != null && !Objects.equals(rule.getTaskTypeId(), taskTypeId)) continue;
			String keyword = Objects.toString(rule.getKeyword(), "").trim();
			if (!keyword.isBlank() && !matchesKeyword(text, keyword)) continue;
			if (rule.getPriorityId() != null) {
				Priority priority = priorityRepository.findById(rule.getPriorityId()).orElse(null);
				if (priority != null) return priority;
			}
		}
		return service.getDefaultPriority();
	}

	@Transactional(readOnly = true)
	public SlaPolicy resolveSlaPolicy(Organization organization, ru.ravel.ItDesk.model.Service service) {
		OrganizationServiceAssignment assignment = null;
		if (organization != null && organization.getId() != null && service != null && service.getId() != null) {
			assignment = effectiveAssignments(organization.getId()).stream()
					.filter(item -> item.getService() != null && Objects.equals(item.getService().getId(), service.getId()))
					.max(Comparator.comparing(OrganizationServiceAssignment::getId, Comparator.nullsFirst(Long::compareTo)))
					.orElse(null);
		}
		Integer firstResponse = assignment != null && assignment.getFirstResponseSlaMinutes() != null
				? assignment.getFirstResponseSlaMinutes()
				: service == null ? null : service.getFirstResponseSlaMinutes();
		Integer resolution = assignment != null && assignment.getResolutionSlaMinutes() != null
				? assignment.getResolutionSlaMinutes()
				: service == null ? null : service.getResolutionSlaMinutes();
		String calendar = assignment != null && assignment.getSupportCalendar() != null
				? assignment.getSupportCalendar()
				: service == null ? "GENERAL_SETTINGS" : Objects.requireNonNullElse(service.getSupportCalendar(), "GENERAL_SETTINGS");
		return new SlaPolicy(firstResponse, resolution, calendar);
	}

	@Transactional(readOnly = true)
	public ServiceOlaStage findOlaStage(ru.ravel.ItDesk.model.Service service, SupportLine line) {
		if (service == null || line == null || line.getId() == null) return null;
		return safeList(service.getOlaStages()).stream()
				.filter(stage -> stage != null && !Boolean.FALSE.equals(stage.getActive()))
				.filter(stage -> Objects.equals(stage.getSupportLineId(), line.getId()))
				.min(Comparator.comparing(stage -> Objects.requireNonNullElse(stage.getOrderNumber(), Integer.MAX_VALUE)))
				.orElse(null);
	}

	@Transactional(readOnly = true)
	public ru.ravel.ItDesk.model.Service requireService(Long id) {
		if (id == null) throw new IllegalArgumentException("service.id is required");
		return serviceRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Сервис не найден: " + id));
	}

	private void apply(ru.ravel.ItDesk.model.Service service, ServiceSaveRequest request) {
		service.setCode(normalizeCode(request.getCode()));
		service.setName(request.getName().trim());
		service.setDescription(Objects.toString(request.getDescription(), "").trim());
		if (request.getMonitoringUrl() != null) {
			service.setMonitoringUrl(blankToNull(request.getMonitoringUrl()));
		}
		service.setActive(!Boolean.FALSE.equals(request.getActive()));
		if (request.getOrderNumber() != null) service.setOrderNumber(request.getOrderNumber());
		service.setCriticality(resolveOrDefaultCriticality(request.getCriticalityId()));
		service.setOwner(findNullable(userRepository, request.getOwnerId()));
		service.setDefaultSupportLine(findNullable(supportLineRepository, request.getDefaultSupportLineId()));
		service.setDefaultPriority(findNullable(priorityRepository, request.getDefaultPriorityId()));
		service.setAllowedTaskTypes(resolveSet(taskTypeRepository, request.getTaskTypeIds()));
		service.setExecutors(resolveSet(userRepository, request.getExecutorIds()));
		Set<ru.ravel.ItDesk.model.Service> dependencies = resolveSet(serviceRepository, request.getDependencyIds());
		if (service.getId() != null && dependencies.stream().anyMatch(item -> Objects.equals(item.getId(), service.getId()))) {
			throw new IllegalArgumentException("Сервис не может зависеть от самого себя");
		}
		if (service.getId() != null && dependencies.stream().anyMatch(item -> dependsOn(item, service.getId(), new HashSet<>()))) {
			throw new IllegalArgumentException("Зависимости сервисов образуют цикл");
		}
		service.setDependentServices(dependencies);
		service.setFirstResponseSlaMinutes(nonNegative(request.getFirstResponseSlaMinutes()));
		service.setResolutionSlaMinutes(nonNegative(request.getResolutionSlaMinutes()));
		service.setSupportCalendar(defaultString(request.getSupportCalendar(), "GENERAL_SETTINGS"));
		service.setPauseSlaOnWaitingClient(!Boolean.FALSE.equals(request.getPauseSlaOnWaitingClient()));
		service.setAutoAssignDefaultLine(!Boolean.FALSE.equals(request.getAutoAssignDefaultLine()));
		service.setPriorityRules(normalizePriorityRules(request.getPriorityRules()));
		service.setOlaStages(normalizeOlaStages(request.getOlaStages()));
		service.setZabbixIntegration(findNullable(zabbixIntegrationRepository, request.getZabbixIntegrationId()));
		service.setZabbixEnabled(Boolean.TRUE.equals(request.getZabbixEnabled()));
		service.setMonitoringIdentifiers(request.getMonitoringIdentifiers() == null
				? new LinkedHashMap<>()
				: request.getMonitoringIdentifiers().entrySet().stream()
				.filter(entry -> entry.getKey() != null && !entry.getKey().isBlank())
				.collect(Collectors.toMap(
						entry -> entry.getKey().trim(),
						entry -> Objects.toString(entry.getValue(), "").trim(),
						(left, right) -> right,
						LinkedHashMap::new
				)));
		if (!Boolean.TRUE.equals(service.getZabbixEnabled()) || service.getZabbixIntegration() == null) {
			service.setMonitoringStatus(ServiceMonitoringStatus.NOT_CONFIGURED);
			service.setLastMonitoringMessage("Мониторинг Zabbix не настроен");
		}
	}

	private void applyAssignment(OrganizationServiceAssignment assignment, OrganizationServiceAssignmentRequest request) {
		Organization organization = organizationRepository.findById(request.getOrganizationId()).orElseThrow();
		ru.ravel.ItDesk.model.Service service = requireService(request.getServiceId());
		boolean active = !Boolean.FALSE.equals(request.getActive());
		boolean defaultForOrganization = Boolean.TRUE.equals(request.getDefaultForOrganization());
		if (defaultForOrganization && active) {
			// Clear the previous default before changing the current entity. This is
			// important when an inactive historical assignment is reactivated: an
			// automatic JPA flush must not temporarily create two active defaults.
			if (assignment.getId() == null) {
				assignmentRepository.clearActiveDefaultForOrganization(organization.getId());
			} else {
				assignmentRepository.clearOtherActiveDefaultsForOrganization(organization.getId(), assignment.getId());
			}
		}
		assignment.setOrganization(organization);
		assignment.setService(service);
		assignment.setContractNumber(blankToNull(request.getContractNumber()));
		assignment.setTariffName(blankToNull(request.getTariffName()));
		assignment.setValidFrom(request.getValidFrom());
		assignment.setValidTo(request.getValidTo());
		assignment.setSupportCalendar(defaultString(request.getSupportCalendar(), "GENERAL_SETTINGS"));
		assignment.setSlaPolicyName(blankToNull(request.getSlaPolicyName()));
		assignment.setFirstResponseSlaMinutes(nonNegative(request.getFirstResponseSlaMinutes()));
		assignment.setResolutionSlaMinutes(nonNegative(request.getResolutionSlaMinutes()));
		assignment.setAvailableChannels(request.getAvailableChannels() == null ? new ArrayList<>() : request.getAvailableChannels().stream()
				.map(value -> Objects.toString(value, "").trim().toUpperCase(Locale.ROOT))
				.filter(value -> !value.isBlank()).distinct().toList());
		assignment.setActive(active);
		assignment.setDefaultForOrganization(defaultForOrganization);
		assignment.setNotes(blankToNull(request.getNotes()));
	}

	private void validateRequest(ServiceSaveRequest request, Long id) {
		if (request == null) throw new IllegalArgumentException("Данные сервиса не переданы");
		if (request.getCode() == null || request.getCode().isBlank()) throw new IllegalArgumentException("Код сервиса обязателен");
		if (request.getName() == null || request.getName().isBlank()) throw new IllegalArgumentException("Название сервиса обязательно");
		Long effectiveId = id == null ? -1L : id;
		String code = normalizeCode(request.getCode());
		if (serviceRepository.existsByCodeIgnoreCaseAndIdNot(code, effectiveId)) throw new IllegalArgumentException("Сервис с таким кодом уже существует");
		if (serviceRepository.existsByNameIgnoreCaseAndIdNot(request.getName().trim(), effectiveId)) throw new IllegalArgumentException("Сервис с таким названием уже существует");
	}

	private void validateAssignmentRequest(OrganizationServiceAssignmentRequest request) {
		if (request == null || request.getOrganizationId() == null || request.getServiceId() == null) {
			throw new IllegalArgumentException("Организация и сервис обязательны");
		}
		if (request.getValidFrom() != null && request.getValidTo() != null && request.getValidTo().isBefore(request.getValidFrom())) {
			throw new IllegalArgumentException("Дата окончания договора не может быть раньше даты начала");
		}
	}

	private List<OrganizationServiceAssignment> effectiveAssignments(Long organizationId) {
		return configuredAssignments(organizationId).stream()
				.filter(item -> !Boolean.FALSE.equals(item.getActive()))
				.filter(item -> item.isEffectiveOn(LocalDate.now()))
				.filter(item -> item.getService() != null && !Boolean.FALSE.equals(item.getService().getActive()))
				.toList();
	}

	private List<OrganizationServiceAssignment> configuredAssignments(Long organizationId) {
		if (organizationId == null) return List.of();
		return assignmentRepository.findAllByOrganizationIdOrderByDefaultForOrganizationDescIdAsc(organizationId);
	}

	private Map<String, Object> toDto(ru.ravel.ItDesk.model.Service service) {
		Map<String, Object> result = new LinkedHashMap<>(toCompactDto(service));
		result.put("description", Objects.toString(service.getDescription(), ""));
		result.put("monitoringUrl", service.getMonitoringUrl());
		result.put("orderNumber", service.getOrderNumber());
		result.put("owner", userDto(service.getOwner()));
		result.put("defaultSupportLine", supportLineDto(service.getDefaultSupportLine()));
		result.put("defaultPriority", priorityDto(service.getDefaultPriority()));
		result.put("taskTypes", safeSet(service.getAllowedTaskTypes()).stream().sorted().map(this::taskTypeDto).toList());
		result.put("executors", safeSet(service.getExecutors()).stream().map(this::userDto).toList());
		result.put("dependencies", safeSet(service.getDependentServices()).stream().sorted().map(this::toCompactDto).toList());
		result.put("firstResponseSlaMinutes", service.getFirstResponseSlaMinutes());
		result.put("resolutionSlaMinutes", service.getResolutionSlaMinutes());
		result.put("supportCalendar", service.getSupportCalendar());
		result.put("pauseSlaOnWaitingClient", service.getPauseSlaOnWaitingClient());
		result.put("autoAssignDefaultLine", service.getAutoAssignDefaultLine());
		result.put("priorityRules", safeList(service.getPriorityRules()));
		result.put("olaStages", safeList(service.getOlaStages()));
		result.put("zabbixIntegration", zabbixIntegrationDto(service.getZabbixIntegration()));
		result.put("zabbixEnabled", service.getZabbixEnabled());
		result.put("monitoringIdentifiers", Objects.requireNonNullElseGet(service.getMonitoringIdentifiers(), LinkedHashMap::new));
		result.put("lastMonitoringData", Objects.requireNonNullElseGet(service.getLastMonitoringData(), LinkedHashMap::new));
		// The catalog is available to operators and observers. Expose only the
		// assignment data required to determine service availability; contract
		// numbers, tariffs and notes remain behind the admin-only assignment API.
		result.put("assignments", assignmentRepository.findAllByServiceIdOrderByOrganizationNameAsc(service.getId()).stream()
				.map(this::toAvailabilityAssignmentDto)
				.toList());
		return result;
	}

	private Map<String, Object> toCompactDto(ru.ravel.ItDesk.model.Service service) {
		if (service == null) return null;
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", service.getId());
		result.put("code", service.getCode());
		result.put("name", service.getName());
		result.put("active", service.getActive());
		result.put("criticality", criticalityDto(service.getCriticality()));
		result.put("monitoringStatus", service.getMonitoringStatus());
		result.put("lastMonitoringAt", service.getLastMonitoringAt());
		result.put("lastMonitoringMessage", service.getLastMonitoringMessage());
		return result;
	}

	private Map<String, Object> toAvailabilityAssignmentDto(OrganizationServiceAssignment assignment) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", assignment.getId());
		result.put("organization", organizationDto(assignment.getOrganization()));
		result.put("active", assignment.getActive());
		result.put("defaultForOrganization", assignment.getDefaultForOrganization());
		result.put("effective", assignment.isEffectiveOn(LocalDate.now()));
		return result;
	}

	private Map<String, Object> toAssignmentDto(OrganizationServiceAssignment assignment) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", assignment.getId());
		result.put("organization", organizationDto(assignment.getOrganization()));
		result.put("service", toCompactDto(assignment.getService()));
		result.put("contractNumber", assignment.getContractNumber());
		result.put("tariffName", assignment.getTariffName());
		result.put("validFrom", assignment.getValidFrom());
		result.put("validTo", assignment.getValidTo());
		result.put("supportCalendar", assignment.getSupportCalendar());
		result.put("slaPolicyName", assignment.getSlaPolicyName());
		result.put("firstResponseSlaMinutes", assignment.getFirstResponseSlaMinutes());
		result.put("resolutionSlaMinutes", assignment.getResolutionSlaMinutes());
		result.put("availableChannels", Objects.requireNonNullElseGet(assignment.getAvailableChannels(), ArrayList::new));
		result.put("active", assignment.getActive());
		result.put("defaultForOrganization", assignment.getDefaultForOrganization());
		result.put("notes", assignment.getNotes());
		result.put("effective", assignment.isEffectiveOn(LocalDate.now()));
		return result;
	}

	private Map<String, Object> userDto(User user) {
		if (user == null) return null;
		return Map.of(
				"id", user.getId(),
				"firstname", Objects.toString(user.getFirstname(), ""),
				"lastname", Objects.toString(user.getLastname(), ""),
				"username", Objects.toString(user.getUsername(), "")
		);
	}

	private Map<String, Object> supportLineDto(SupportLine line) {
		return line == null ? null : Map.of("id", line.getId(), "name", Objects.toString(line.getName(), ""), "level", Objects.requireNonNullElse(line.getLevel(), 1));
	}

	private Map<String, Object> priorityDto(Priority priority) {
		return priority == null ? null : Map.of("id", priority.getId(), "name", Objects.toString(priority.getName(), ""));
	}

	private Map<String, Object> taskTypeDto(TaskType type) {
		return type == null ? null : Map.of("id", type.getId(), "type", Objects.toString(type.getType(), ""));
	}

	private Map<String, Object> organizationDto(Organization organization) {
		return organization == null ? null : Map.of("id", organization.getId(), "name", Objects.toString(organization.getName(), ""));
	}

	private Map<String, Object> criticalityDto(ServiceCriticality criticality) {
		return criticality == null ? null : Map.of(
				"id", criticality.getId(),
				"name", Objects.toString(criticality.getName(), ""),
				"orderNumber", Objects.requireNonNullElse(criticality.getOrderNumber(), 0),
				"defaultSelection", Boolean.TRUE.equals(criticality.getDefaultSelection())
		);
	}

	private Map<String, Object> zabbixIntegrationDto(ZabbixIntegration integration) {
		if (integration == null) return null;
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", integration.getId());
		result.put("name", integration.getName());
		result.put("baseUrl", integration.getBaseUrl());
		result.put("username", integration.getUsername());
		result.put("active", integration.getActive());
		result.put("requestTimeoutSeconds", integration.getRequestTimeoutSeconds());
		result.put("hasPassword", integration.hasPassword());
		result.put("hasApiToken", integration.hasApiToken());
		result.put("lastTestedAt", integration.getLastTestedAt());
		result.put("lastTestSuccess", integration.getLastTestSuccess());
		result.put("lastTestMessage", integration.getLastTestMessage());
		return result;
	}

	private ServiceCriticality resolveOrDefaultCriticality(Long id) {
		if (id != null) return criticalityRepository.findById(id).orElseThrow();
		return criticalityRepository.findByDefaultSelectionTrue().orElse(null);
	}

	private List<ServicePriorityRule> normalizePriorityRules(List<ServicePriorityRule> rules) {
		if (rules == null) return new ArrayList<>();
		List<ServicePriorityRule> result = new ArrayList<>();
		for (int i = 0; i < rules.size(); i++) {
			ServicePriorityRule rule = rules.get(i);
			if (rule == null || rule.getPriorityId() == null) continue;
			priorityRepository.findById(rule.getPriorityId()).orElseThrow(() -> new IllegalArgumentException("Приоритет правила не найден: " + rule.getPriorityId()));
			if (rule.getTaskTypeId() != null) taskTypeRepository.findById(rule.getTaskTypeId()).orElseThrow();
			rule.setId(rule.getId() == null || rule.getId().isBlank() ? UUID.randomUUID().toString() : rule.getId());
			rule.setName(defaultString(rule.getName(), "Правило " + (i + 1)));
			rule.setOrderNumber(rule.getOrderNumber() == null ? i : rule.getOrderNumber());
			rule.setActive(!Boolean.FALSE.equals(rule.getActive()));
			result.add(rule);
		}
		return result;
	}

	private List<ServiceOlaStage> normalizeOlaStages(List<ServiceOlaStage> stages) {
		if (stages == null) return new ArrayList<>();
		List<ServiceOlaStage> result = new ArrayList<>();
		Set<Long> seenLines = new HashSet<>();
		for (int i = 0; i < stages.size(); i++) {
			ServiceOlaStage stage = stages.get(i);
			if (stage == null || stage.getSupportLineId() == null || stage.getDurationSeconds() == null || stage.getDurationSeconds() <= 0) continue;
			supportLineRepository.findById(stage.getSupportLineId()).orElseThrow();
			if (!seenLines.add(stage.getSupportLineId())) throw new IllegalArgumentException("Линия поддержки повторяется в OLA-этапах");
			stage.setOrderNumber(stage.getOrderNumber() == null ? i : stage.getOrderNumber());
			stage.setUseWorkingTime(!Boolean.FALSE.equals(stage.getUseWorkingTime()));
			stage.setWarningPercent(Math.max(1, Math.min(100, Objects.requireNonNullElse(stage.getWarningPercent(), 80))));
			stage.setActive(!Boolean.FALSE.equals(stage.getActive()));
			result.add(stage);
		}
		return result;
	}


	private boolean dependsOn(ru.ravel.ItDesk.model.Service current, Long targetId, Set<Long> visited) {
		if (current == null || current.getId() == null || targetId == null || !visited.add(current.getId())) return false;
		if (Objects.equals(current.getId(), targetId)) return true;
		return safeSet(current.getDependentServices()).stream().anyMatch(next -> dependsOn(next, targetId, visited));
	}


	private boolean matchesKeyword(String text, String keyword) {
		String normalized = keyword.toLowerCase(Locale.ROOT);
		if (normalized.startsWith("regex:")) {
			try { return Pattern.compile(keyword.substring(6), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text).find(); }
			catch (Exception ignored) { return false; }
		}
		return text.contains(normalized);
	}

	private String normalizeCode(String code) {
		String normalized = Objects.toString(code, "").trim().toUpperCase(Locale.ROOT).replaceAll("[^A-ZА-Я0-9_.-]+", "-");
		if (normalized.isBlank()) throw new IllegalArgumentException("Некорректный код сервиса");
		return normalized;
	}

	private Integer nonNegative(Integer value) {
		return value == null ? null : Math.max(0, value);
	}

	private String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	private String defaultString(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value.trim();
	}

	private <T> T findNullable(org.springframework.data.jpa.repository.JpaRepository<T, Long> repository, Long id) {
		return id == null ? null : repository.findById(id).orElseThrow();
	}

	private <T> Set<T> resolveSet(org.springframework.data.jpa.repository.JpaRepository<T, Long> repository, Collection<Long> ids) {
		if (ids == null || ids.isEmpty()) return new LinkedHashSet<>();
		Map<Long, T> byId = repository.findAllById(ids).stream().collect(Collectors.toMap(this::entityId, Function.identity()));
		return ids.stream().filter(Objects::nonNull).distinct().map(byId::get).filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
	}

	private Long entityId(Object entity) {
		return switch (entity) {
			case User value -> value.getId();
			case TaskType value -> value.getId();
			case ru.ravel.ItDesk.model.Service value -> value.getId();
			default -> throw new IllegalArgumentException("Unsupported entity: " + entity);
		};
	}

	private <T> List<T> safeList(List<T> values) { return values == null ? List.of() : values; }
	private <T> Set<T> safeSet(Set<T> values) { return values == null ? Set.of() : values; }

	public record SlaPolicy(Integer firstResponseMinutes, Integer resolutionMinutes, String supportCalendar) {}
}
