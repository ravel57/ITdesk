package ru.ravel.ItDesk.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.model.FrontendUser;
import ru.ravel.ItDesk.model.Role;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.repository.UserRepository;

import java.util.*;


@Service
@RequiredArgsConstructor
public class UserService {

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;

	@Getter
	private final Set<User> usersOnline = new HashSet<>();


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


	public User getUserByUsername(@NotNull String username) {
		return userRepository.findByUsername(username).orElseThrow();
	}


	public User getCurrentUser() {
		String username = SecurityContextHolder.getContext().getAuthentication().getName();
		return getUsers().stream()
				.filter(it -> it.getUsername().equals(username))
				.findFirst()
				.orElseThrow();
	}


	public User userOnline() {
		User currentUser = getCurrentUser();
		usersOnline.add(currentUser);
		return currentUser;
	}


	public User userOffline(User user) {
		usersOnline.remove(user);
		return user;
	}

}