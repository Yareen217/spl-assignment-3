package bgu.spl.net.impl.stomp;

import bgu.spl.net.api.MessageEncoderDecoder;

import java.nio.charset.StandardCharsets;

public class StompMessageEncoderDecoder implements MessageEncoderDecoder<String> {

    private final StringBuilder buffer = new StringBuilder();

    @Override
    public String decodeNextByte(byte nextByte) {
        char c = (char) (nextByte & 0xff);

        // STOMP frame terminator
        if (c == '\0') {
            String frame = buffer.toString();
            buffer.setLength(0);
            return frame;
        }

        buffer.append(c);
        return null; // frame not complete yet
    }

    @Override
    public byte[] encode(String message) {
        if (message == null) return new byte[0];

        // Ensure outgoing frame ends with '\0'
        if (!message.endsWith("\0")) {
            message = message + "\0";
        }
        return message.getBytes(StandardCharsets.UTF_8);
    }
}
