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
@Table(name = "t_user")
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
	private Long id;

	private String firstname;

	private String lastname;

	private String username;

	@JsonIgnore
	private String password;

	@ElementCollection(fetch = FetchType.EAGER)
	@Enumerated(EnumType.ORDINAL)
	@CollectionTable(name = "user_authorities", joinColumns = @JoinColumn(name = "user_id"))
	@Column(name = "authorities")
	private List<Role> authorities;

	@ManyToMany(fetch = FetchType.EAGER)
	private List<Organization> availableOrganizations;

	@Builder.Default
	@JsonIgnore
	private boolean isAccountNonExpired = true;

	@Builder.Default
	@JsonIgnore
	private boolean isAccountNonLocked = true;

	@Builder.Default
	@JsonIgnore
	private boolean isCredentialsNonExpired = true;

	@Builder.Default
	private boolean isEnabled = true;


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
		return isEnabled;
	}


	@Override
	public boolean isAccountNonExpired() {
		return isAccountNonExpired;
	}


	@Override
	public boolean isAccountNonLocked() {
		return isAccountNonLocked;
	}


	@Override
	public boolean isCredentialsNonExpired() {
		return isCredentialsNonExpired;
	}


	@Override
	public int compareTo(@NotNull User o) {
		return id.compareTo(o.id);
	}
}
