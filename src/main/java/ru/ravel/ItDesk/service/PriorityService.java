package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.model.Priority;
import ru.ravel.ItDesk.repository.PriorityRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PriorityService {

	private final PriorityRepository priorityRepository;


	public List<Priority> getPriorities() {
		return priorityRepository.findAll().stream().sorted().toList();
	}


	public Priority newPriority(Priority priority) {
		priority.setOrderNumber(getPriorities().size() + 1);
		return priorityRepository.save(priority);
	}


	public void deletePriority(Long priorityId) {
		priorityRepository.deleteById(priorityId);
	}


	public Priority prioritySetDefaultSelection(@NotNull Priority priority) {
		List<Priority> statuses = priorityRepository.findAll().stream()
				.peek(p -> p.setDefaultSelection(false))
				.toList();
		priorityRepository.saveAll(statuses);
		priority.setDefaultSelection(true);
		priorityRepository.save(priority);
		return priority;
	}


	public Priority prioritySetCritical(Priority priority) {
		List<Priority> statuses = priorityRepository.findAll().stream()
				.peek(p -> p.setCritical(false))
				.toList();
		priorityRepository.saveAll(statuses);
		priority.setCritical(true);
		priorityRepository.save(priority);
		return priority;
	}


	public List<Priority> resortPriorities(@NotNull List<Priority> newOrderedPriorities) {
		List<Priority> priorities = priorityRepository.findAll();
		for (Priority priority : priorities) {
			priority.setOrderNumber(newOrderedPriorities.indexOf(priority));
		}
		priorityRepository.saveAll(priorities);
		return priorities.stream().sorted().toList();
	}

}