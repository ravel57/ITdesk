package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.model.TaskFilter;
import ru.ravel.ItDesk.repository.TaskFilterRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TaskFilterService {

	private final TaskFilterRepository taskFilterRepository;

	public List<TaskFilter> getAll() {
		return taskFilterRepository.findAll();
	}

	public TaskFilter saveTaskFilter(@NotNull TaskFilter taskFilter) {
		return taskFilterRepository.save(taskFilter);
	}
}
