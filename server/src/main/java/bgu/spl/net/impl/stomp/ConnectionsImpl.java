package bgu.spl.net.impl.stomp;

import bgu.spl.net.srv.ConnectionHandler;
import bgu.spl.net.srv.Connections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Map;
import java.util.List;
import java.io.IOException;

public class ConnectionsImpl<T> implements Connections<T> {

    // Mapping: ConnectionID -> ConnectionHandler
    private final ConcurrentHashMap<Integer, ConnectionHandler<T>> activeConnections = new ConcurrentHashMap<>();
    
    // Mapping: Channel Name -> List of Subscriber ConnectionIDs
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Integer>> channelSubscribers = new ConcurrentHashMap<>();

    // Mapping: ConnectionID -> (SubscriptionID -> Channel Name)
    // This helper map allows us to easily remove all subscriptions for a specific user on disconnect.
    private final ConcurrentHashMap<Integer, ConcurrentHashMap<Integer, String>> clientSubscriptions = new ConcurrentHashMap<>();

    // Mapping: Username -> ConnectionID (To check "User already logged in")
    private final ConcurrentHashMap<String, Integer> loggedInUsers = new ConcurrentHashMap<>();

    @Override
    public boolean send(int connectionId, T msg) {
        ConnectionHandler<T> handler = activeConnections.get(connectionId);
        if (handler != null) {
            handler.send(msg);
            return true;
        }
        return false;
    }

    @Override
    public void send(String channel, T msg) {
        // Broadcast to all subscribers of this channel
        List<Integer> subscribers = channelSubscribers.get(channel);
        if (subscribers != null) {
            for (Integer connId : subscribers) {
                send(connId, msg);
            }
        }
    }

    @Override
    public void disconnect(int connectionId) {
        // 1. Remove from active connections
        activeConnections.remove(connectionId);

        // 2. Remove from loggedInUsers (CRITICAL for Milestone 4)
        // We have to iterate because the map is Username -> ID
        loggedInUsers.values().removeIf(id -> id == connectionId);

        // 3. Remove all subscriptions for this connection (CRITICAL for Milestone 4)
        Map<Integer, String> userSubs = clientSubscriptions.remove(connectionId);
        if (userSubs != null) {
            for (String channel : userSubs.values()) {
                List<Integer> subscribers = channelSubscribers.get(channel);
                if (subscribers != null) {
                    subscribers.remove((Integer) connectionId);
                }
            }
        }
    }

    // --- Helper Methods for StompProtocolImpl ---

    public void addConnection(int connectionId, ConnectionHandler<T> handler) {
        activeConnections.put(connectionId, handler);
        clientSubscriptions.put(connectionId, new ConcurrentHashMap<>());
    }

    public boolean tryLogin(int connectionId, String username) {
        // Atomic check: if key absent, put value. Returns null if success (key was absent).
        return loggedInUsers.putIfAbsent(username, connectionId) == null;
    }
    
    public void subscribe(int connectionId, String channel, int subscriptionId) {
        channelSubscribers.putIfAbsent(channel, new CopyOnWriteArrayList<>());
        channelSubscribers.get(channel).add(connectionId);

        // Track per client for easy removal later
        if (clientSubscriptions.containsKey(connectionId)) {
            clientSubscriptions.get(connectionId).put(subscriptionId, channel);
        }
    }

    public void unsubscribe(int connectionId, int subscriptionId) {
        if (clientSubscriptions.containsKey(connectionId)) {
            String channel = clientSubscriptions.get(connectionId).remove(subscriptionId);
            if (channel != null && channelSubscribers.containsKey(channel)) {
                channelSubscribers.get(channel).remove((Integer) connectionId);
            }
        }
    }

    public boolean hasSubscription(int connectionId, String channel) {
        if (!clientSubscriptions.containsKey(connectionId)) return false;
        return clientSubscriptions.get(connectionId).containsValue(channel);
    }

    public Map<Integer, Integer> getChannelSubscribers(String channel) {
        // Needed for sending messages with specific Subscription IDs
        // Returns Map: ConnectionID -> SubscriptionID
        Map<Integer, Integer> result = new java.util.HashMap<>();
        
        List<Integer> subscribers = channelSubscribers.get(channel);
        if (subscribers != null) {
            for (Integer connId : subscribers) {
                // Find the sub ID for this channel for this user
                Map<Integer, String> userSubs = clientSubscriptions.get(connId);
                if (userSubs != null) {
                    for (Map.Entry<Integer, String> entry : userSubs.entrySet()) {
                        if (entry.getValue().equals(channel)) {
                            result.put(connId, entry.getKey());
                            break; 
                        }
                    }
                }
            }
        }
        return result;
    }
}