package ru.ravel.ItDesk;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.ravel.ItDesk.service.AuthService;

@SpringBootTest
class ItDeskApplicationTests {

	@Autowired
	AuthService authService;
}
