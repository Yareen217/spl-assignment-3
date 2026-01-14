package bgu.spl.net.srv;

import bgu.spl.net.api.MessageEncoderDecoder;
import bgu.spl.net.api.MessagingProtocol;
import bgu.spl.net.impl.stomp.ConnectionsImpl;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class BaseServer<T> implements Server<T> {

    private final int port;
    private final Supplier<MessagingProtocol<T>> protocolFactory;
    private final Supplier<MessageEncoderDecoder<T>> encdecFactory;
    private ServerSocket sock;
    
    // NEW: Fields to manage connections and IDs
    private final ConnectionsImpl<T> connections = new ConnectionsImpl<>();
    private final AtomicInteger connectionIdCounter = new AtomicInteger(0);

    public BaseServer(
            int port,
            Supplier<MessagingProtocol<T>> protocolFactory,
            Supplier<MessageEncoderDecoder<T>> encdecFactory) {

        this.port = port;
        this.protocolFactory = protocolFactory;
        this.encdecFactory = encdecFactory;
		this.sock = null;
    }

    @Override
    public void serve() {

        try (ServerSocket serverSock = new ServerSocket(port)) {
			System.out.println("Server started");

            this.sock = serverSock; // just to be able to close

            while (!Thread.currentThread().isInterrupted()) {

                Socket clientSock = serverSock.accept();

                // 1. Generate ID
                int connectionId = connectionIdCounter.incrementAndGet();
                
                // 2. Create Protocol
                MessagingProtocol<T> protocol = protocolFactory.get();
                
                // 3. Create Handler with NEW arguments (connections + id)
                BlockingConnectionHandler<T> handler = new BlockingConnectionHandler<>(
                        clientSock,
                        encdecFactory.get(),
                        protocol,
                        connections,      // <--- Passed here
                        connectionId      // <--- Passed here
                );
                
                // 4. Register in connections map
                connections.addConnection(connectionId, handler);

                execute(handler);
            }
        } catch (IOException ex) {
        }

        System.out.println("server closed");
    }

    @Override
    public void close() throws IOException {
		if (sock != null)
			sock.close();
    }

    protected abstract void execute(BlockingConnectionHandler<T>  handler);
}