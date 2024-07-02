package ru.ravel.ItDesk.component;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import ru.ravel.ItDesk.feign.LicenseFeignClient;
import ru.ravel.ItDesk.model.ItDeskInstance;
import ru.ravel.ItDesk.repository.ItDeskInstanceRepository;

import java.time.ZonedDateTime;
import java.util.List;


@Component
@RequiredArgsConstructor
public class LicenseStarter implements CommandLineRunner {

	private final LicenseFeignClient licenseFeignClient;
	private final ItDeskInstanceRepository repository;

	private final Logger logger = LoggerFactory.getLogger(this.getClass());

	@Value("${instance-name}")
	private String instanceName;

	public static Long maxUsers;


	@Override
	public void run(String... args) {
		try {
			ItDeskInstance instance;
			List<ItDeskInstance> instances = repository.findAll();
			if (instances.isEmpty()) {
				instance = repository.save(licenseFeignClient.register(instanceName));
			} else {
				instance = licenseFeignClient.license(instances.getFirst().getLicense().toString());
			}
			maxUsers = instance.getUsersCount();
			if (ZonedDateTime.now().isAfter(instance.getValidUntil())) {
				throw new RuntimeException("license expired");
			}
		} catch (RuntimeException e) {
			logger.error(e.getMessage());
			System.exit(1);
		}
	}
}
