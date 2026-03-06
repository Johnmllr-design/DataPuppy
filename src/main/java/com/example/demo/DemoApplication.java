package com.example.demo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.github.alexdlaird.ngrok.NgrokClient;
import com.github.alexdlaird.ngrok.conf.JavaNgrokConfig;
import com.github.alexdlaird.ngrok.protocol.CreateTunnel;
import com.github.alexdlaird.ngrok.protocol.Tunnel;

import io.github.cdimascio.dotenv.Dotenv;

@SpringBootApplication
public class DemoApplication {

	public static void main(String[] args) {
		SpringApplication.run(DemoApplication.class, args);
		final Dotenv env = Dotenv.load();
		
		// Start ngrok
		JavaNgrokConfig config  = new JavaNgrokConfig.Builder().withAuthToken(env.get("ngrok")).build();
        NgrokClient ngrokClient = new NgrokClient.Builder().withJavaNgrokConfig(config).build();
        Tunnel tunnel = ngrokClient.connect(new CreateTunnel.Builder().withAddr(8080).build());

        String publicUrl = tunnel.getPublicUrl();
        System.out.println("🌍 ngrok URL: " + publicUrl);

        // Pass it straight into EventBridge setup
        AmazonAPIcaller caller = new AmazonAPIcaller();
		caller.makeAPIrule(publicUrl);
	}
}