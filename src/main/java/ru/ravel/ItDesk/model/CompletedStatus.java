package ru.ravel.ItDesk.model;

import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Transient;
import lombok.Data;
import lombok.EqualsAndHashCode;
import ru.ravel.ItDesk.repository.CompletedStatusRepository;

import java.util.List;

@Entity
@Data
@EqualsAndHashCode(callSuper = true)
@DiscriminatorValue("CompletedStatus")
public class CompletedStatus extends Status {

	@Transient
	private static CompletedStatus instance;
	@Transient
	private static CompletedStatusRepository repository;

	protected String name = "Закрыта";


	protected CompletedStatus() {
	}


	public static CompletedStatus getInstance() {
		assert repository != null;
		List<CompletedStatus> all = repository.findAll();
		if (all.isEmpty()) {
			instance = new CompletedStatus();
			repository.save(instance);
		} else {
			instance = all.getFirst();
		}
		return instance;
	}


	public static void initializeInstance(CompletedStatusRepository completedStatusRepository) {
		CompletedStatus.repository = completedStatusRepository;
	}


	public void save() {
		repository.save(instance);
	}

}