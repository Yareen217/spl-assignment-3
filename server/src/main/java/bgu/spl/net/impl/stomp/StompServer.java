package bgu.spl.net.impl.stomp;

import bgu.spl.net.srv.Server;
import java.util.Scanner;

public class StompServer {

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: <port> <tpc|reactor>");
            return;
        }

        int port = Integer.parseInt(args[0]);
        String mode = args[1];

        SqlClient sqlClient = new SqlClient("127.0.0.1", 7778);

        new Thread(() -> {
            Scanner scanner = new Scanner(System.in);
            System.out.println("Admin console active. Type 'report' to see database stats.");
            
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (line.equalsIgnoreCase("report")) {
                    System.out.println("\n===== SERVER REPORT =====");
                    
                    System.out.println("--- USERS ---");
                    String users = sqlClient.execute("SELECT username, password FROM users");
                    System.out.println(users.isEmpty() ? "(No users)" : users);
                    
                    System.out.println("\n--- LOGIN HISTORY ---");
                    String logins = sqlClient.execute("SELECT * FROM login_history"); 
                    System.out.println(logins.isEmpty() ? "(No login history)" : logins);

                    System.out.println("\n--- GAME REPORTS ---");
                    String reports = sqlClient.execute("SELECT * FROM reports");
                    System.out.println(reports.isEmpty() ? "(No reports)" : reports);
                    
                    System.out.println("=========================\n");
                } else if (line.equalsIgnoreCase("exit")) {
                    System.exit(0);
                }
            }
        }).start();

        if ("tpc".equalsIgnoreCase(mode)) {
            Server.threadPerClient(
                    port,
                    () -> new StompProtocolImpl(sqlClient), 
                    StompMessageEncoderDecoder::new
            ).serve();
        } else if ("reactor".equalsIgnoreCase(mode)) {
            int threads = Runtime.getRuntime().availableProcessors();
            Server.reactor(
                    threads,
                    port,
                    () -> new StompProtocolImpl(sqlClient),
                    StompMessageEncoderDecoder::new
            ).serve();
        } else {
            System.out.println("Unknown mode: " + mode);
        }
    }
}