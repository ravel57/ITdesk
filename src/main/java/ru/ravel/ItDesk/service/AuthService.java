package ru.ravel.ItDesk.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.component.FlywayMigrationRunner;
import org.springframework.beans.factory.ObjectProvider;
import ru.ravel.ItDesk.repository.UserRepository;

@Service
@RequiredArgsConstructor
public class AuthService implements UserDetailsService {

	@Value("${app.is-demo:false}")
	private boolean isDemo;

	@Value("${app.demo.allow-destructive-reset:false}")
	private boolean allowDestructiveDemoReset;

	@Value("${spring.profiles.active:}")
	private String activeProfiles;

	private final Object demoResetLock = new Object();
	private final UserRepository repository;
	private final ObjectProvider<DemoResetService> demoResetServiceProvider;
	private final FlywayMigrationRunner flywayMigrationRunner;


	@PostConstruct
	void validateDemoModeConfiguration() {
		if (!isDemo) {
			return;
		}
		boolean productionProfile = java.util.Arrays.stream(activeProfiles == null ? new String[0] : activeProfiles.split(","))
				.map(String::trim)
				.anyMatch(profile -> profile.equalsIgnoreCase("prod") || profile.equalsIgnoreCase("production"));
		if (productionProfile) {
			throw new IllegalStateException("Demo mode with destructive reset is forbidden in production profile");
		}
		if (!allowDestructiveDemoReset) {
			throw new IllegalStateException(
					"Demo mode requires explicit app.demo.allow-destructive-reset=true acknowledgement"
			);
		}
	}

	@Override
	public UserDetails loadUserByUsername(@NotNull String username) throws UsernameNotFoundException {
		if (isDemo) {
			synchronized (demoResetLock) {
				DemoResetService resetService = demoResetServiceProvider.getIfAvailable();
				if (resetService != null) {
					resetService.truncateAllData();
					flywayMigrationRunner.run();
					resetService.prepareTestData();
				} else {
					throw new UsernameNotFoundException(username);
				}
			}
		}
		return repository.findByUsername(username.trim()).orElseThrow(() -> new UsernameNotFoundException("incorrect login"));
	}

}