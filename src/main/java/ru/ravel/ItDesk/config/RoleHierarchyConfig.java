package ru.ravel.ItDesk.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import ru.ravel.ItDesk.model.RolePermissions;


/**
 * Иерархия ролей для URL-правил и hasRole(): ADMIN > MANAGER > SENIOR_OPERATOR > OPERATOR.
 * Spring Security подхватывает бин и в authorizeHttpRequests, и в @PreAuthorize.
 */
@Configuration
public class RoleHierarchyConfig {

	@Bean
	static RoleHierarchy roleHierarchy() {
		return RoleHierarchyImpl.fromHierarchy(RolePermissions.HIERARCHY);
	}

}
