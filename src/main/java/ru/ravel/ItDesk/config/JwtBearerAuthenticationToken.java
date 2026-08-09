package ru.ravel.ItDesk.config;

import lombok.Getter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.service.JwtAuthenticationResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Getter
public final class JwtBearerAuthenticationToken extends UsernamePasswordAuthenticationToken {

	private final Long tokenId;
	private final String jti;
	private final String tokenName;
	private final Set<String> scopes;

	public JwtBearerAuthenticationToken(JwtAuthenticationResult result) {
		super(result.user(), null, authorities(result));
		this.tokenId = result.tokenId();
		this.jti = result.jti();
		this.tokenName = result.tokenName();
		this.scopes = Set.copyOf(result.scopes());
	}

	public boolean hasScope(String scope) {
		return scopes.contains(scope);
	}

	private static List<GrantedAuthority> authorities(JwtAuthenticationResult result) {
		List<GrantedAuthority> authorities = new ArrayList<>(result.user().getAuthorities());
		result.scopes().stream()
				.map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope))
				.forEach(authorities::add);
		return authorities;
	}
}
