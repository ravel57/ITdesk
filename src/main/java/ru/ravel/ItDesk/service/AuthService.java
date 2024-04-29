package ru.ravel.ItDesk.service;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.models.Role;
import ru.ravel.ItDesk.models.User;
import ru.ravel.ItDesk.reposetory.UserRepository;

import java.util.Set;

@Service
public class AuthService implements UserDetailsService {

	private final UserRepository repository;

	public AuthService(UserRepository repository) {
		this.repository = repository;
		User admin = repository.findByUsername("admin");
		if (admin == null)
			repository.save(new User(1L, "admin", "$2a$12$qzyw1.HJ4TIKvq8Z.Vdt6uwKRTvimL9V6h53u.s/DyoqDEVuML1j.",  Set.of(Role.ADMIN)));
	}

	@Override
	public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
		return repository.findByUsername(username.trim());
	}
}