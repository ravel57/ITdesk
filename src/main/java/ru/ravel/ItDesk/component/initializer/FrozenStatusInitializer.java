package ru.ravel.ItDesk.component.initializer;

import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;
import ru.ravel.ItDesk.model.FrozenStatus;
import ru.ravel.ItDesk.repository.FrozenStatusRepository;


@Component
@AllArgsConstructor
public class FrozenStatusInitializer {

	private final FrozenStatusRepository completedStatusRepository;

	@PostConstruct
	public void init() {
		FrozenStatus.initializeInstance(completedStatusRepository);
	}

}
