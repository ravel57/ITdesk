package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.model.Role;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.repository.UserRepository;

import java.util.List;


@Service
public class AuthService implements UserDetailsService {

	private final UserRepository repository;


	public AuthService(@NotNull UserRepository repository) {
		this.repository = repository;
		if (repository.findAll().isEmpty()) {
			repository.save(User.builder()
					.username("admin")
					.firstname("admin")
					.password("$2a$12$qzyw1.HJ4TIKvq8Z.Vdt6uwKRTvimL9V6h53u.s/DyoqDEVuML1j.")
					.authorities(List.of(Role.ADMIN))
					.build());
		}
	}


	@Override
	public UserDetails loadUserByUsername(@NotNull String username) throws UsernameNotFoundException {
		return repository.findByUsername(username.trim()).orElseThrow(() -> new UsernameNotFoundException("incorrect login"));
	}
}