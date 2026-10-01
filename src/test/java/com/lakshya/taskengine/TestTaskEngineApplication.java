package com.lakshya.taskengine;

import org.springframework.boot.SpringApplication;

public class TestTaskEngineApplication {

	public static void main(String[] args) {
		SpringApplication.from(TaskEngineApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
