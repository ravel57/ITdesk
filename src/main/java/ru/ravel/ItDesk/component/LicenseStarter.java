package ru.ravel.ItDesk.component;

import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import ru.ravel.ItDesk.feign.LicenseFeignClient;
import ru.ravel.ItDesk.model.License;
import ru.ravel.ItDesk.repository.LicenseRepository;

import java.time.ZonedDateTime;
import java.util.List;


@Component
@RequiredArgsConstructor
public class LicenseStarter implements CommandLineRunner {

	private final LicenseFeignClient licenseFeignClient;
	private final LicenseRepository repository;

	private final Logger logger = LoggerFactory.getLogger(this.getClass());

	@Value("${instance-name}")
	private String instanceName;

	public static Long maxUsers;			// FIXME
	public static Boolean isLicenseActive;	// FIXME


	@Override
	public void run(String... args) {
		try {
			License instance;
			List<License> instances = repository.findAll();
			if (instances.isEmpty()) {
				instance = repository.save(licenseFeignClient.register(instanceName));
			} else {
				instance = licenseFeignClient.license(instances.getFirst().getLicense().toString());
			}
			maxUsers = instance.getUsersCount();
			if (ZonedDateTime.now().isAfter(instance.getValidUntil())) {
				throw new RuntimeException("license expired");
			}
			isLicenseActive = true;
			logger.info("license accessed");
		} catch (RuntimeException e) {
			logger.error(e.getMessage());
			isLicenseActive = false;
		}
	}
}
