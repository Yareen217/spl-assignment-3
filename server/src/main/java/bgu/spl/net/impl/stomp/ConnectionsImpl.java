package bgu.spl.net.impl.stomp;

import bgu.spl.net.srv.Connections;
import bgu.spl.net.srv.ConnectionHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class ConnectionsImpl<T> implements Connections<T> {

    private final ConcurrentMap<Integer, ConnectionHandler<T>> handlers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ConcurrentMap<Integer, Integer>> channelSubscribers = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, ConcurrentMap<Integer, String>> connectionActiveSubscriptions = new ConcurrentHashMap<>();
    
    // NEW: Map to track which user is connected on which connection ID
    private final ConcurrentMap<Integer, String> activeUsers = new ConcurrentHashMap<>();

    @Override
    public boolean send(int connectionId, T msg) {
        ConnectionHandler<T> handler = handlers.get(connectionId);
        if (handler != null) {
            handler.send(msg);
            return true;
        }
        return false;
    }

    @Override
    public void send(String channel, T msg) {
        ConcurrentMap<Integer, Integer> subscribers = channelSubscribers.get(channel);
        if (subscribers != null) {
            for (Integer connectionId : subscribers.keySet()) {
                send(connectionId, msg); 
            }
        }
    }

    @Override
    public void disconnect(int connectionId) {
        ConnectionHandler<T> handler = handlers.remove(connectionId);
        
        // 1. Clean up subscriptions (existing logic)
        Map<Integer, String> subscriptions = connectionActiveSubscriptions.remove(connectionId);
        if (subscriptions != null) {
            for (String channel : subscriptions.values()) {
                ConcurrentMap<Integer, Integer> subscribers = channelSubscribers.get(channel);
                if (subscribers != null) {
                    subscribers.remove(connectionId);
                }
            }
        }
        
        // 2. NEW: Clean up the user login state
        activeUsers.remove(connectionId);

        // 3. Close socket
        if (handler != null) {
            try {
                handler.close();
            } catch (Exception ignored) {}
        }
    }

    public void addConnection(int connectionId, ConnectionHandler<T> handler) {
        handlers.put(connectionId, handler);
    }

    // ... (Your existing subscribe/unsubscribe/hasSubscription helpers remain here) ...
    public void subscribe(int connectionId, String channel, int subscriptionId) {
        channelSubscribers.computeIfAbsent(channel, k -> new ConcurrentHashMap<>())
                          .put(connectionId, subscriptionId);
        connectionActiveSubscriptions.computeIfAbsent(connectionId, k -> new ConcurrentHashMap<>())
                                     .put(subscriptionId, channel);
    }

    public void unsubscribe(int connectionId, int subscriptionId) {
        Map<Integer, String> clientSubs = connectionActiveSubscriptions.get(connectionId);
        if (clientSubs != null) {
            String channel = clientSubs.remove(subscriptionId);
            if (channel != null) {
                ConcurrentMap<Integer, Integer> subscribers = channelSubscribers.get(channel);
                if (subscribers != null) {
                    subscribers.remove(connectionId);
                }
            }
        }
    }

    public boolean hasSubscription(int connectionId, String channel) {
        Map<Integer, String> subs = connectionActiveSubscriptions.get(connectionId);
        return subs != null && subs.containsValue(channel);
    }

    public Map<Integer, Integer> getChannelSubscribers(String channel) {
        ConcurrentMap<Integer, Integer> subs = channelSubscribers.get(channel);
        if (subs == null) return new java.util.HashMap<>();
        return subs;
    }

    /** * NEW: Try to log in a user.
     * @return true if successful, false if user is already logged in elsewhere.
     */
    public boolean tryLogin(int connectionId, String username) {
        // Check if username is already in values
        if (activeUsers.containsValue(username)) {
            return false;
        }
        // Atomic check might be better, but standard containsValue is usually sufficient for this assignment level.
        // For stricter concurrency:
        for (String user : activeUsers.values()) {
            if (user.equals(username)) return false;
        }
        activeUsers.put(connectionId, username);
        return true;
    }
}