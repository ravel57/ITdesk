package ru.ravel.ItDesk.model;

import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.*;
import ru.ravel.ItDesk.repository.SystemUserRepository;

import java.io.Serializable;
import java.util.List;


@Entity
@Data
@EqualsAndHashCode(callSuper = true)
@DiscriminatorValue("SystemUser")
public class SystemUser extends User implements Serializable {

	@Transient
	private static SystemUser instance;
	@Transient
	private static SystemUserRepository repository;

	protected String name = "Система (ULDESK)";


	public static SystemUser getInstance() {
		assert repository != null;
		List<SystemUser> all = repository.findAll();
		if (all.isEmpty()) {
			instance = new SystemUser();
			repository.save(instance);
		} else {
			instance = all.getFirst();
		}
		return instance;
	}


	public static void initializeInstance(SystemUserRepository SystemUserRepository) {
		SystemUser.repository = SystemUserRepository;
	}


	public void save() {
		repository.save(instance);
	}

}
