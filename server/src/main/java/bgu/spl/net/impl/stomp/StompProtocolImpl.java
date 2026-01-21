package bgu.spl.net.impl.stomp;

import bgu.spl.net.api.StompMessagingProtocol;
import bgu.spl.net.srv.Connections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class StompProtocolImpl implements StompMessagingProtocol<String> {


    private static final AtomicInteger messageIdCounter = new AtomicInteger(0);

    private int connectionId;
    private Connections<String> connections;
    private boolean shouldTerminate = false;
    
    private String username = null;
    private int dbLoginId = -1; 

    private final SqlClient sqlClient;

    public StompProtocolImpl(SqlClient sqlClient) {
        this.sqlClient = sqlClient;
    }

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
            sendError(frame, "User not logged in");
            disconnectNow();
            return null;
        }

        switch (cmd) {
            case "CONNECT": handleConnect(frame); break;
            case "DISCONNECT": handleDisconnect(frame); break;
            case "SUBSCRIBE": handleSubscribe(frame); break;
            case "UNSUBSCRIBE": handleUnsubscribe(frame); break;
            case "SEND": handleSend(frame); break;
            default: sendError(frame, "Unsupported command"); disconnectNow();
        }
        return null;
    }
    
    @Override
    public boolean shouldTerminate() { return shouldTerminate; }

    // --- Handlers ---

    private void handleConnect(StompFrame frame) {
        String login = frame.headers.get("login");
        String passcode = frame.headers.get("passcode");

        if (login == null || passcode == null) {
            sendError(frame, "Missing login/passcode");
            disconnectNow(); return;
        }

        boolean success = ((ConnectionsImpl<String>) connections).tryLogin(connectionId, login);
        if (!success) {
            sendError(frame, "User already logged in");
            disconnectNow(); return;
        }

        String resp = sqlClient.execute("SELECT password FROM users WHERE username='" + login + "'");
        
        if (resp == null || resp.trim().isEmpty()) {
            sqlClient.execute("INSERT INTO users (username, password) VALUES ('" + login + "', '" + passcode + "')");
        } else {
            String dbPass = resp.trim(); 
            if (!dbPass.equals(passcode)) {
                sendError(frame, "Wrong password");
                disconnectNow(); return;
            }
        }

        String idResp = sqlClient.execute("INSERT INTO login_history (username) VALUES ('" + login + "')");
        try {
            this.dbLoginId = Integer.parseInt(idResp.trim());
        } catch (NumberFormatException e) {
            System.out.println("Error parsing DB Login ID: " + idResp);
        }

        this.username = login;
        connections.send(connectionId, "CONNECTED\nversion:1.2\n\n\u0000");
    }

    private void handleDisconnect(StompFrame frame) {
        sendReceiptIfNeeded(frame);
        disconnectNow();
    }

    private void handleSend(StompFrame frame) {
        String destination = frame.headers.get("destination");
        ConnectionsImpl<String> connImpl = (ConnectionsImpl<String>) connections;
        
        if (!connImpl.hasSubscription(connectionId, destination)) {
            sendError(frame, "Not subscribed"); disconnectNow(); return;
        }

        String messageBody = frame.body;

        String filename = getValueFromBody(messageBody, "file");
        
        if (filename == null) {
            filename = frame.headers.get("file-name");
        }
        
        if (filename != null) {
            String timestamp = Long.toString(System.currentTimeMillis());
            
            if (this.username != null) {
                sqlClient.execute("INSERT INTO reports (username, filename, game_channel, timestamp) VALUES ('" 
                                  + this.username + "', '" 
                                  + filename + "', '" 
                                  + destination + "', " 
                                  + timestamp + ")");
                
                System.out.println("Report logged: User=" + this.username + ", File=" + filename);
            }
        }
        int msgId = messageIdCounter.incrementAndGet();
        
        for (Map.Entry<Integer, Integer> subscriber : connImpl.getChannelSubscribers(destination).entrySet()) {
            connections.send(subscriber.getKey(), 
                "MESSAGE\nsubscription:" + subscriber.getValue() + "\nmessage-id:" + msgId + "\ndestination:" + destination + "\n\n" + messageBody + "\u0000");
        }
        sendReceiptIfNeeded(frame);
    }

    // --- Helper Method to parse "key: value" from message body ---
    private String getValueFromBody(String body, String key) {
        if (body == null) return null;
        String[] lines = body.split("\n");
        for (String line : lines) {
            String[] parts = line.split(":", 2);
            if (parts.length == 2) {
                if (parts[0].trim().equals(key)) {
                    return parts[1].trim();
                }
            }
        }
        return null; 
    }

    public void disconnectNow() {
        if (dbLoginId != -1) {
            sqlClient.execute("UPDATE login_history SET logout_time=CURRENT_TIMESTAMP WHERE id=" + dbLoginId);
            dbLoginId = -1;
        }

        shouldTerminate = true;
        connections.disconnect(connectionId);
    }
    
    // ... (Helper methods) ...
    private void handleSubscribe(StompFrame frame) {
        String destination = frame.headers.get("destination");
        String idStr = frame.headers.get("id");
        if (destination == null || idStr == null) { disconnectNow(); return; }
        ((ConnectionsImpl<String>) connections).subscribe(connectionId, destination, Integer.parseInt(idStr));
        sendReceiptIfNeeded(frame);
    }
    
    private void handleUnsubscribe(StompFrame frame) {
         String idStr = frame.headers.get("id");
         if (idStr == null) { disconnectNow(); return; }
         ((ConnectionsImpl<String>) connections).unsubscribe(connectionId, Integer.parseInt(idStr));
         sendReceiptIfNeeded(frame);
    }

    private void sendReceiptIfNeeded(StompFrame frame) {
        String r = frame.headers.get("receipt");
        if (r != null) connections.send(connectionId, "RECEIPT\nreceipt-id:" + r + "\n\n\u0000");
    }

    private void sendError(StompFrame f, String msg) {
    String r = f.headers.get("receipt");
    // 1. Build Headers
    StringBuilder sb = new StringBuilder();
    sb.append("ERROR\n");
    if (r != null) sb.append("receipt-id:").append(r).append("\n");
    sb.append("message:").append(msg).append("\n");
    sb.append("\n"); // End of headers
    
    // 2. Build Body (The "Fancy" part)
    sb.append("The message:\n");
    sb.append("-----\n");
    sb.append(f.command).append("\n"); // The command that failed
    // Reconstruct the headers provided by the user
    for (String key : f.headers.keySet()) {
        sb.append(key).append(":").append(f.headers.get(key)).append("\n");
    }
    sb.append("\n"); // Spacer
    if (!f.body.isEmpty()) sb.append(f.body).append("\n"); // The body they sent
    sb.append("-----\n");

    sb.append("\u0000"); // Null terminator

    connections.send(connectionId, sb.toString());
}

    // ... Inner StompFrame class ...
    private static class StompFrame {
        final String command; final Map<String, String> headers; final String body;
        static StompFrame parse(String raw) { 
             if (raw.endsWith("\u0000")) raw = raw.substring(0, raw.length() - 1);
             String[] parts = raw.split("\n\n", 2);
             String[] lines = parts[0].split("\n");
             Map<String,String> h = new HashMap<>();
             for(int i=1; i<lines.length; i++) {
                 int idx = lines[i].indexOf(':');
                 if(idx>0) h.put(lines[i].substring(0, idx).trim(), lines[i].substring(idx+1).trim());
             }
             return new StompFrame(lines[0].trim(), h, parts.length>1?parts[1]:"");
        }
        private StompFrame(String c, Map<String,String> h, String b) { command=c; headers=h; body=b; }
    }
}