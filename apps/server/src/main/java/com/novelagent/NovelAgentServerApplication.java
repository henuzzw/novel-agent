package com.novelagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class NovelAgentServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(NovelAgentServerApplication.class, args);
	}

}
