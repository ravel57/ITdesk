package ru.ravel.ItDesk;

import org.junit.Assert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.ravel.ItDesk.DAO.Impls.SupporterDAO;
import ru.ravel.ItDesk.Models.Supporter;
import ru.ravel.ItDesk.Service.Impls.AuthService;

import java.util.HashMap;
import java.util.Map;

@SpringBootTest
class ItDeskApplicationTests {

    @Autowired
    AuthService authService;

    private static final Map<Object, Object> qwe = new HashMap<>();

    @BeforeAll
    private static void init() {
        qwe.put("plomakin", "govnoed123");
        qwe.put("plomakin", "govnoed");
    }

    @Test
    void contextLoads() {
        qwe.forEach((o, o2) -> {
            Supporter s = authService.authorizeUser((String) o, (String) o2);
            System.out.println(o + "    " + o2);
            Assert.assertEquals(null, s);
        });

    }

}
