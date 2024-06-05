package ru.ravel.ItDesk.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.dto.Password;
import ru.ravel.ItDesk.model.FrontendUser;
import ru.ravel.ItDesk.model.Role;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.repository.UserRepository;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


@Service
@RequiredArgsConstructor
public class UserService {

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;

	@Value("${max-users}")
	private int maxUsers;

	@Getter
	private final Set<User> usersOnline = new HashSet<>();


	public List<User> getUsers() {
		return userRepository.findAll();
	}


	public List<Role> getRoles() {
		return Arrays.stream(Role.values()).toList();
	}


	public User newUser(@NotNull FrontendUser frontendUser) {
		if (userRepository.findAll().size() < maxUsers) {
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
		} else {
			throw new RuntimeException("max users is " + maxUsers);
		}
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


	public void userOnline(@NotNull User user) {
		userRepository.findById(user.getId()).ifPresent(usersOnline::add);
	}


	public void userOffline(@NotNull User user) {
		userRepository.findById(user.getId()).ifPresent(usersOnline::remove);
	}


	public User changePassword(@NotNull Password password) {
		User user = getCurrentUser();
		user.setPassword(passwordEncoder.encode(password.getPassword()));
		return user;
	}


	public void deleteUser(Long userId) {
		if (userRepository.findAll().size() > 1) {
			userRepository.deleteById(userId);
		} else {
			throw new RuntimeException("user is empty");
		}
	}
}