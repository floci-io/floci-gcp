package io.floci.gcp.services.compute;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ComputeSerialPortTest {

    @Test
    void readsFromAPositionAndReportsTheNextOne() {
        ComputeSerialPort port = new ComputeSerialPort(64);
        port.write(bytes("hello\nworld\n"));

        assertEquals(new ComputeSerialPort.Output("hello\nworld\n", 0, 12), port.read(0));
        assertEquals(new ComputeSerialPort.Output("world\n", 6, 12), port.read(6));
        assertEquals(new ComputeSerialPort.Output("", 12, 12), port.read(12));
        assertEquals(new ComputeSerialPort.Output("", 40, 12), port.read(40));
    }

    @Test
    void negativeStartReadsTheLastBytes() {
        ComputeSerialPort port = new ComputeSerialPort(64);
        port.write(bytes("hello\nworld\n"));

        assertEquals(new ComputeSerialPort.Output("world\n", 6, 12), port.read(-6));
        assertEquals(new ComputeSerialPort.Output("hello\nworld\n", 0, 12), port.read(-100));
    }

    @Test
    void droppedOutputMovesTheStartToTheOldestByteHeld() {
        ComputeSerialPort port = new ComputeSerialPort(8);
        port.write(bytes("0123456789ABCDEF"));

        assertEquals(new ComputeSerialPort.Output("89ABCDEF", 8, 16), port.read(0));
        assertEquals(new ComputeSerialPort.Output("CDEF", 12, 16), port.read(12));
    }

    @Test
    void emptyPortStartsAtZero() {
        assertEquals(new ComputeSerialPort.Output("", 0, 0), new ComputeSerialPort().read(0));
        assertEquals(new ComputeSerialPort.Output("", 0, 0), new ComputeSerialPort().read(-10));
    }

    @Test
    void invalidUtf8IsHexEscapedAndValidTextIsKept() {
        ComputeSerialPort port = new ComputeSerialPort(64);
        port.write(new byte[] {'o', 'k', ' ', (byte) 0xff, ' ', (byte) 0xc3, (byte) 0xa9, '\n'});

        assertEquals("ok \\xff é\n", port.read(0).contents());
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
