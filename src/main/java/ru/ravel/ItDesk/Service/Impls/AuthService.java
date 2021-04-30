package ru.ravel.ItDesk.Service.Impls;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.DAO.Impls.SupporterDAO;
import ru.ravel.ItDesk.Models.Supporter;

import java.util.HashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class AuthService {

    private final SupporterDAO supporterDAO;

    public Supporter authorizeUser(String login, String password) {
        login = login.trim();
        password = password.trim();
        BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

        Set<GrantedAuthority> roles = new HashSet<>();
        roles.add(new SimpleGrantedAuthority("USER"));

        Supporter supporter = supporterDAO.getUserByLoginAndPasswordOrReturnNull(login, password);
        if (supporter != null) {
            Authentication auth = new UsernamePasswordAuthenticationToken(login, passwordEncoder.encode(password), roles);
            SecurityContextHolder.getContext().setAuthentication(auth);
//            SecurityContext sc = SecurityContextHolder.getContext();
//            System.out.println();
        }
        return supporter;
    }
}
