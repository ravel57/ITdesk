package ru.ravel.ItDesk.service;

import lombok.AllArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.component.LicenseStarter;
import ru.ravel.ItDesk.model.GlobalNotification;

import java.util.concurrent.TimeUnit;

@Service
@AllArgsConstructor
public class GlobalNotificationService {

	WebSocketService webSocketService;


	@Scheduled(fixedRate = 5, timeUnit = TimeUnit.SECONDS)
	public void notifyLicenseExpireSoon() {
		if (LicenseStarter.isLicenseExpireSoon != null && LicenseStarter.isLicenseExpireSoon) {
			webSocketService.globalNotification(new GlobalNotification("Лицензия скоро закончится"));
		}
	}

}