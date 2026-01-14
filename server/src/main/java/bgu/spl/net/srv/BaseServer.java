package bgu.spl.net.srv;
import java.util.concurrent.atomic.AtomicInteger;

import bgu.spl.net.api.StompMessagingProtocol;
import bgu.spl.net.impl.stomp.ConnectionsImpl;

import bgu.spl.net.api.MessageEncoderDecoder;
import bgu.spl.net.api.MessagingProtocol;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.function.Supplier;

public abstract class BaseServer<T> implements Server<T> {

    private final int port;
    private final Supplier<MessagingProtocol<T>> protocolFactory;
    private final Supplier<MessageEncoderDecoder<T>> encdecFactory;
    private ServerSocket sock;
    private final ConnectionsImpl<T> connections = new ConnectionsImpl<>();
    private final AtomicInteger idCounter = new AtomicInteger(0);


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

            this.sock = serverSock; //just to be able to close

            while (!Thread.currentThread().isInterrupted()) {

                Socket clientSock = serverSock.accept();

                MessagingProtocol<T> protocol = protocolFactory.get();
                MessageEncoderDecoder<T> encdec = encdecFactory.get();

                BlockingConnectionHandler<T> handler =
                        new BlockingConnectionHandler<>(clientSock, encdec, protocol);

                int id = idCounter.incrementAndGet();
                connections.addConnection(id, handler);

                if (protocol instanceof StompMessagingProtocol) {
                    ((StompMessagingProtocol<T>) protocol).start(id, connections);
                }

                execute(handler);

            }
        } catch (IOException ex) {
        }

        System.out.println("server closed!!!");
    }

    @Override
    public void close() throws IOException {
		if (sock != null)
			sock.close();
    }

    protected abstract void execute(BlockingConnectionHandler<T>  handler);

}
