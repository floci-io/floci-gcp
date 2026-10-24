package io.floci.gcp.services.compute;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * The most recent output of one serial port, as {@code instances.getSerialPortOutput} serves it: positions count
 * every byte ever written, the oldest bytes are dropped past {@link #CAPACITY}, and a read from a dropped position
 * starts at the oldest byte still held. Bytes that are not valid UTF-8 come back hex-escaped ({@code \xNN}), as
 * GCE escapes them.
 */
final class ComputeSerialPort {
    /** "the most recent 1 MB of output per port" (Compute docs, viewing serial port output). */
    static final int CAPACITY = 1024 * 1024;

    record Output(String contents, long start, long next) {}

    private final byte[] buffer;
    private long written;

    ComputeSerialPort() {
        this(CAPACITY);
    }

    ComputeSerialPort(int capacity) {
        this.buffer = new byte[capacity];
    }

    synchronized void write(byte[] bytes) {
        for (byte b : bytes) {
            buffer[(int) (written % buffer.length)] = b;
            written++;
        }
    }

    /**
     * Output from byte position {@code start}. A negative start reads the last {@code -start} bytes; a start before
     * the oldest byte held moves up to it; a start past the end returns nothing at that position.
     */
    synchronized Output read(long start) {
        long oldest = Math.max(0, written - buffer.length);
        long from = start < 0 ? Math.max(oldest, written + start) : Math.max(oldest, start);
        if (from >= written) {
            return new Output("", from, written);
        }
        byte[] out = new byte[(int) (written - from)];
        for (int i = 0; i < out.length; i++) {
            out[i] = buffer[(int) ((from + i) % buffer.length)];
        }
        return new Output(escape(out), from, written);
    }

    static String escape(byte[] bytes) {
        CharsetDecoder strict = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return strict.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return escapeInvalid(bytes);
        }
    }

    private static String escapeInvalid(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length);
        int i = 0;
        while (i < bytes.length) {
            int length = sequenceLength(bytes, i);
            if (length > 0) {
                out.append(new String(bytes, i, length, StandardCharsets.UTF_8));
                i += length;
            } else {
                out.append(String.format("\\x%02x", bytes[i] & 0xff));
                i++;
            }
        }
        return out.toString();
    }

    /** Length of the valid UTF-8 sequence starting at {@code i}, or 0 when the byte there does not start one. */
    private static int sequenceLength(byte[] bytes, int i) {
        int b = bytes[i] & 0xff;
        int length = b < 0x80 ? 1 : b >= 0xc2 && b <= 0xdf ? 2 : b >= 0xe0 && b <= 0xef ? 3 : b >= 0xf0 && b <= 0xf4 ? 4 : 0;
        if (length == 0 || i + length > bytes.length) { return 0; }
        for (int k = 1; k < length; k++) {
            if ((bytes[i + k] & 0xc0) != 0x80) { return 0; }
        }
        CharsetDecoder strict = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            strict.decode(ByteBuffer.wrap(bytes, i, length));
            return length;
        } catch (CharacterCodingException e) {
            return 0;
        }
    }
}
