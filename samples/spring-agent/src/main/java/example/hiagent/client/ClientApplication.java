package example.hiagent.client;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
/** Supply an AgentModelClient bean or select an adapter; no production fake is installed. */
@SpringBootApplication
public class ClientApplication {
    public static void main(String[] args) { SpringApplication.run(ClientApplication.class, args); }
}
