package com.example.defusion_bcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DefusionBcpApplication {

	public static void main(String[] args) {
		SpringApplication.run(DefusionBcpApplication.class, args);
	}

}
