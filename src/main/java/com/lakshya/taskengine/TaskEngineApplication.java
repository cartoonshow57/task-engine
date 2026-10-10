package com.lakshya.taskengine;

import org.springframework.boot.SpringApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableScheduling
public class TaskEngineApplication {

	public static void main(String[] args) {
		SpringApplication.run(TaskEngineApplication.class, args);
	}

}
