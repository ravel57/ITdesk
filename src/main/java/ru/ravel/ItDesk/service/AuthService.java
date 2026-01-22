package ru.ravel.ItDesk.service;

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
	private final UserRepository repository;
	private final ObjectProvider<DemoResetService> demoResetServiceProvider;
	private final FlywayMigrationRunner flywayMigrationRunner;

	@Override
	public UserDetails loadUserByUsername(@NotNull String username) throws UsernameNotFoundException {
		if (isDemo) {
			DemoResetService resetService = demoResetServiceProvider.getIfAvailable();
			if (resetService != null) {
				resetService.truncateAllData();
				flywayMigrationRunner.run();
				resetService.prepareTestData();
			} else {
				throw new UsernameNotFoundException(username);
			}
		}
		return repository.findByUsername(username.trim()).orElseThrow(() -> new UsernameNotFoundException("incorrect login"));
	}

}