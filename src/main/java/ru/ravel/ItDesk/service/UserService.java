package ru.ravel.ItDesk.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.component.LicenseStarter;
import ru.ravel.ItDesk.dto.Password;
import ru.ravel.ItDesk.dto.UserDto;
import ru.ravel.ItDesk.dto.UserNotificationSettingsDto;
import ru.ravel.ItDesk.feign.SupportFeignClient;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.model.automatosation.TriggerType;
import ru.ravel.ItDesk.repository.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


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
	private final ClientRepository clientRepository;
	private final PasswordResetService passwordResetService;
	private final SupportLineRepository supportLineRepository;

	private final Logger logger = LoggerFactory.getLogger(this.getClass());

	@Getter
	private final Set<Long> usersOnlineIds = ConcurrentHashMap.newKeySet();
	private final TaskRepository taskRepository;
	private final EventPublisher eventPublisher;
	private final GlobalSearchService globalSearchService;
	private final AppSettingsService appSettingsService;
	private final SecurityAuditService securityAuditService;
	/**
	 * Не final и внедряется отдельно (не через {@code @RequiredArgsConstructor}): UserSessionService сам
	 * зависит от UserService, и {@code @Lazy} на поле конструктора Lombok не копирует — нужен сеттер/поле.
	 */
	@Lazy
	@Autowired
	private UserSessionService userSessionService;


	public List<User> getUsers() {
		return userRepository.findAll().stream()
				.filter(user -> !Objects.equals(user, SystemUser.getInstance()))
				.filter(User::isEnabled)
				.filter(user -> !isClient(user))
				.toList();
	}


	public List<User> getManageableUsers() {
		return userRepository.findAll().stream()
				.filter(user -> !Objects.equals(user, SystemUser.getInstance()))
				.filter(User::isEnabled)
				.toList();
	}


	public long getLicensedUsersCount() {
		return getManageableUsers().stream()
				.filter(this::consumesLicense)
				.count();
	}


	public List<User> getAllUsersIncludingDisabled() {
		return userRepository.findAll().stream()
				.filter(user -> !Objects.equals(user, SystemUser.getInstance()))
				.toList();
	}


	public List<Role> getRoles() {
		return Arrays.stream(Role.values()).toList();
	}


	@Transactional
	public User newUser(@NotNull UserDto userDto) {
		Role requestedRole = Role.getByName(userDto.getAuthorities());

		Pattern pattern = Pattern.compile("^[a-zA-Z0-9._-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");
		String username = Objects.toString(userDto.getUsername(), "").trim();
		if (!pattern.matcher(username).matches()) {
			throw new RuntimeException("not email");
		}

		boolean passwordProvided = userDto.getPassword() != null && !userDto.getPassword().isBlank();
		SupportLine selectedSupportLine = resolveNewUserSupportLine(userDto.getSupportLineId(), requestedRole);

		User existingUser = userRepository.findByUsername(username).orElse(null);
		validateLicenseSeatAvailable(requestedRole, existingUser != null && existingUser.isEnabled() && consumesLicense(existingUser));
		Long clientId = resolveClientIdForNewUser(userDto, requestedRole, existingUser);
		syncClientOrganization(clientId, requestedRole, userDto);
		if (existingUser != null) {
			existingUser.setFirstname(userDto.getFirstname());
			existingUser.setLastname(userDto.getLastname());
			existingUser.setAuthorities(List.of(requestedRole));
			existingUser.setAvailableOrganizations(resolveAvailableOrganizations(userDto));
			existingUser.setClientId(clientId);
			existingUser.setIsEnabled(true);
			existingUser.setIsAccountNonLocked(true);
			if (passwordProvided) {
				validateRawPassword(userDto.getPassword());
				existingUser.setPassword(passwordEncoder.encode(userDto.getPassword()));
				existingUser.setInvitePending(false);
			} else {
				existingUser.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
				existingUser.setInvitePending(true);
			}
//			User saved = userRepository.save(existingUser);
			addNewUserToSupportLine(existingUser, selectedSupportLine);
			globalSearchService.indexUser(existingUser);
			audit(SecurityAuditEventType.USER_CREATED, existingUser.getUsername(), "Повторно включён, роль: " + requestedRole.getName());
			if (!passwordProvided) {
				passwordResetService.requestInviteLink(existingUser.getUsername());
			}
			return existingUser;
		}

		try {
			String rawPassword;
			boolean invitePending = !passwordProvided;
			if (passwordProvided) {
				rawPassword = userDto.getPassword();
				validateRawPassword(rawPassword);
			} else {
				rawPassword = UUID.randomUUID().toString();
			}
			User user = User.builder()
					.username(username)
					.firstname(userDto.getFirstname())
					.lastname(userDto.getLastname())
					.authorities(List.of(requestedRole))
					.password(passwordEncoder.encode(rawPassword))
					.availableOrganizations(resolveAvailableOrganizations(userDto))
					.clientId(clientId)
					.isEnabled(true)
					.isAccountNonLocked(true)
					.isAccountNonExpired(true)
					.isCredentialsNonExpired(true)
					.invitePending(invitePending)
					.build();
			User saved = userRepository.save(user);
			addNewUserToSupportLine(saved, selectedSupportLine);
			globalSearchService.indexUser(saved);
			eventPublisher.publish(TriggerType.USER_CREATED, Map.of("user", saved));
			audit(SecurityAuditEventType.USER_CREATED, saved.getUsername(), "Роль: " + requestedRole.getName());
			if (invitePending) {
				passwordResetService.requestInviteLink(saved.getUsername());
			}
			return saved;
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
			throw new RuntimeException(e);
		}
	}


	private SupportLine resolveNewUserSupportLine(Long supportLineId, Role role) {
		if (supportLineId == null) {
			return null;
		}
		if (!role.consumesLicense()) {
			throw new IllegalArgumentException("Участником линии может быть только сотрудник поддержки");
		}
		SupportLine line = supportLineRepository.findById(supportLineId)
				.orElseThrow(() -> new IllegalArgumentException("Линия поддержки не найдена"));
		if (!Boolean.TRUE.equals(line.getActive())) {
			throw new IllegalArgumentException("Линия поддержки отключена");
		}
		return line;
	}


	private void addNewUserToSupportLine(User user, SupportLine line) {
		if (line == null) {
			return;
		}
		List<User> members = new ArrayList<>(Objects.requireNonNullElse(line.getMembers(), List.of()));
		if (members.stream().noneMatch(member -> Objects.equals(member.getId(), user.getId()))) {
			members.add(user);
			line.setMembers(members);
			if (line.getObservers() != null) {
				line.getObservers().removeIf(observer -> Objects.equals(observer.getId(), user.getId()));
			}
			supportLineRepository.save(line);
		}
	}


	/** Повторно отправить приглашение задать пароль; только для пользователей, которые ещё не активировали учётную запись. */
	public void resendInvite(Long userId) {
		User user = userRepository.findById(userId).orElseThrow();
		if (!Boolean.TRUE.equals(user.getInvitePending())) {
			throw new IllegalStateException("Пользователь уже задал пароль");
		}
		passwordResetService.requestInviteLink(user.getUsername());
	}


	public User updateUser(@NotNull UserDto userDto) {
		User savedUser = userRepository.findById(userDto.getId()).orElseThrow();
		Role requestedRole = Role.getByName(userDto.getAuthorities());
		validateLicenseSeatAvailable(requestedRole, savedUser.isEnabled() && consumesLicense(savedUser));
		Long clientId = resolveClientIdForUpdate(savedUser, userDto, requestedRole);
		syncClientOrganization(clientId, requestedRole, userDto);
		User user = User.builder()
				.id(userDto.getId())
				.firstname(userDto.getFirstname())
				.lastname(userDto.getLastname())
				.authorities(List.of(requestedRole))
				.username(savedUser.getUsername())
				.password(savedUser.getPassword())
				.availableOrganizations(resolveAvailableOrganizations(userDto))
				.clientId(clientId)
				.notifyChatPing(savedUser.getNotifyChatPing())
				.notifyPortalMessages(savedUser.getNotifyPortalMessages())
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
				.invitePending(savedUser.getInvitePending())
				.mfaEnabled(savedUser.getMfaEnabled())
				.mfaSecret(savedUser.getMfaSecret())
				.mfaPendingSecret(savedUser.getMfaPendingSecret())
				.mfaRecoveryCodes(savedUser.getMfaRecoveryCodes())
				.mfaLastStep(savedUser.getMfaLastStep())
				.mfaEnabledAt(savedUser.getMfaEnabledAt())
				.welcomeSeen(savedUser.getWelcomeSeen())
				.taskCardFieldsConfig(savedUser.getTaskCardFieldsConfig())
				.onboardingProgress(savedUser.getOnboardingProgress())
				.build();
		User saved = userRepository.save(user);
		globalSearchService.indexUser(saved);
		eventPublisher.publish(TriggerType.USER_UPDATED, Map.of("user", saved));
		String previousRoles = roleNames(savedUser);
		String currentRoles = roleNames(saved);
		if (!previousRoles.equals(currentRoles)) {
			audit(SecurityAuditEventType.USER_ROLE_CHANGED, saved.getUsername(), previousRoles + " → " + currentRoles);
			userSessionService.forceLogout(saved.getUsername(), "ROLE_CHANGED");
		} else {
			audit(SecurityAuditEventType.USER_UPDATED, saved.getUsername(), "Роль: " + currentRoles);
		}
		return saved;
	}


	private static String roleNames(User user) {
		return user.getAuthorities() == null ? "" : user.getAuthorities().stream()
				.filter(Role.class::isInstance)
				.map(role -> ((Role) role).getName())
				.sorted()
				.collect(java.util.stream.Collectors.joining(", "));
	}


	/** В тестах UserService часто создаётся частичным моком без журнала. */
	private void audit(SecurityAuditEventType type, String target, String details) {
		if (securityAuditService != null) {
			securityAuditService.record(type, target, details);
		}
	}


	public User getCurrentUser() {
		var authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
			throw new AccessDeniedException("Пользователь не авторизован");
		}
		String username = authentication.getName();
		return userRepository.findByUsername(username)
				.filter(User::isEnabled)
				.orElseThrow(() -> new AccessDeniedException("Пользователь не найден или отключён"));
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
		String rawPassword = password == null ? null : password.getPassword();
		validateRawPassword(rawPassword);
		user.setPassword(passwordEncoder.encode(rawPassword));
		userRepository.save(user);
		audit(SecurityAuditEventType.PASSWORD_CHANGED, user.getUsername(), null);
		return user;
	}


	public User changeClientPasswordByAdmin(Long userId, String rawPassword) {
		if (userId == null) {
			throw new IllegalArgumentException("Не указан пользователь");
		}
		String password = Objects.toString(rawPassword, "");
		validateRawPassword(password);
		User user = userRepository.findById(userId)
				.orElseThrow(() -> new NoSuchElementException("Пользователь не найден"));
		if (!isClient(user)) {
			throw new IllegalArgumentException("Смена пароля этим методом доступна только для клиентских учётных записей");
		}
		user.setPassword(passwordEncoder.encode(password));
		User saved = userRepository.save(user);
		globalSearchService.indexUser(saved);
		eventPublisher.publish(TriggerType.USER_UPDATED, Map.of("user", saved));
		audit(SecurityAuditEventType.PASSWORD_CHANGED_BY_ADMIN, saved.getUsername(), null);
		userSessionService.forceLogout(saved.getUsername(), "PASSWORD_CHANGED");
		return saved;
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
		long enabledStaffUsersCount = getUsers().stream()
				.filter(User::isEnabled)
				.filter(item -> !isClient(item))
				.count();
		if (!isClient(user) && enabledStaffUsersCount <= 1) {
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
					Task savedTask = taskRepository.save(task);
					clientRepository.findByTaskId(savedTask.getId())
							.ifPresent(client -> globalSearchService.indexTask(client, savedTask));
				});
		User saved = userRepository.save(user);
		globalSearchService.deleteUser(saved.getId());
		eventPublisher.publish(TriggerType.USER_UPDATED, Map.of("user", saved));
		audit(SecurityAuditEventType.USER_DELETED, saved.getUsername(), "Роль: " + roleNames(saved));
		userSessionService.forceLogout(saved.getUsername(), "ACCOUNT_DISABLED");
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


	public static void validateRawPassword(String password) {
		if (password == null || password.length() < 8) {
			throw new IllegalArgumentException("Пароль должен содержать минимум 8 символов");
		}
		if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
			throw new IllegalArgumentException("Пароль слишком длинный (максимум 72 байта UTF-8)");
		}
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
		User user = getUser(dto);
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

	@NotNull
	private User getUser(UserNotificationSettingsDto dto) {
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
		return user;
	}


	/** Пользователь закрыл/прошёл приветственный мастер (/welcome) — больше показывать не нужно. */
	public void markCurrentUserWelcomeSeen() {
		User user = getCurrentUser();
		if (!Boolean.TRUE.equals(user.getWelcomeSeen())) {
			user.setWelcomeSeen(true);
			userRepository.save(user);
		}
	}


	public List<User.TaskCardFieldConfig> updateCurrentUserTaskCardConfig(List<User.TaskCardFieldConfig> config) {
		User user = getCurrentUser();
		user.setTaskCardFieldsConfig(config);
		User saved = userRepository.save(user);
		return saved.getTaskCardFieldsConfig();
	}


	/**
	 * Объединяет переданные пары ключ→статус тура ("done"/"skipped"/…, конкретное значение — на усмотрение
	 * страницы) с уже сохранёнными; остальные туры не трогает. Одним вызовом можно обновить сразу несколько
	 * туров (например "Пропустить всё" на велком-экране) — это один read-modify-write, без гонки между
	 * несколькими параллельными запросами по одному туру за раз.
	 */
	public Map<String, String> updateCurrentUserOnboardingProgress(Map<String, String> updates) {
		if (updates == null || updates.isEmpty()) {
			throw new IllegalArgumentException("updates не указаны");
		}
		User user = getCurrentUser();
		Map<String, String> progress = new LinkedHashMap<>(
				user.getOnboardingProgress() != null ? user.getOnboardingProgress() : Map.of()
		);
		progress.putAll(updates);
		user.setOnboardingProgress(progress);
		User saved = userRepository.save(user);
		return saved.getOnboardingProgress();
	}


	public Set<User> getUsersOnline() {
		List<User> users = userRepository.findAllById(usersOnlineIds);
		return new LinkedHashSet<>(users).stream()
				.filter(user -> !user.getAuthorities().contains(Role.CLIENT))
				.collect(Collectors.toSet());
	}


	public boolean isAdmin(User user) {
		return hasRole(user, Role.ADMIN);
	}


	public boolean isManager(User user) {
		return hasRole(user, Role.MANAGER);
	}


	/**
	 * Линейная поддержка: OPERATOR, SENIOR_OPERATOR и MANAGER (менеджер работает с заявками как оператор,
	 * но без ограничения по организациям). ADMIN проверяется отдельно через {@link #isAdmin(User)}.
	 * Иерархия ролей и права — в {@link ru.ravel.ItDesk.model.RolePermissions}.
	 */
	public boolean isOperator(User user) {
		return hasRole(user, Role.OPERATOR) || isSeniorOperator(user) || isManager(user);
	}


	public boolean isSeniorOperator(User user) {
		return hasRole(user, Role.SENIOR_OPERATOR);
	}


	public boolean isObserver(User user) {
		return hasRole(user, Role.OBSERVER);
	}


	public boolean isClient(User user) {
		return hasRole(user, Role.CLIENT);
	}


	/** Организации, явно назначенные пользователю (User.availableOrganizations). */
	public Set<Long> getAssignedOrganizationIds(User user) {
		return getAvailableOrganizationIds(user);
	}


	/** Внутренний чат заявки (Task.messages) видит только поддержка; менеджеру организации (OBSERVER) он не отдаётся. */
	public boolean canUserSeeTaskChat(User user) {
		return isAdmin(user) || isOperator(user);
	}


	public boolean isCurrentUserAdmin() {
		return isAdmin(getCurrentUser());
	}


	public Set<Long> getAnalyticsOrganizationIds(User user) {
		return getAvailableOrganizationIds(user);
	}


	public boolean hasAnalyticsAccessToAllOrganizations(User user) {
		return isAdmin(user) || isManager(user) || (isOperator(user) && hasAccessToAllOrganizations(user));
	}


	public boolean canUserAccessAnalyticsTask(
			User user,
			Long organizationId,
			SupportLine line,
			Long executorId,
			Collection<Long> accessUserIds
	) {
		if (user == null) {
			return false;
		}
		if (isAdmin(user) || isManager(user)) {
			return true;
		}

		Set<Long> organizationIds = getAvailableOrganizationIds(user);
		if (isObserver(user)) {
			return organizationId != null && organizationIds.contains(organizationId);
		}
		if (!isOperator(user)) {
			return false;
		}
		if (!hasAccessToAllOrganizations(user)
				&& (organizationId == null || !organizationIds.contains(organizationId))) {
			return false;
		}

		SupportLineVisibilityMode lineMode = line == null
				? SupportLineVisibilityMode.INHERIT
				: Objects.requireNonNullElse(line.getVisibilityMode(), SupportLineVisibilityMode.INHERIT);
		if (lineMode == SupportLineVisibilityMode.ALL_OPERATORS) {
			return true;
		}

		boolean member = isSupportLineMember(line, user);
		boolean observer = isSupportLineObserver(line, user);
		if (lineMode == SupportLineVisibilityMode.LINE_MEMBERS) {
			return member;
		}
		if (lineMode == SupportLineVisibilityMode.LINE_MEMBERS_AND_OBSERVERS) {
			return member || observer;
		}

		SupportLineAccessMode globalMode = appSettingsService.getGeneralSettings().getSupportLineAccessMode();
		globalMode = Objects.requireNonNullElse(globalMode, SupportLineAccessMode.HYBRID);
		if (globalMode == SupportLineAccessMode.ALL_OPERATORS) {
			return true;
		}
		if (globalMode == SupportLineAccessMode.LINE_MEMBERS) {
			return member;
		}

		boolean executor = executorId != null && Objects.equals(executorId, user.getId());
		boolean mentioned = accessUserIds != null && accessUserIds.stream()
				.filter(Objects::nonNull)
				.anyMatch(id -> Objects.equals(id, user.getId()));
		return member || observer || executor || mentioned;
	}


	/**
	 * Какие организации видит пользователь — единое правило для всех фильтров по организациям.
	 * ADMIN и MANAGER — все; OPERATOR и SENIOR_OPERATOR — назначенные, а без назначений все;
	 * OBSERVER — только назначенные; CLIENT — организация своей карточки; остальные — ничего.
	 */
	public OrganizationScope organizationScope(User user) {
		if (user == null) {
			return OrganizationScope.NONE;
		}
		if (isAdmin(user) || isManager(user)) {
			return OrganizationScope.ALL;
		}
		if (isObserver(user)) {
			return OrganizationScope.of(getAvailableOrganizationIds(user));
		}
		if (isOperator(user)) {
			Set<Long> assigned = getAvailableOrganizationIds(user);
			return assigned.isEmpty() ? OrganizationScope.ALL : OrganizationScope.of(assigned);
		}
		if (isClient(user) && user.getClientId() != null) {
			return OrganizationScope.of(clientRepository.findById(user.getClientId())
					.map(Client::getOrganization)
					.map(Organization::getId)
					.map(Set::of)
					.orElse(Set.of()));
		}
		return OrganizationScope.NONE;
	}


	public OrganizationScope currentOrganizationScope() {
		return organizationScope(getCurrentUser());
	}


	public List<Organization> filterOrganizationsByCurrentUser(Collection<Organization> organizations) {
		return currentOrganizationScope().filter(organizations, Organization::getId);
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


	public void assertCurrentUserCanAccessTask(Client client, Task task) throws AccessDeniedException {
		User currentUser = getCurrentUser();
		if (!canUserAccessTask(currentUser, client, task)) {
			throw new AccessDeniedException("Нет доступа к заявке");
		}
	}


	public boolean canCurrentUserAccessTask(Client client, Task task) {
		return canUserAccessTask(getCurrentUser(), client, task);
	}


	public boolean canUserAccessTask(User user, Client client, Task task) {
		if (user == null || task == null) {
			return false;
		}
		if (isAdmin(user) || isManager(user)) {
			return true;
		}
		if (isObserver(user)) {
			return canUserAccessClient(user, client);
		}
		if (!isOperator(user) || !canUserAccessClient(user, client)) {
			return false;
		}

		SupportLine line = task.getSupportLine();
		SupportLineVisibilityMode lineMode = line == null
				? SupportLineVisibilityMode.INHERIT
				: Objects.requireNonNullElse(line.getVisibilityMode(), SupportLineVisibilityMode.INHERIT);
		if (lineMode == SupportLineVisibilityMode.ALL_OPERATORS) {
			return true;
		}

		boolean member = isSupportLineMember(line, user);
		boolean observer = isSupportLineObserver(line, user);
		if (lineMode == SupportLineVisibilityMode.LINE_MEMBERS) {
			return member;
		}
		if (lineMode == SupportLineVisibilityMode.LINE_MEMBERS_AND_OBSERVERS) {
			return member || observer;
		}

		SupportLineAccessMode globalMode = appSettingsService.getGeneralSettings().getSupportLineAccessMode();
		globalMode = Objects.requireNonNullElse(globalMode, SupportLineAccessMode.HYBRID);
		if (globalMode == SupportLineAccessMode.ALL_OPERATORS) {
			return true;
		}
		if (globalMode == SupportLineAccessMode.LINE_MEMBERS) {
			return member;
		}

		boolean executor = task.getExecutor() != null && Objects.equals(task.getExecutor().getId(), user.getId());
		boolean mentioned = task.getAccessUsers() != null && task.getAccessUsers().stream()
				.filter(Objects::nonNull)
				.anyMatch(item -> Objects.equals(item.getId(), user.getId()));
		return member || observer || executor || mentioned;
	}


	private boolean isSupportLineMember(SupportLine line, User user) {
		if (line == null || user == null || user.getId() == null) {
			return false;
		}
		if (line.getResponsible() != null && Objects.equals(line.getResponsible().getId(), user.getId())) {
			return true;
		}
		return line.getMembers() != null && line.getMembers().stream()
				.filter(Objects::nonNull)
				.anyMatch(item -> Objects.equals(item.getId(), user.getId()));
	}


	private boolean isSupportLineObserver(SupportLine line, User user) {
		return line != null && user != null && user.getId() != null && line.getObservers() != null
				&& line.getObservers().stream()
				.filter(Objects::nonNull)
				.anyMatch(item -> Objects.equals(item.getId(), user.getId()));
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
		if (client == null || user == null) {
			return false;
		}
		if (isAdmin(user) || isManager(user)) {
			return true;
		}
		if (isClient(user)) {
			return user.getClientId() != null && Objects.equals(user.getClientId(), client.getId());
		}
		return organizationScope(user).allows(client.getOrganization() == null ? null : client.getOrganization().getId());
	}


	private boolean canUserAccessOrganizationId(User user, Long organizationId) {
		return organizationId != null && organizationScope(user).allows(organizationId);
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


	private boolean hasAccessToAllOrganizations(User user) {
		return (isAdmin(user) || isOperator(user)) && organizationScope(user).all();
	}


	private void validateLicenseSeatAvailable(Role requestedRole, boolean alreadyConsumesLicense) {
		if (requestedRole == null || !requestedRole.consumesLicense() || alreadyConsumesLicense) {
			return;
		}

		long maxUsers = Objects.requireNonNullElse(LicenseStarter.maxUsers, Long.MAX_VALUE);
		long licensedUsers = getLicensedUsersCount();
		if (licensedUsers >= maxUsers) {
			logger.info("max licensed users is {}", maxUsers);
			throw new RuntimeException("max users is " + maxUsers);
		}
	}


	private boolean consumesLicense(User user) {
		if (user == null || user.getAuthorities() == null) {
			return false;
		}
		return user.getAuthorities().stream()
				.filter(Role.class::isInstance)
				.map(Role.class::cast)
				.anyMatch(Role::consumesLicense);
	}


	private boolean hasRole(User user, Role role) {
		if (user == null || role == null || user.getAuthorities() == null) {
			return false;
		}
		return user.getAuthorities().stream()
				.filter(Objects::nonNull)
				.anyMatch(authority -> Objects.equals(authority.getAuthority(), role.getAuthority()));
	}


	private Long resolveClientIdForNewUser(UserDto userDto, Role role, User existingUser) {
		if (role != Role.CLIENT) {
			return null;
		}
		resolveRequiredClientOrganization(userDto);
		if (existingUser != null && isClient(existingUser) && existingUser.getClientId() != null) {
			if (userDto.getClientId() != null && !Objects.equals(existingUser.getClientId(), userDto.getClientId())) {
				throw new IllegalArgumentException("Карточку клиента нельзя изменить");
			}
			return existingUser.getClientId();
		}
		if (Boolean.TRUE.equals(userDto.getCreateNewClientCard())) {
			return createClientCardForPortalUser(userDto).getId();
		}
		return resolveExistingClientId(userDto, role);
	}


	private Long resolveClientIdForUpdate(User savedUser, UserDto userDto, Role role) {
		if (role != Role.CLIENT) {
			return null;
		}
		resolveRequiredClientOrganization(userDto);
		if (savedUser != null && isClient(savedUser)) {
			Long savedClientId = savedUser.getClientId();
			if (savedClientId == null) {
				throw new IllegalStateException("У клиентской учётной записи не указана карточка клиента");
			}
			if (userDto.getClientId() != null && !Objects.equals(savedClientId, userDto.getClientId())) {
				throw new IllegalArgumentException("Карточку клиента нельзя изменить");
			}
			return savedClientId;
		}
		return resolveExistingClientId(userDto, role);
	}


	private Long resolveExistingClientId(UserDto userDto, Role role) {
		if (role != Role.CLIENT) {
			return null;
		}
		resolveRequiredClientOrganization(userDto);
		if (userDto == null || userDto.getClientId() == null) {
			throw new IllegalArgumentException("Для роли «Клиент» выберите карточку клиента или включите создание новой");
		}
		return clientRepository.findById(userDto.getClientId())
				.map(Client::getId)
				.orElseThrow(() -> new IllegalArgumentException("Клиент не найден: " + userDto.getClientId()));
	}


	private Client createClientCardForPortalUser(UserDto userDto) {
		if (userDto == null) {
			throw new IllegalArgumentException("Данные пользователя не заданы");
		}
		Client client = Client.builder()
				.messageFrom(MessageFrom.PORTAL)
				.firstname(normalizeNullable(userDto.getFirstname()))
				.lastname(normalizeNullable(userDto.getLastname()))
				.email(normalizeNullable(userDto.getUsername()))
				.organization(resolveRequiredClientOrganization(userDto))
				.build();
		Client savedClient = clientRepository.saveAndFlush(client);
		globalSearchService.indexClient(savedClient);
		eventPublisher.publish(TriggerType.CLIENT_CREATED, Map.of("client", savedClient));
		return savedClient;
	}


	private void syncClientOrganization(Long clientId, Role role, UserDto userDto) {
		if (role != Role.CLIENT || clientId == null) {
			return;
		}
		Organization organization = resolveRequiredClientOrganization(userDto);
		Client client = clientRepository.findById(clientId)
				.orElseThrow(() -> new IllegalArgumentException("Клиент не найден: " + clientId));
		Long currentOrganizationId = client.getOrganization() == null ? null : client.getOrganization().getId();
		if (Objects.equals(currentOrganizationId, organization.getId())) {
			return;
		}
		client.setOrganization(organization);
		Client savedClient = clientRepository.saveAndFlush(client);
		globalSearchService.indexClient(savedClient);
	}


	private Organization resolveRequiredClientOrganization(UserDto userDto) {
		if (userDto == null || userDto.getClientOrganizationId() == null) {
			throw new IllegalArgumentException("Для клиента необходимо выбрать организацию");
		}
		Long organizationId = userDto.getClientOrganizationId();
		return organizationService.getOrganizations().stream()
				.filter(Objects::nonNull)
				.filter(organization -> Objects.equals(organization.getId(), organizationId))
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Организация не найдена: " + organizationId));
	}


	private String normalizeNullable(String value) {
		String normalized = Objects.toString(value, "").trim();
		return normalized.isBlank() ? null : normalized;
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
		if (isClient(user)) {
			return clients.stream()
					.filter(Objects::nonNull)
					.filter(client -> Objects.equals(client.getId(), user.getClientId()))
					.toList();
		}
		return organizationScope(user).filter(clients,
				client -> client.getOrganization() == null ? null : client.getOrganization().getId());
	}


}
