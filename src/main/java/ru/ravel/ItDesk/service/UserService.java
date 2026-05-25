package ru.ravel.ItDesk.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.component.LicenseStarter;
import ru.ravel.ItDesk.dto.Password;
import ru.ravel.ItDesk.dto.UserDto;
import ru.ravel.ItDesk.dto.UserNotificationSettingsDto;
import ru.ravel.ItDesk.feign.SupportFeignClient;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.model.automatosation.TriggerType;
import ru.ravel.ItDesk.repository.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;


@Service
@RequiredArgsConstructor
public class UserService {

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final OrganizationService organizationService;
	private final SupportFeignClient supportFeignClient;
	private final LicenseRepository licenseRepository;
	private final MessageRepository messageRepository;
	private final SupportRepository supportRepository;

	private final Logger logger = LoggerFactory.getLogger(this.getClass());

	@Getter
	private final Set<Long> usersOnlineIds = ConcurrentHashMap.newKeySet();
	private final TaskRepository taskRepository;
	private final EventPublisher eventPublisher;


	public List<User> getUsers() {
		return userRepository.findAll().stream()
				.filter(user -> !Objects.equals(user, SystemUser.getInstance()))
				.filter(User::isEnabled)
				.toList();
	}


	public List<User> getAllUsersIncludingDisabled() {
		return userRepository.findAll().stream()
				.filter(user -> !Objects.equals(user, SystemUser.getInstance()))
				.toList();
	}


	public List<Role> getRoles() {
		return Arrays.stream(Role.values()).toList();
	}


	public User newUser(@NotNull UserDto userDto) {
		if (getUsers().size() < LicenseStarter.maxUsers) {
			Pattern pattern = Pattern.compile("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");
			if (pattern.matcher(userDto.getUsername()).matches()) {
				try {
					User user = User.builder()
							.username(userDto.getUsername())
							.firstname(userDto.getFirstname())
							.lastname(userDto.getLastname())
							.authorities(List.of(Role.getByName(userDto.getAuthorities())))
							.password(passwordEncoder.encode(userDto.getPassword()))
							.availableOrganizations(resolveAvailableOrganizations(userDto))
							.isEnabled(true)
							.isAccountNonLocked(true)
							.isAccountNonExpired(true)
							.isCredentialsNonExpired(true)
							.build();
					User saved = userRepository.save(user);
					eventPublisher.publish(TriggerType.USER_CREATED, Map.of("user", saved));
					return saved;

				} catch (Exception e) {
					logger.error(e.getMessage(), e);
					throw new RuntimeException(e);
				}
			} else {
				throw new RuntimeException("not email");
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
				.availableOrganizations(resolveAvailableOrganizations(userDto))
				.notifyChatPing(savedUser.getNotifyChatPing())
				.notifyTaskChatPing(savedUser.getNotifyTaskChatPing())
				.notifyNewAssignedTask(savedUser.getNotifyNewAssignedTask())
				.notifyTaskNewMessageAssigned(savedUser.getNotifyTaskNewMessageAssigned())
				.notifySlaHalfTimePassed(savedUser.getNotifySlaHalfTimePassed())
				.notifySlaOverdue(savedUser.getNotifySlaOverdue())
				.notifyChatUnansweredTooLong(savedUser.getNotifyChatUnansweredTooLong())
				.notifyChatUnansweredTooLongMinutes(savedUser.getNotifyChatUnansweredTooLongMinutes())
				.notifyDeadlineOverdueBeforeMinutes(savedUser.getNotifyDeadlineOverdueBeforeMinutes())
				.notifyDeadlineOverdueBeforeMinutesEnabled(savedUser.getNotifyDeadlineOverdueBeforeMinutesEnabled())
				.notifyDeadlineOverdue(savedUser.getNotifyDeadlineOverdue())
				.isEnabled(savedUser.getIsEnabled())
				.isAccountNonLocked(savedUser.getIsAccountNonLocked())
				.isAccountNonExpired(savedUser.getIsAccountNonExpired())
				.isCredentialsNonExpired(savedUser.getIsCredentialsNonExpired())
				.build();
		User saved = userRepository.save(user);
		eventPublisher.publish(TriggerType.USER_UPDATED, Map.of("user", saved));
		return saved;
	}


	public User getCurrentUser() {
		var authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
			throw new AccessDeniedException("Пользователь не авторизован");
		}
		String username = authentication.getName();
		return getAllUsersIncludingDisabled().stream()
				.filter(User::isEnabled)
				.filter(it -> it.getUsername().equals(username))
				.findFirst()
				.orElseThrow();
	}


	public User userOnline() {
		User currentUser = getCurrentUser();
		userOnline(currentUser.getId(), currentUser.getUsername());
		return currentUser;
	}


	public void userOnline(Long id, String username) {
		if (id != null) {
			usersOnlineIds.add(id);
			return;
		}
		if (username != null && !username.isBlank()) {
			userRepository.findByUsername(username)
					.map(User::getId)
					.ifPresent(usersOnlineIds::add);
		}
	}


	public void userOffline(@NotNull User user) {
		if (user.getId() != null) {
			usersOnlineIds.remove(user.getId());
		}
	}


	public void userOffline(Long id) {
		if (id != null) {
			usersOnlineIds.remove(id);
		}
	}


	public void userOffline(String username) {
		if (username == null || username.isBlank()) {
			return;
		}
		userRepository.findByUsername(username)
				.map(User::getId)
				.ifPresent(usersOnlineIds::remove);
	}


	public User changePassword(@NotNull Password password) {
		User user = getCurrentUser();
		user.setPassword(passwordEncoder.encode(password.getPassword()));
		userRepository.save(user);
		return user;
	}


	public void deleteUser(Long userId) {
		if (userId == null) {
			throw new IllegalArgumentException("userId must not be null");
		}
		User user = userRepository.findById(userId).orElseThrow();
		if (!user.isEnabled()) {
			userOffline(userId);
			return;
		}
		long enabledUsersCount = getUsers().stream()
				.filter(User::isEnabled)
				.count();
		if (enabledUsersCount <= 1) {
			throw new RuntimeException("users will be empty");
		}
		userOffline(userId);
		user.setIsEnabled(false);
		user.setIsAccountNonLocked(false);
		taskRepository.findAll().stream()
				.filter(task -> task.getExecutor() != null)
				.filter(task -> Objects.equals(task.getExecutor().getId(), userId))
				.forEach(task -> {
					task.setExecutor(null);
					taskRepository.save(task);
				});
		userRepository.save(user);
		eventPublisher.publish(TriggerType.USER_UPDATED, Map.of("user", user));
	}


	public Message sendSupportMessage(Message message) {
		License license = licenseRepository.findAll().getFirst();
		List<Support> supports = supportRepository.findAll();
		Support support;
		if (supports.isEmpty()) {
			support = new Support();
			supportRepository.save(support);
		} else {
			support = supports.getFirst();
		}
		supportFeignClient.newMessage(license.getLicense(), message);
		support.getMessages().add(message);
		messageRepository.save(message);
		return message;
	}


	public void resaveMessage(String license, Message message) {
		if (licenseRepository.findAll().getFirst().getLicense().toString().equals(license)) {
			messageRepository.save(message);
			List<Support> supports = supportRepository.findAll();
			Support support;
			if (supports.isEmpty()) {
				support = new Support();
				supportRepository.save(support);
			} else {
				support = supports.getFirst();
			}
			support.getMessages().add(message);
			supportRepository.save(support);
		}
	}


	public void resetPassword(String username) {
		userRepository.findByUsername(username).ifPresent(user -> {
			UUID license = licenseRepository.findAll().getFirst().getLicense();
			String password = supportFeignClient.resetPassword(license, username);
			user.setPassword(passwordEncoder.encode(password));
			userRepository.save(user);
		});
	}


	private Integer normalizeChatUnansweredTooLongMinutes(Integer minutes) {
		if (minutes == null || minutes < 1) {
			return 30;
		}
		return minutes;
	}


	public UserNotificationSettingsDto getCurrentUserNotificationSettings() {
		User user = getCurrentUser();
		return UserNotificationSettingsDto.builder()
				.notifyChatPing(Boolean.TRUE.equals(user.getNotifyChatPing()))
				.notifyTaskChatPing(Boolean.TRUE.equals(user.getNotifyTaskChatPing()))
				.notifyNewAssignedTask(Boolean.TRUE.equals(user.getNotifyNewAssignedTask()))
				.notifyTaskNewMessageAssigned(Boolean.TRUE.equals(user.getNotifyTaskNewMessageAssigned()))
				.notifySlaHalfTimePassed(Boolean.TRUE.equals(user.getNotifySlaHalfTimePassed()))
				.notifySlaOverdue(Boolean.TRUE.equals(user.getNotifySlaOverdue()))
				.notifyDeadlineOverdue(Boolean.TRUE.equals(user.getNotifyDeadlineOverdue()))
				.notifyChatUnansweredTooLong(Boolean.TRUE.equals(user.getNotifyChatUnansweredTooLong()))
				.notifyChatUnansweredTooLongMinutes(normalizePositiveMinutes(user.getNotifyChatUnansweredTooLongMinutes(), 30))
				.notifyDeadlineOverdueBeforeMinutesEnabled(Boolean.TRUE.equals(user.getNotifyDeadlineOverdueBeforeMinutesEnabled()))
				.notifyDeadlineOverdueBeforeMinutes(normalizePositiveMinutes(user.getNotifyDeadlineOverdueBeforeMinutes(), 30))
				.build();
	}


	public UserNotificationSettingsDto updateCurrentUserNotificationSettings(UserNotificationSettingsDto dto) {
		User user = getCurrentUser();
		Integer slaOverdueBeforeMinutes = normalizePositiveMinutes(
				dto.getNotifyDeadlineOverdueBeforeMinutes(),
				30
		);
		Integer chatUnansweredTooLongMinutes = normalizePositiveMinutes(
				dto.getNotifyChatUnansweredTooLongMinutes(),
				30
		);
		user.setNotifyChatPing(Boolean.TRUE.equals(dto.getNotifyChatPing()));
		user.setNotifyTaskChatPing(Boolean.TRUE.equals(dto.getNotifyTaskChatPing()));
		user.setNotifyNewAssignedTask(Boolean.TRUE.equals(dto.getNotifyNewAssignedTask()));
		user.setNotifyTaskNewMessageAssigned(Boolean.TRUE.equals(dto.getNotifyTaskNewMessageAssigned()));
		user.setNotifySlaHalfTimePassed(Boolean.TRUE.equals(dto.getNotifySlaHalfTimePassed()));
		user.setNotifySlaOverdue(Boolean.TRUE.equals(dto.getNotifySlaOverdue()));
		user.setNotifyDeadlineOverdueBeforeMinutes(slaOverdueBeforeMinutes);
		user.setNotifyDeadlineOverdueBeforeMinutesEnabled(Boolean.TRUE.equals(dto.getNotifyDeadlineOverdueBeforeMinutesEnabled()));
		user.setNotifyChatUnansweredTooLong(Boolean.TRUE.equals(dto.getNotifyChatUnansweredTooLong()));
		user.setNotifyChatUnansweredTooLongMinutes(chatUnansweredTooLongMinutes);
		user.setNotifyDeadlineOverdue(Boolean.TRUE.equals(dto.getNotifyDeadlineOverdue()));
		User saved = userRepository.save(user);
		return UserNotificationSettingsDto.builder()
				.notifyChatPing(Boolean.TRUE.equals(saved.getNotifyChatPing()))
				.notifyTaskChatPing(Boolean.TRUE.equals(saved.getNotifyTaskChatPing()))
				.notifyNewAssignedTask(Boolean.TRUE.equals(saved.getNotifyNewAssignedTask()))
				.notifyTaskNewMessageAssigned(Boolean.TRUE.equals(saved.getNotifyTaskNewMessageAssigned()))
				.notifySlaHalfTimePassed(Boolean.TRUE.equals(saved.getNotifySlaHalfTimePassed()))
				.notifySlaOverdue(Boolean.TRUE.equals(saved.getNotifySlaOverdue()))
				.notifyDeadlineOverdue(Boolean.TRUE.equals(saved.getNotifyDeadlineOverdue()))
				.notifyChatUnansweredTooLong(Boolean.TRUE.equals(saved.getNotifyChatUnansweredTooLong()))
				.notifyChatUnansweredTooLongMinutes(normalizePositiveMinutes(saved.getNotifyChatUnansweredTooLongMinutes(), 30))
				.notifyDeadlineOverdueBeforeMinutesEnabled(Boolean.TRUE.equals(saved.getNotifyDeadlineOverdueBeforeMinutesEnabled()))
				.notifyDeadlineOverdueBeforeMinutes(normalizePositiveMinutes(saved.getNotifyDeadlineOverdueBeforeMinutes(), 30))
				.build();
	}


	public Set<User> getUsersOnline() {
		return new LinkedHashSet<>(userRepository.findAllById(usersOnlineIds));
	}


	public boolean isAdmin(User user) {
		return hasRole(user, Role.ADMIN);
	}


	public boolean isOperator(User user) {
		return hasRole(user, Role.OPERATOR);
	}


	public boolean isCurrentUserAdmin() {
		return isAdmin(getCurrentUser());
	}


	public List<Organization> filterOrganizationsByCurrentUser(Collection<Organization> organizations) {
		if (organizations == null) {
			return List.of();
		}

		User currentUser = getCurrentUser();

		if (isAdmin(currentUser)) {
			return organizations.stream()
					.filter(Objects::nonNull)
					.toList();
		}

		if (!isOperator(currentUser)) {
			return List.of();
		}

		Set<Long> allowedOrganizationIds = getAvailableOrganizationIds(currentUser);

		return organizations.stream()
				.filter(Objects::nonNull)
				.filter(organization -> organization.getId() != null)
				.filter(organization -> allowedOrganizationIds.contains(organization.getId()))
				.toList();
	}


	public List<Client> filterClientsByCurrentUser(Collection<Client> clients) {
		return filterClientsByUser(clients, getCurrentUser());
	}


	public void assertCurrentUserCanAccessOrganizationId(Long organizationId) throws AccessDeniedException {
		User currentUser = getCurrentUser();
		if (isAdmin(currentUser)) {
			return;
		}
		if (!canUserAccessOrganizationId(currentUser, organizationId)) {
			throw new AccessDeniedException("Нет доступа к организации");
		}
	}


	public void assertCurrentUserCanAccessClient(Client client) throws AccessDeniedException {
		User currentUser = getCurrentUser();
		if (isAdmin(currentUser)) {
			return;
		}
		if (!canUserAccessClient(currentUser, client)) {
			throw new AccessDeniedException("Нет доступа к клиенту");
		}
	}


	public void assertUserCanAccessClient(User user, Client client) throws AccessDeniedException {
		if (isAdmin(user)) {
			return;
		}
		if (!canUserAccessClient(user, client)) {
			throw new AccessDeniedException("Нет доступа к клиенту");
		}
	}


	private boolean canUserAccessClient(User user, Client client) {
		if (client == null || client.getOrganization() == null) {
			return false;
		}
		return canUserAccessOrganizationId(user, client.getOrganization().getId());
	}


	private boolean canUserAccessOrganizationId(User user, Long organizationId) {
		if (user == null || organizationId == null) {
			return false;
		}
		if (isAdmin(user)) {
			return true;
		}
		if (!isOperator(user)) {
			return false;
		}
		return getAvailableOrganizationIds(user).contains(organizationId);
	}


	private Set<Long> getAvailableOrganizationIds(User user) {
		if (user == null || user.getAvailableOrganizations() == null) {
			return Set.of();
		}
		return user.getAvailableOrganizations().stream()
				.filter(Objects::nonNull)
				.map(Organization::getId)
				.filter(Objects::nonNull)
				.collect(java.util.stream.Collectors.toSet());
	}


	private boolean hasRole(User user, Role role) {
		if (user == null || role == null || user.getAuthorities() == null) {
			return false;
		}
		return user.getAuthorities().stream()
				.filter(Objects::nonNull)
				.anyMatch(authority -> Objects.equals(authority.getAuthority(), role.getAuthority()));
	}


	private List<Organization> resolveAvailableOrganizations(UserDto userDto) {
		if (userDto == null) {
			return List.of();
		}
		List<Organization> organizations = organizationService.getOrganizations();
		if (userDto.getAvailableOrganizationIds() != null && !userDto.getAvailableOrganizationIds().isEmpty()) {
			Set<Long> ids = userDto.getAvailableOrganizationIds().stream()
					.filter(Objects::nonNull)
					.collect(java.util.stream.Collectors.toSet());
			return organizations.stream()
					.filter(organization -> organization.getId() != null)
					.filter(organization -> ids.contains(organization.getId()))
					.toList();
		}
		if (userDto.getAvailableOrganizations() == null || userDto.getAvailableOrganizations().isEmpty()) {
			return List.of();
		}
		Set<String> names = userDto.getAvailableOrganizations().stream()
				.filter(Objects::nonNull)
				.map(String::trim)
				.filter(value -> !value.isBlank())
				.collect(java.util.stream.Collectors.toSet());
		return organizations.stream()
				.filter(organization -> names.contains(organization.getName()))
				.toList();
	}


	private Integer normalizePositiveMinutes(Integer minutes, Integer defaultValue) {
		if (minutes == null || minutes < 1) {
			return defaultValue;
		}
		return minutes;
	}


	public User getUserByUsername(String username) {
		if (username == null || username.isBlank()) {
			throw new NoSuchElementException("username is empty");
		}
		return userRepository.findByUsername(username).orElseThrow();
	}


	public List<Client> filterClientsByUser(Collection<Client> clients, User user) {
		if (clients == null) {
			return List.of();
		}
		if (isAdmin(user)) {
			return clients.stream()
					.filter(Objects::nonNull)
					.toList();
		}
		if (!isOperator(user)) {
			return List.of();
		}
		Set<Long> allowedOrganizationIds = getAvailableOrganizationIds(user);
		return clients.stream()
				.filter(Objects::nonNull)
				.filter(client -> client.getOrganization() != null)
				.filter(client -> client.getOrganization().getId() != null)
				.filter(client -> allowedOrganizationIds.contains(client.getOrganization().getId()))
				.toList();
	}

}