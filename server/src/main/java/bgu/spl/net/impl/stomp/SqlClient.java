package bgu.spl.net.impl.stomp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class SqlClient {

    private final String host;
    private final int port;

    public SqlClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public String execute(String sqlCommand) {
        try (Socket socket = new Socket(host, port);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            out.print(sqlCommand + "\u0000");
            out.flush();

            return in.readLine();

        } catch (IOException e) {
            System.out.println("SQL Communication Error: " + e.getMessage());
            return null;
        }
    }
}