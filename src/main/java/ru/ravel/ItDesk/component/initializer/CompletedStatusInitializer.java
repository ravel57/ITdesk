package ru.ravel.ItDesk.component;

import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;
import ru.ravel.ItDesk.model.CompletedStatus;
import ru.ravel.ItDesk.repository.CompletedStatusRepository;


@Component
@AllArgsConstructor
public class CompletedStatusInitializer {

	private final CompletedStatusRepository completedStatusRepository;

	@PostConstruct
	public void init() {
		CompletedStatus.initializeInstance(completedStatusRepository);
	}

}
