package bgu.spl.net.impl.stomp;

import bgu.spl.net.api.StompMessagingProtocol;
import bgu.spl.net.srv.Connections;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class StompProtocolImpl implements StompMessagingProtocol<String> {

    private static final ConcurrentHashMap<String, String> userToPass = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> loggedIn = new ConcurrentHashMap<>();

    private int connectionId;
    private Connections<String> connections;
    private boolean shouldTerminate = false;
    private String username = null;

    @Override
    public void start(int connectionId, Connections<String> connections) {
        this.connectionId = connectionId;
        this.connections = connections;
    }

    @Override
    public String process(String message) {
        if (message == null || message.isEmpty()) return null;

        StompFrame frame = StompFrame.parse(message);
        String cmd = frame.command;
        
        if (username == null && !cmd.equals("CONNECT")) {
            sendError(frame, "Must CONNECT first");
            disconnectNow();
            return null;
        }


        switch (cmd) {
            case "CONNECT":
                handleConnect(frame);
                break;

            case "DISCONNECT":
                handleDisconnect(frame);
                break;

            default:
                sendError(frame, "Unsupported command: " + cmd);
                disconnectNow();
        }
        return null;
    }

    @Override
    public boolean shouldTerminate() {
        return shouldTerminate;
    }

    private void handleConnect(StompFrame frame) {
        String login = frame.headers.get("login");
        String passcode = frame.headers.get("passcode");

        if (login == null || passcode == null) {
            sendError(frame, "Missing login/passcode");
            disconnectNow();
            return;
        }

        userToPass.putIfAbsent(login, passcode);

        if (!userToPass.get(login).equals(passcode)) {
            sendError(frame, "Wrong password");
            disconnectNow();
            return;
        }

        if (Boolean.TRUE.equals(loggedIn.get(login))) {
            sendError(frame, "User already logged in");
            disconnectNow();
            return;
        }

        loggedIn.put(login, true);
        this.username = login;

        String connected =
                "CONNECTED\n" +
                "version:1.2\n" +
                "\n";

        connections.send(connectionId, connected);
    }

    private void handleDisconnect(StompFrame frame) {
        String receipt = frame.headers.get("receipt");
        if (receipt != null) {
            String receiptFrame =
                    "RECEIPT\n" +
                    "receipt-id:" + receipt + "\n" +
                    "\n";
            connections.send(connectionId, receiptFrame);
        }

        if (username != null) {
            loggedIn.put(username, false);
            username = null;
        }

        disconnectNow();
    }

    private void sendError(StompFrame relatedFrame, String shortMessage) {
        String receipt = relatedFrame.headers.get("receipt");

        StringBuilder sb = new StringBuilder();
        sb.append("ERROR\n");
        if (receipt != null) sb.append("receipt-id:").append(receipt).append("\n");
        sb.append("message:").append(shortMessage).append("\n");
        sb.append("\n");

        connections.send(connectionId, sb.toString());
    }

    private void disconnectNow() {
        shouldTerminate = true;
        connections.disconnect(connectionId);
    }

    private static class StompFrame {
        final String command;
        final Map<String, String> headers;
        final String body;

        private StompFrame(String command, Map<String, String> headers, String body) {
            this.command = command;
            this.headers = headers;
            this.body = body;
        }

        static StompFrame parse(String raw) {
            String[] parts = raw.split("\n\n", 2);
            String head = parts[0];
            String body = (parts.length == 2) ? parts[1] : "";

            String[] lines = head.split("\n");
            String cmd = lines[0].trim();

            Map<String, String> headers = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                int idx = line.indexOf(':');
                if (idx > 0) {
                    headers.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
                }
            }
            return new StompFrame(cmd, headers, body);
        }
    }
}
