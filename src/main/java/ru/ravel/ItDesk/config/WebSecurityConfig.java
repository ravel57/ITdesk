package ru.ravel.ItDesk.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.LogoutConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import ru.ravel.ItDesk.reposetory.UserRepository;
import ru.ravel.ItDesk.service.AuthService;


@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class WebSecurityConfig {

	@Autowired
	UserRepository repository;


	@Bean
	UserDetailsService userDetailsService() {
		return new AuthService(repository);
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder(12);
	}

	@Bean
	AuthenticationProvider authenticationProvider() {
		DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
		provider.setUserDetailsService(userDetailsService());
		provider.setPasswordEncoder(passwordEncoder());
		return provider;
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
		requestCache.setMatchingRequestParameterName(null);
		return http.csrf(AbstractHttpConfigurer::disable)
				.authorizeHttpRequests(requests -> requests
						.requestMatchers("/js/**", "/css/**").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/**").permitAll()
						.anyRequest().permitAll())
//				.formLogin(form -> form.loginPage("/login").permitAll())
//				.formLogin(Customizer.withDefaults())
//				.httpBasic(Customizer.withDefaults())
				.formLogin(form -> form.defaultSuccessUrl("/", true))
				.logout(LogoutConfigurer::permitAll)
//						.logoutSuccessUrl("/logout")
//						.invalidateHttpSession(true)
//						.deleteCookies("JSESSIONID")
//						.permitAll())
				.requestCache(cache -> cache.requestCache(requestCache))
				.build();
	}
}
