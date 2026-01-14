package bgu.spl.net.impl.stomp;

import bgu.spl.net.api.StompMessagingProtocol;
import bgu.spl.net.srv.Connections;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class StompProtocolImpl implements StompMessagingProtocol<String> {

    private static final ConcurrentHashMap<String, String> userToPass = new ConcurrentHashMap<>();
    // [REMOVED] private static final ConcurrentHashMap<String, Boolean> loggedIn = ...
    private static final AtomicInteger messageIdCounter = new AtomicInteger(0);

    private int connectionId;
    private Connections<String> connections;
    private boolean shouldTerminate = false;
    private String username = null;

    @Override
    public void start(int connectionId, Connections<String> connections) {
        this.connectionId = connectionId;
        this.connections = connections;
    }
    
    // ... (process() remains exactly the same) ...
    @Override
    public String process(String message) {
        if (message == null || message.isEmpty()) return null;

        StompFrame frame = StompFrame.parse(message);
        String cmd = frame.command;

        // Force login first (except for CONNECT command)
        if (username == null && !cmd.equals("CONNECT")) {
            sendError(frame, "User not logged in");
            disconnectNow();
            return null;
        }
        // ... switch case logic ...
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

    private void handleConnect(StompFrame frame) {
        String login = frame.headers.get("login");
        String passcode = frame.headers.get("passcode");

        if (login == null || passcode == null) {
            sendError(frame, "Missing login/passcode");
            disconnectNow(); return;
        }

        userToPass.putIfAbsent(login, passcode);

        if (!userToPass.get(login).equals(passcode)) {
            sendError(frame, "Wrong password");
            disconnectNow(); return;
        }

        // NEW: Delegate "Is logged in?" check to ConnectionsImpl
        boolean success = ((ConnectionsImpl<String>) connections).tryLogin(connectionId, login);
        if (!success) {
            sendError(frame, "User already logged in");
            disconnectNow(); return;
        }

        this.username = login; // Local tracker

        String connected = "CONNECTED\nversion:1.2\n\n\u0000";
        connections.send(connectionId, connected);
    }

    private void handleDisconnect(StompFrame frame) {
        sendReceiptIfNeeded(frame);
        // Note: We don't need to manually remove from 'loggedIn' map anymore.
        // connections.disconnect() will handle it.
        disconnectNow();
    }
    
    // ... (rest of methods: handleSubscribe, handleSend, etc. remain the same) ...
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
    
    private void handleSend(StompFrame frame) {
        String destination = frame.headers.get("destination");
        ConnectionsImpl<String> connImpl = (ConnectionsImpl<String>) connections;
        if (!connImpl.hasSubscription(connectionId, destination)) {
            sendError(frame, "Not subscribed"); disconnectNow(); return;
        }
        String messageBody = frame.body;
        int msgId = messageIdCounter.incrementAndGet();
        for (Map.Entry<Integer, Integer> subscriber : connImpl.getChannelSubscribers(destination).entrySet()) {
            connections.send(subscriber.getKey(), 
                "MESSAGE\nsubscription:" + subscriber.getValue() + "\nmessage-id:" + msgId + "\ndestination:" + destination + "\n\n" + messageBody + "\u0000");
        }
        sendReceiptIfNeeded(frame);
    }

    private void sendReceiptIfNeeded(StompFrame frame) {
        String r = frame.headers.get("receipt");
        if (r != null) connections.send(connectionId, "RECEIPT\nreceipt-id:" + r + "\n\n\u0000");
    }

    private void sendError(StompFrame f, String msg) {
        String r = f.headers.get("receipt");
        String out = "ERROR\n" + (r!=null ? "receipt-id:"+r+"\n":"") + "message:"+msg+"\n\n\u0000";
        connections.send(connectionId, out);
    }

    private void disconnectNow() {
        shouldTerminate = true;
        connections.disconnect(connectionId);
    }
    
    // ... Inner StompFrame class ...
    private static class StompFrame {
        final String command; final Map<String, String> headers; final String body;
        static StompFrame parse(String raw) { /* same as before */ 
             // ... implementation ...
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