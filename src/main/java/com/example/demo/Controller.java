package com.example.demo;

import java.util.Map;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.web.bind.annotation.GetMapping;



@RestController
public class Controller {

    private static final Dotenv env = Dotenv.load();

    public Controller(){}

    @PostMapping("/aws/events")
    public ResponseEntity<String> AWSEventEndpoint(@RequestHeader("x-api-key") String key, @RequestBody Map<String, Object> body){
        System.out.println("we got an event that happened!!");
        if (key.equals(env.get("x-api-key"))) {
            System.out.println("AWS event occured, printing out event occurence: ");
            System.out.println(body.keySet());
            System.out.println(body.values());
            return ResponseEntity.status(401).body("Unauthorized");
        }
        else{
            System.out.println("we had  api key not match the provided key\n");
            System.out.printf("provided key: %s and env key: %s\n", key, env.get("x-api-key"));
        }
        return  ResponseEntity.ok("received");
    }

    @GetMapping("/path")
    public String getDemoString() {
        return "This is a demo!";
    }
}
