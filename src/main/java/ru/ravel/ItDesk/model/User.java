package ru.ravel.ItDesk.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;


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

	@Builder.Default
	@Column(name = "notify_chat_ping", nullable = false)
	protected Boolean notifyChatPing = false;

	@Builder.Default
	@Column(name = "notify_task_chat_ping", nullable = false)
	protected Boolean notifyTaskChatPing = false;

	@Builder.Default
	@Column(name = "notify_new_assigned_task", nullable = false)
	protected Boolean notifyNewAssignedTask = false;

	@Builder.Default
	@Column(name = "notify_task_new_message_assigned", nullable = false)
	protected Boolean notifyTaskNewMessageAssigned = false;

	@Builder.Default
	@Column(name = "notify_sla_half_time_passed", nullable = false)
	protected Boolean notifySlaHalfTimePassed = false;

	@Builder.Default
	@Column(name = "notify_sla_overdue", nullable = false)
	protected Boolean notifySlaOverdue = false;

	@Builder.Default
	@Column(name = "notify_chat_unanswered_too_long", nullable = false)
	protected Boolean notifyChatUnansweredTooLong = false;


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
