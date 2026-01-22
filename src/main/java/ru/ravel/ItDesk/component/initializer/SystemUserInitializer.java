package ru.ravel.ItDesk.component.initializer;

import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;
import ru.ravel.ItDesk.model.SystemUser;
import ru.ravel.ItDesk.repository.SystemUserRepository;


@Component
@AllArgsConstructor
public class SystemUserInitializer {

	private final SystemUserRepository systemUserRepository;

	@PostConstruct
	public void init() {
		SystemUser.initializeInstance(systemUserRepository);
	}

}
