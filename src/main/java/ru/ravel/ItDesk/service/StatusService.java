package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.model.Status;
import ru.ravel.ItDesk.repository.StatusRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
public class StatusService {

	private final StatusRepository statusRepository;


	public List<Status> getStatuses() {
		return statusRepository.findAll();
	}
}
