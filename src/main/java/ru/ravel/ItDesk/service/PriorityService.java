package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.model.Priority;
import ru.ravel.ItDesk.repository.PriorityRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PriorityService {

	private final PriorityRepository priorityRepository;


	public List<Priority> getPriorities() {
		return priorityRepository.findAll();
	}


	public Priority newPriority(Priority priority) {
		return priorityRepository.save(priority);
	}

	public void deletePriority(Long priorityId) {
		priorityRepository.deleteById(priorityId);
	}
}