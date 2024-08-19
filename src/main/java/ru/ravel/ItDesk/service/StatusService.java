package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.model.CompletedStatus;
import ru.ravel.ItDesk.model.FrozenStatus;
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


	public Status newStatus(@NotNull Status status) {
		status.setOrderNumber((int) (statusRepository.count() + 1));
		return statusRepository.save(status);
	}


	public Status updateStatus(@NotNull Status status) {
		if (status.getId().equals(FrozenStatus.getInstance().getId()) || status.getId().equals(CompletedStatus.getInstance().getId())) {
			throw new RuntimeException();
		}
		return statusRepository.save(status);
	}


	public void deleteStatus(Long statusId) {
		Status status = statusRepository.findById(statusId).orElseThrow();
		if (status.getId().equals(FrozenStatus.getInstance().getId()) || status.getId().equals(CompletedStatus.getInstance().getId())) {
			throw new RuntimeException();
		}
		statusRepository.deleteById(statusId);
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


	public List<Status> resortStatuses(@NotNull List<Status> statuses) {
		for (int i = 0; i < statuses.size(); i++) {
			statuses.get(i).setOrderNumber(i);
		}
		List<Status> list = statuses.stream().sorted().toList();
		CompletedStatus completedStatus = CompletedStatus.getInstance();
		FrozenStatus frozenStatus = FrozenStatus.getInstance();
		list.stream().filter(status -> status.getName().equals(completedStatus.getName()))
				.findFirst()
				.ifPresent(status -> {
					completedStatus.setOrderNumber(status.getOrderNumber());
					completedStatus.save();
				});
		list.stream().filter(status -> status.getName().equals(frozenStatus.getName()))
				.findFirst()
				.ifPresent(status -> {
					frozenStatus.setOrderNumber(status.getOrderNumber());
					frozenStatus.save();
				});
		statusRepository.saveAll(list.stream()
				.filter(status -> !status.getName().equals(completedStatus.getName()) && !status.getName().equals(frozenStatus.getName()))
				.toList());
		return list;
	}

}
