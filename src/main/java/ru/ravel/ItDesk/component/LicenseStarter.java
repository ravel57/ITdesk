package ru.ravel.ItDesk.component;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;
import ru.ravel.ItDesk.feign.LicenseFeignClient;
import ru.ravel.ItDesk.model.License;
import ru.ravel.ItDesk.repository.LicenseRepository;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;


@Component
@RequiredArgsConstructor
public class LicenseStarter {

	private final LicenseFeignClient licenseFeignClient;
	private final LicenseRepository repository;
	private final BuildProperties buildProperties;

	private final Logger logger = LoggerFactory.getLogger(this.getClass());

	@Value("${instance-name}")
	private String instanceName;

	public static Long maxUsers;               // FIXME
	public static Boolean isLicenseActive;     // FIXME
	public static Boolean isLicenseExpireSoon; // FIXME
	public static Boolean isLicenseExpired;    // FIXME
	public static ZonedDateTime licenseUntil;  // FIXME


	public void run() {
		try {
			License instance;
			List<License> instances = repository.findAll();
			if (instances.isEmpty()) {
				instance = licenseFeignClient.register(instanceName);
				repository.save(instance);
			} else {
				instance = licenseFeignClient.license(instances.getFirst().getLicense());
			}
			instance.setVersion(buildProperties.getVersion());
			licenseFeignClient.sendVersion(instance.getLicense(), buildProperties.getVersion());
			maxUsers = instance.getUsersCount();
			if (ZonedDateTime.now().isAfter(Objects.requireNonNullElse(instance.getValidUntil(), ZonedDateTime.now()))) {
				isLicenseExpired = true;
				throw new RuntimeException("license expired");
			}
			isLicenseActive = true;
			isLicenseExpireSoon = ZonedDateTime.now().plusDays(7).isAfter(instance.getValidUntil());
			licenseUntil = instance.getValidUntil();
			logger.info("license accessed");
		} catch (RuntimeException e) {
			logger.error(e.getMessage());
			isLicenseActive = false;
		}
	}

}