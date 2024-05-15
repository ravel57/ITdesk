package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.model.Status;
import ru.ravel.ItDesk.repository.StatusRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
public class StatusService {

	private final StatusRepository statusRepository;


	public List<Status> getStatuses() {
		return statusRepository.findAll().stream().sorted().toList();
	}


	public Status newStatus(Status status) {
		return statusRepository.save(status);
	}


	public Status statusSetDefaultSelection(@NotNull Status status) {
		List<Status> statuses = statusRepository.findAll().stream()
				.peek(s -> s.setDefaultSelection(false))
				.toList();
		statusRepository.saveAll(statuses);
		status.setDefaultSelection(true);
		statusRepository.save(status);
		return status;
	}

	public List<Status> getResortedStatuses(@NotNull List<Status> statuses) {
		for (int i = 0; i < statuses.size(); i++) {
			statuses.get(i).setOrderNumber(i);
		}
		List<Status> list = statuses.stream().sorted().toList();
		statusRepository.saveAll(list);
		return list;
	}

}
