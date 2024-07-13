package ru.ravel.ItDesk.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;
import ru.ravel.ItDesk.repository.DefaultOrganizationRepository;
import ru.ravel.ItDesk.repository.PriorityRepository;

import java.util.List;

@Entity
@NoArgsConstructor
public class DefaultOrganization extends Organization {

	@Transient
	@Getter
	private static DefaultOrganization instance;
	private static PriorityRepository priorityRepository;

	@Override
	public String getName() {
		return "Стандартный SLA";
	}

	public static void initializeInstance(@NotNull DefaultOrganizationRepository repository, PriorityRepository priorityRepository) {
		DefaultOrganization.priorityRepository = priorityRepository;
		List<DefaultOrganization> all = repository.findAll();
		if (!all.isEmpty()) {
			instance = all.getFirst();
		} else {
			instance = new DefaultOrganization();
			repository.save(instance);
		}
	}
}