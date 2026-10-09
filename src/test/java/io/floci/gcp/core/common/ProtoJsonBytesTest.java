package io.floci.gcp.core.common;

import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtoJsonBytesTest {

    @ParameterizedTest
    @ValueSource(strings = {"+/8=", "+/8", "-_8=", "-_8"})
    void decode_acceptsBothAlphabetsWithOptionalPadding(String value) {
        assertArrayEquals(new byte[]{(byte) 0xfb, (byte) 0xff},
                ProtoJsonBytes.decode(value, "payload.data"));
    }

    @Test
    void decode_emptyString() {
        assertArrayEquals(new byte[0], ProtoJsonBytes.decode("", "data"));
    }

    @ParameterizedTest
    @CsvSource({
            "!, payload.data",
            "A, ciphertext",
            "AA=, messages[1].data",
            "not*base64, data",
            "AA===, digest.sha256"
    })
    void decode_malformedInputReportsField(String value, String field) {
        GcpException error = assertThrows(GcpException.class, () -> ProtoJsonBytes.decode(value, field));

        assertEquals(400, error.getHttpStatus());
        assertEquals("INVALID_ARGUMENT", error.getGcpStatus());
        assertEquals(Status.Code.INVALID_ARGUMENT, error.getGrpcCode());
        assertTrue(error.getMessage().startsWith("Invalid value at '" + field + "' (TYPE_BYTES): "));
    }
}
