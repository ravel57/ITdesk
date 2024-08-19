package ru.ravel.ItDesk.model;

import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Transient;
import lombok.Data;
import lombok.EqualsAndHashCode;
import ru.ravel.ItDesk.repository.FrozenStatusRepository;

import java.util.List;

@Entity
@Data
@EqualsAndHashCode(callSuper = true)
@DiscriminatorValue("FrozenStatus")
public class FrozenStatus extends Status {

	@Transient
	private static FrozenStatus instance;
	@Transient
	private static FrozenStatusRepository repository;

	protected String name = "Заморожена";

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


	public void save() {
		repository.save(instance);
	}


	public static void initializeInstance(FrozenStatusRepository repository) {
		FrozenStatus.repository = repository;
	}

}