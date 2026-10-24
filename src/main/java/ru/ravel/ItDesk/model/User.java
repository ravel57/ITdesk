package ru.ravel.ItDesk.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;


@Entity
@Table(name = "user_t")
@DiscriminatorColumn(name = "type", discriminatorType = DiscriminatorType.STRING)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class User implements UserDetails, Comparable<User>, Serializable {
	@Serial
	private static final long serialVersionUID = -4988500411217727913L;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	protected Long id;

	protected String firstname;

	protected String lastname;

	protected String username;

	@JsonIgnore
	protected String password;

	@ElementCollection(fetch = FetchType.EAGER)
	@Enumerated(EnumType.STRING)
	@CollectionTable(name = "user_authorities", joinColumns = @JoinColumn(name = "user_id"))
	@Column(name = "authorities")
	protected List<Role> authorities;

	@ManyToMany(fetch = FetchType.EAGER)
	protected List<Organization> availableOrganizations;

	protected Long clientId;

	@Builder.Default
	@JsonIgnore
	protected Boolean isAccountNonExpired = true;

	@Builder.Default
	@JsonIgnore
	protected Boolean isAccountNonLocked = true;

	@Builder.Default
	@JsonIgnore
	protected Boolean isCredentialsNonExpired = true;

	@Builder.Default
	protected Boolean isEnabled = true;

	/** Создан без пароля администратором: ждёт перехода по ссылке-приглашению, чтобы задать пароль. */
	@Builder.Default
	@Column(name = "invite_pending", nullable = false, columnDefinition = "boolean default false")
	protected Boolean invitePending = false;

	/** Двухфакторная аутентификация (TOTP) включена. Секреты наружу не отдаются. */
	@Builder.Default
	@Column(name = "mfa_enabled", nullable = false, columnDefinition = "boolean default false")
	protected Boolean mfaEnabled = false;

	@JsonIgnore
	@Column(name = "mfa_secret", columnDefinition = "text")
	protected String mfaSecret;

	/** Секрет, который пользователь подключает прямо сейчас: станет mfaSecret после подтверждения кодом. */
	@JsonIgnore
	@Column(name = "mfa_pending_secret", columnDefinition = "text")
	protected String mfaPendingSecret;

	/** SHA-256 неиспользованных кодов восстановления через запятую. */
	@JsonIgnore
	@Column(name = "mfa_recovery_codes", columnDefinition = "text")
	protected String mfaRecoveryCodes;

	/** Последний принятый 30-секундный интервал TOTP: один и тот же код дважды не принимается. */
	@JsonIgnore
	@Column(name = "mfa_last_step")
	protected Long mfaLastStep;

	@JsonIgnore
	@Column(name = "mfa_enabled_at")
	protected java.time.ZonedDateTime mfaEnabledAt;

	@Builder.Default
	@Column(name = "notify_chat_ping", nullable = false, columnDefinition = "boolean default false")
	protected Boolean notifyChatPing = false;

	@Builder.Default
	@Column(name = "notify_portal_messages", nullable = false, columnDefinition = "boolean default false")
	protected Boolean notifyPortalMessages = false;

	@Builder.Default
	@Column(name = "notify_task_chat_ping", nullable = false, columnDefinition = "boolean default false")
	protected Boolean notifyTaskChatPing = false;

	@Builder.Default
	@Column(name = "notify_new_assigned_task", nullable = false, columnDefinition = "boolean default false")
	protected Boolean notifyNewAssignedTask = false;

	@Builder.Default
	@Column(name = "notify_task_new_message_assigned", nullable = false, columnDefinition = "boolean default false")
	protected Boolean notifyTaskNewMessageAssigned = false;

	@Builder.Default
	@Column(name = "notify_sla_half_time_passed", nullable = false, columnDefinition = "boolean default false")
	protected Boolean notifySlaHalfTimePassed = false;

	@Builder.Default
	@Column(name = "notify_sla_overdue", nullable = false, columnDefinition = "boolean default false")
	protected Boolean notifySlaOverdue = false;

	@Builder.Default
	@Column(name = "notify_chat_unanswered_too_long", nullable = false, columnDefinition = "boolean default false")
	protected Boolean notifyChatUnansweredTooLong = false;

	@Builder.Default
	@Column(name = "notify_chat_unanswered_too_long_minutes", nullable = false, columnDefinition = "int default 30")
	protected Integer notifyChatUnansweredTooLongMinutes = 30;

	@Builder.Default
	@Column(name = "notify_deadline_overdue_before_minutes_enabled", nullable = false, columnDefinition = "boolean default false")
	protected Boolean notifyDeadlineOverdueBeforeMinutesEnabled = false;

	@Builder.Default
	@Column(name = "notify_deadline_overdue_before_minutes", nullable = false, columnDefinition = "int default 30")
	protected Integer notifyDeadlineOverdueBeforeMinutes = 30;

	@Builder.Default
	@Column(name = "notify_deadline_overdue", nullable = false, columnDefinition = "boolean default false")
	protected Boolean notifyDeadlineOverdue = false;

	/** Прошёл приветственный мастер первого входа (/welcome) хотя бы раз; раньше хранилось в localStorage браузера. */
	@Builder.Default
	@Column(name = "welcome_seen", nullable = false, columnDefinition = "boolean default false")
	protected Boolean welcomeSeen = false;

	/**
	 * Какие поля показывать в карточке заявки и в каком порядке — личная настройка пользователя;
	 * раньше хранилось в localStorage браузера под ключом taskCardConfig. null — использовать
	 * настройки по умолчанию (все поля, порядок как во фронтовом справочнике TASK_CARD_FIELDS).
	 */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "task_card_fields_config", columnDefinition = "jsonb")
	protected List<TaskCardFieldConfig> taskCardFieldsConfig;

	/**
	 * Какие подсказки-туры на отдельных страницах (заявки, карточка заявки, чаты и т.д.) уже пройдены
	 * или пропущены — ключ тура → "done"/"skipped". Раньше каждая страница хранила свой ключ в localStorage.
	 */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "onboarding_progress", columnDefinition = "jsonb")
	protected Map<String, String> onboardingProgress;


	/** User сам Serializable (хранится в HttpSession как principal) — вложенные типы обязаны быть тоже. */
	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class TaskCardFieldConfig implements Serializable {
		@Serial
		private static final long serialVersionUID = 1L;
		String key;
		Boolean enabled;
	}


	@Override
	public String getUsername() {
		return username;
	}


	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return authorities;
	}


	@Override
	public String getPassword() {
		return password;
	}


	@Override
	public boolean isEnabled() {
		return !Boolean.FALSE.equals(isEnabled);
	}


	@Override
	public boolean isAccountNonExpired() {
		return !Boolean.FALSE.equals(isAccountNonExpired);
	}


	@Override
	public boolean isAccountNonLocked() {
		return !Boolean.FALSE.equals(isAccountNonLocked);
	}


	@Override
	public boolean isCredentialsNonExpired() {
		return !Boolean.FALSE.equals(isCredentialsNonExpired);
	}


	@Override
	public int compareTo(@NotNull User o) {
		return id.compareTo(o.id);
	}
}
