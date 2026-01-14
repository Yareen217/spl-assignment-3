package bgu.spl.net.impl.stomp;
import bgu.spl.net.srv.Server;
public class StompServer {

    public static void main(String[] args) {
        // TODO: implement this
        if(args.length < 2){
            System.out.println("Usage: <port> <tpc|reactor>");
            return;
        }

        int port = Integer.parseInt(args[0]);
        String mode = args[1];

        if ("tpc".equalsIgnoreCase(mode)){
            Server.threadPerClient(
                port,
                StompProtocolImpl::new,
                StompMessageEncoderDecoder::new
            ).serve();
        } else if ("reactor".equalsIgnoreCase(mode)){
            int threads = Runtime.getRuntime().availableProcessors();
            Server.reactor(
                threads,
                port,
                StompProtocolImpl::new,
                StompMessageEncoderDecoder::new
            ).serve();
        }else{
            System.out.println("Unknown mode: " + mode);
        }
    }
}
