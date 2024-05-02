package ru.ravel.ItDesk.service;

import org.jetbrains.annotations.NotNull;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.model.Role;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.reposetory.UserRepository;

import java.util.List;


@Service
public class AuthService implements UserDetailsService {

	private final UserRepository repository;

	public AuthService(UserRepository repository) {
		this.repository = repository;
		User admin = repository.findByUsername("admin");
		if (admin == null) {
			repository.save(User.builder()
					.username("admin")
					.password("$2a$12$qzyw1.HJ4TIKvq8Z.Vdt6uwKRTvimL9V6h53u.s/DyoqDEVuML1j.")
					.roles(List.of(Role.ADMIN))
					.build());
		}
	}

	@Override
	public UserDetails loadUserByUsername(@NotNull String username) throws UsernameNotFoundException {
		return repository.findByUsername(username.trim());
	}
}