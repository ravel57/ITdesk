package ru.ravel.ItDesk.model;

import jakarta.persistence.Entity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.jetbrains.annotations.NotNull;
import ru.ravel.ItDesk.repository.FrozenStatusRepository;

import java.util.List;

@Entity
@Data
@EqualsAndHashCode(callSuper = true)
public class FrozenStatus extends Status {

	private static FrozenStatus instance;
	private static FrozenStatusRepository repository;

	protected String name = "Заморожена";
	protected Integer orderNumber = -1;

	protected FrozenStatus() {
	}

	public static FrozenStatus getInstance() {
		assert repository != null;
		List<FrozenStatus> all = repository.findAll();
		if (all.isEmpty()) {
			instance = new FrozenStatus();
			repository.save(instance);
		} else {
			instance = all.getFirst();
		}
		return instance;
	}


	public static void initializeInstance(FrozenStatusRepository repository) {
		FrozenStatus.repository = repository;
	}


	@Override
	public int compareTo(@NotNull Status o) {
		if (o.getOrderNumber() == null) {
			return 1;
		}
		return getOrderNumber().compareTo(o.getOrderNumber());
	}

}