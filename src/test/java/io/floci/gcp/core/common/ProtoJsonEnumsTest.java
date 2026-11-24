package io.floci.gcp.core.common;

import com.google.cloud.kms.v1.CryptoKey.CryptoKeyPurpose;
import com.google.cloud.kms.v1.CryptoKeyVersion.CryptoKeyVersionAlgorithm;
import com.google.protobuf.Descriptors.EnumDescriptor;
import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtoJsonEnumsTest {

    private static final EnumDescriptor PURPOSE = CryptoKeyPurpose.getDescriptor();
    private static final EnumDescriptor ALGORITHM = CryptoKeyVersionAlgorithm.getDescriptor();

    @Test
    void name_acceptsTheValueName() {
        assertEquals("ENCRYPT_DECRYPT", ProtoJsonEnums.name("ENCRYPT_DECRYPT", PURPOSE, "crypto_key.purpose"));
    }

    static Stream<Object> integralNumbers() {
        return Stream.of(1, 1L, BigInteger.ONE, new BigDecimal("1.0"), 1.0d, new BigDecimal("10e-1"));
    }

    @ParameterizedTest
    @MethodSource("integralNumbers")
    void name_acceptsAnIntegralNumberOfAnyNumericType(Object number) {
        assertEquals("ENCRYPT_DECRYPT", ProtoJsonEnums.name(number, PURPOSE, "crypto_key.purpose"));
    }

    @Test
    void name_acceptsExponentNotationThatIsAnInteger() {
        assertEquals("EC_SIGN_P256_SHA256",
                ProtoJsonEnums.name(new BigDecimal("1.2e1"), ALGORITHM, "version_template.algorithm"));
        assertEquals("EC_SIGN_P256_SHA256", ProtoJsonEnums.name(12.0d, ALGORITHM, "version_template.algorithm"));
    }

    @Test
    void name_returnsNullForNull() {
        assertNull(ProtoJsonEnums.name(null, PURPOSE, "crypto_key.purpose"));
    }

    static Stream<Object> rejectedValues() {
        return Stream.of(new BigDecimal("1.5"), 1.5d, "1", true, "NOT_A_PURPOSE", 99, 99L,
                new BigInteger("99999999999999999999"), List.of("ENCRYPT_DECRYPT"));
    }

    @ParameterizedTest
    @MethodSource("rejectedValues")
    void name_rejectsAValueTheEnumDoesNotDefineAndNamesTheField(Object value) {
        GcpException error = assertThrows(GcpException.class,
                () -> ProtoJsonEnums.name(value, PURPOSE, "crypto_key.purpose"));

        assertEquals(400, error.getHttpStatus());
        assertEquals("INVALID_ARGUMENT", error.getGcpStatus());
        assertEquals(Status.Code.INVALID_ARGUMENT, error.getGrpcCode());
        assertTrue(error.getMessage().startsWith("Invalid value at 'crypto_key.purpose' "
                + "(type.googleapis.com/google.cloud.kms.v1.CryptoKey.CryptoKeyPurpose), "), error.getMessage());
    }

    @Test
    void name_quotesAStringValueAndShowsANumberBare() {
        GcpException name = assertThrows(GcpException.class,
                () -> ProtoJsonEnums.name("1", PURPOSE, "crypto_key.purpose"));
        GcpException number = assertThrows(GcpException.class,
                () -> ProtoJsonEnums.name(99, PURPOSE, "crypto_key.purpose"));

        assertTrue(name.getMessage().endsWith(", \"1\""), name.getMessage());
        assertTrue(number.getMessage().endsWith(", 99"), number.getMessage());
    }
}
