package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.model.FrontendUser;
import ru.ravel.ItDesk.model.Role;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.repository.UserRepository;

import java.util.Arrays;
import java.util.List;


@Service
@RequiredArgsConstructor
public class UserService {

	private final UserRepository userRepository;

	private final PasswordEncoder passwordEncoder;

	private final SessionRegistry sessionRegistry;


	public List<User> getUsers() {
		return userRepository.findAll();
	}


	public List<Role> getRoles() {
		return Arrays.stream(Role.values()).toList();
	}


	public User newUser(@NotNull FrontendUser frontendUser) {
		User user = User.builder()
				.username(frontendUser.getUsername())
				.firstname(frontendUser.getFirstname())
				.lastname(frontendUser.getLastname())
				.authorities(List.of(Role.valueOf(frontendUser.getAuthorities())))
				.password(passwordEncoder.encode(frontendUser.getPassword()))
				.isEnabled(true)
				.isAccountNonLocked(true)
				.isAccountNonExpired(true)
				.isCredentialsNonExpired(true)
				.build();
		return userRepository.save(user);
	}


	public User updateUser(@NotNull FrontendUser frontendUser) {
		User savedUser = userRepository.findById(frontendUser.getId()).orElseThrow();
		User user = User.builder()
				.id(frontendUser.getId())
				.firstname(frontendUser.getFirstname())
				.lastname(frontendUser.getLastname())
				.authorities(List.of(Role.valueOf(frontendUser.getAuthorities())))
				.username(savedUser.getUsername())
				.password(savedUser.getPassword())
				.build();
		return userRepository.save(user);
	}


	public List<User> getAllAuthenticatedUsers() {
		return sessionRegistry.getAllPrincipals().stream()
				.filter(principal -> principal instanceof User)
				.map(principal -> (User) principal)
				.toList();
	}
}