package ru.ravel.ItDesk.model;

import jakarta.persistence.Entity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.jetbrains.annotations.NotNull;
import ru.ravel.ItDesk.repository.CompletedStatusRepository;

import java.util.List;

@Entity
@Data
@EqualsAndHashCode(callSuper = true)
public class CompletedStatus extends Status {

	private static CompletedStatus instance;
	private static CompletedStatusRepository repository;

	protected String name = "Закрыта";
	protected Integer orderNumber = -1;


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


	@Override
	public int compareTo(@NotNull Status o) {
		if (o.getOrderNumber() == null) {
			return 1;
		}
		return getOrderNumber().compareTo(o.getOrderNumber());
	}

}