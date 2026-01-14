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

            // FIX: Append a null character (\u0000) so the Python server knows to stop reading.
            out.print(sqlCommand + "\u0000");
            out.flush();

            // Python sends response ended with \n. readLine() reads it correctly.
            return in.readLine();

        } catch (IOException e) {
            System.out.println("SQL Communication Error: " + e.getMessage());
            return null;
        }
    }
}