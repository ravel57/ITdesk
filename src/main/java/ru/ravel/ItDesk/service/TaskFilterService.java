package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.model.TaskFilter;
import ru.ravel.ItDesk.model.TaskFilterScope;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.repository.TaskFilterRepository;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class TaskFilterService {

	private final TaskFilterRepository taskFilterRepository;
	private final UserService userService;


	public List<TaskFilter> getVisibleFilters() {
		User currentUser = userService.getCurrentUser();
		if (userService.isObserver(currentUser)) {
			return taskFilterRepository.findAllByScopeAndOwnerUserIdOrderByLabelAsc(
					TaskFilterScope.PERSONAL,
					currentUser.getId()
			);
		}
		if (userService.isAdmin(currentUser) || userService.isManager(currentUser) || userService.isOperator(currentUser)) {
			return taskFilterRepository.findAllByScopeOrOwnerUserIdOrderByLabelAsc(
					TaskFilterScope.GLOBAL,
					currentUser.getId()
			);
		}
		return List.of();
	}


	@Transactional
	public TaskFilter saveTaskFilter(@NotNull TaskFilter taskFilter) {
		User currentUser = userService.getCurrentUser();
		TaskFilter existing = taskFilter.getId() == null
				? null
				: taskFilterRepository.findById(taskFilter.getId()).orElseThrow();

		if (existing != null) {
			assertCanEdit(currentUser, existing);
		}

		TaskFilterScope requestedScope = normalizeRequestedScope(currentUser, taskFilter.getScope());
		if (requestedScope == TaskFilterScope.GLOBAL && !canManageGlobalFilters(currentUser)) {
			throw new AccessDeniedException("Нет прав на глобальные фильтры");
		}

		TaskFilter target = existing == null ? new TaskFilter() : existing;
		String label = Objects.toString(taskFilter.getLabel(), "").trim();
		if (label.isBlank()) {
			throw new IllegalArgumentException("Название фильтра обязательно");
		}

		target.setLabel(label);
		target.setFilterJoinOperator(taskFilter.getFilterJoinOperator());
		target.setSelectedOptions(taskFilter.getSelectedOptions());
		target.setScope(requestedScope);
		target.setOwnerUserId(requestedScope == TaskFilterScope.PERSONAL ? currentUser.getId() : null);
		return taskFilterRepository.save(target);
	}


	@Transactional
	public void deleteTaskFilter(Long filterId) {
		User currentUser = userService.getCurrentUser();
		TaskFilter taskFilter = taskFilterRepository.findById(filterId).orElseThrow();
		assertCanEdit(currentUser, taskFilter);
		taskFilterRepository.delete(taskFilter);
	}


	private TaskFilterScope normalizeRequestedScope(User currentUser, TaskFilterScope requestedScope) {
		if (userService.isObserver(currentUser) || userService.isOperator(currentUser)) {
			return TaskFilterScope.PERSONAL;
		}
		if (requestedScope != null) {
			return requestedScope;
		}
		// До разделения фильтры были общими; сохраняем обратную совместимость для старых ADMIN/MANAGER клиентов.
		return TaskFilterScope.GLOBAL;
	}


	private void assertCanEdit(User currentUser, TaskFilter taskFilter) {
		TaskFilterScope scope = Objects.requireNonNullElse(taskFilter.getScope(), TaskFilterScope.GLOBAL);
		if (scope == TaskFilterScope.GLOBAL) {
			if (!canManageGlobalFilters(currentUser)) {
				throw new AccessDeniedException("Нет прав на изменение глобального фильтра");
			}
			return;
		}
		if (!Objects.equals(taskFilter.getOwnerUserId(), currentUser.getId())) {
			throw new AccessDeniedException("Нет прав на изменение персонального фильтра");
		}
	}


	private boolean canManageGlobalFilters(User currentUser) {
		return userService.isAdmin(currentUser) || userService.isManager(currentUser);
	}
}
