package ru.ravel.ItDesk.Controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.ravel.ItDesk.Service.Impls.AuthService;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.text.SimpleDateFormat;
import java.util.Date;


@Controller
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class AuthController {

    private final AuthService authorize;

    @GetMapping(value = "/login")
    public String getLoginPage() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof AnonymousAuthenticationToken) {
            return "login";
        } else
            return "redirect:/dialogs";
    }

    //    ResponseEntity<Object>
    @PostMapping(value = "/login")
    public String auth(
            @RequestParam("username") String login,
            @RequestParam("password") String password
    ) {
        if (authorize.authorizeUser(login, password) != null) {
            return "redirect:/dialogs";
//            return ResponseEntity.status(HttpStatus.OK).body(authorize.authorizeUser(login, password));
        } else {
            SimpleDateFormat formatter= new SimpleDateFormat("yyyy-MM-dd 'at' HH:mm:ss z");
            System.out.println( new StringBuilder().append(formatter.format(new Date(System.currentTimeMillis())))
                    .append("  failed login attempt. l: ").append(login).append(" p: ").append(password));
            return "login";
//            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(null);
        }
    }

    @PostMapping("/logout")
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextLogoutHandler securityContextHolder = new SecurityContextLogoutHandler();
        securityContextHolder.logout(request, response, null);
    }
}
