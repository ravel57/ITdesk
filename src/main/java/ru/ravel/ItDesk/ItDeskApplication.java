package ru.ravel.ItDesk;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;

@SpringBootApplication
public class ItDeskApplication {

	public static void main(String[] args) {
		ApplicationContext applicationContext = SpringApplication.run(ItDeskApplication.class, args);


//		for (String name : applicationContext.getBeanDefinitionNames()) {
//			System.out.println(name);
//		}
	}

}