package ru.ravel.ItDesk.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.component.LicenseStarter;
import ru.ravel.ItDesk.dto.Password;
import ru.ravel.ItDesk.dto.UserDto;
import ru.ravel.ItDesk.feign.SupportFeignClient;
import ru.ravel.ItDesk.model.License;
import ru.ravel.ItDesk.model.Message;
import ru.ravel.ItDesk.model.Role;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.repository.LicenseRepository;
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
	private final OrganizationService organizationService;
	private final SupportFeignClient supportFeignClient;
	private final LicenseRepository licenseRepository;

	private final Logger logger = LoggerFactory.getLogger(this.getClass());

	@Getter
	private final Set<User> usersOnline = new HashSet<>();


	public List<User> getUsers() {
		return userRepository.findAll();
	}


	public List<Role> getRoles() {
		return Arrays.stream(Role.values()).toList();
	}


	public User newUser(@NotNull UserDto userDto) {
		if (userRepository.findAll().size() < LicenseStarter.maxUsers) {
			try {
				User user = User.builder()
						.username(userDto.getUsername())
						.firstname(userDto.getFirstname())
						.lastname(userDto.getLastname())
						.authorities(List.of(Role.getByName(userDto.getAuthorities())))
						.password(passwordEncoder.encode(userDto.getPassword()))
						.availableOrganizations(userDto.getAvailableOrganizations().stream()
								.map(orgName -> organizationService.getOrganizations().stream()
										.filter(org -> org.getName().equals(orgName)).findFirst().orElseThrow()).toList())
						.isEnabled(true)
						.isAccountNonLocked(true)
						.isAccountNonExpired(true)
						.isCredentialsNonExpired(true)
						.build();
				return userRepository.save(user);
			} catch (Exception e) {
				logger.error(e.getMessage(), e);
				throw new RuntimeException(e);
			}
		} else {
			logger.info("max users is {}", LicenseStarter.maxUsers);
			throw new RuntimeException("max users is " + LicenseStarter.maxUsers);
		}
	}


	public User updateUser(@NotNull UserDto userDto) {
		User savedUser = userRepository.findById(userDto.getId()).orElseThrow();
		User user = User.builder()
				.id(userDto.getId())
				.firstname(userDto.getFirstname())
				.lastname(userDto.getLastname())
				.authorities(List.of(Role.getByName(userDto.getAuthorities())))
				.username(savedUser.getUsername())
				.password(savedUser.getPassword())
				.availableOrganizations(userDto.getAvailableOrganizations().stream()
						.map(orgName -> organizationService.getOrganizations().stream()
								.filter(org -> org.getName().equals(orgName)).findFirst().orElseThrow()).toList())
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
			throw new RuntimeException("users will be empty");
		}
	}


	public Message sendSupportMessage(Long userId, Message message) {
		User user = userRepository.findById(userId).orElseThrow();
		License license = licenseRepository.findAll().getFirst();
		supportFeignClient.newMessage(license.getLicense(), message);
		user.getSupport().getMessages().add(message);
		return message;
	}

}