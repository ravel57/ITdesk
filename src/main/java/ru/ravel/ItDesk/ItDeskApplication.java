package ru.ravel.ItDesk;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import ru.ravel.ItDesk.Controllers.TelegramBotController;

import java.io.FileNotFoundException;
import java.io.IOException;

@SpringBootApplication
public class ItDeskApplication {

	public static void main(String[] args) throws IOException {
		ApplicationContext applicationContext = SpringApplication.run(ItDeskApplication.class, args);


		//TelegramBotController telegramBotController = new TelegramBotController();


//		for (String name : applicationContext.getBeanDefinitionNames()) {
//			System.out.println(name);
//		}
	}

}
