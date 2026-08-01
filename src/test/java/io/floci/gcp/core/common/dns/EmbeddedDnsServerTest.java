package io.floci.gcp.core.common.dns;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddedDnsServerTest {

    private EmbeddedDnsServer dns;

    @BeforeEach
    void setUp() {
        dns = new EmbeddedDnsServer(List.of());
    }

    // ── matchesSuffix — built-in suffix ───────────────────────────────────────

    @Test
    void matchesSuffix_exactMatch() {
        assertTrue(dns.matchesSuffix("localhost.floci.io"));
    }

    @Test
    void matchesSuffix_singleSubdomain() {
        assertTrue(dns.matchesSuffix("my-bucket.localhost.floci.io"));
    }

    @Test
    void matchesSuffix_deeplyNested() {
        assertTrue(dns.matchesSuffix("deeply.nested.bucket.localhost.floci.io"));
    }

    @Test
    void matchesSuffix_caseInsensitive() {
        assertTrue(dns.matchesSuffix("My-Bucket.Localhost.Floci.IO"));
    }

    @Test
    void matchesSuffix_noMatch() {
        assertFalse(dns.matchesSuffix("my-bucket.storage.googleapis.com"));
    }

    @Test
    void matchesSuffix_partialSuffixNoMatch() {
        assertFalse(dns.matchesSuffix("floci-gcp.io"));
    }

    @Test
    void matchesSuffix_bareSubdomainNoMatch() {
        assertFalse(dns.matchesSuffix("my-bucket.floci-gcp.io"));
    }

    @Test
    void matchesSuffix_nullAndEmpty() {
        assertFalse(dns.matchesSuffix(null));
        assertFalse(dns.matchesSuffix(""));
    }

    // ── matchesSuffix — extra suffix ──────────────────────────────────────────

    @Test
    void matchesSuffix_extraSuffixExact() {
        EmbeddedDnsServer dnsWithExtra = new EmbeddedDnsServer(List.of("custom.internal"));
        assertTrue(dnsWithExtra.matchesSuffix("custom.internal"));
        assertTrue(dnsWithExtra.matchesSuffix("foo.custom.internal"));
    }

    // ── readName ──────────────────────────────────────────────────────────────

    @Test
    void readName_simple() {
        byte[] encoded = encodeName("my-bucket.localhost.floci.io");
        ByteBuffer buf = ByteBuffer.wrap(encoded);
        assertEquals("my-bucket.localhost.floci.io", dns.readName(buf, encoded));
    }

    @Test
    void readName_singleLabel() {
        byte[] encoded = encodeName("floci");
        ByteBuffer buf = ByteBuffer.wrap(encoded);
        assertEquals("floci", dns.readName(buf, encoded));
    }

    @Test
    void readName_withCompressionPointer() {
        byte[] data = new byte[20];
        data[0] = (byte) 0xC0;
        data[1] = 0x04;
        byte[] name = encodeName("floci-gcp.io");
        System.arraycopy(name, 0, data, 4, name.length);
        ByteBuffer buf = ByteBuffer.wrap(data);
        assertEquals("floci-gcp.io", dns.readName(buf, data));
    }

    // ── buildAResponse ────────────────────────────────────────────────────────

    @Test
    void buildAResponse_hasCorrectTransactionId() {
        byte[] query = buildQuery("my-bucket.localhost.floci.io", (short) 0x1234);
        byte[] response = dns.buildAResponse(query, (short) 0x1234, 12, query.length, "172.19.0.2");
        short txId = ByteBuffer.wrap(response).getShort(0);
        assertEquals((short) 0x1234, txId);
    }

    @Test
    void buildAResponse_flagsIndicateResponse() {
        byte[] query = buildQuery("bucket.localhost.floci.io", (short) 1);
        byte[] response = dns.buildAResponse(query, (short) 1, 12, query.length, "10.0.0.1");
        short flags = ByteBuffer.wrap(response).getShort(2);
        assertTrue((flags & 0x8000) != 0, "QR bit must be set");
    }

    @Test
    void buildAResponse_answerCountIsOne() {
        byte[] query = buildQuery("bucket.localhost.floci.io", (short) 2);
        byte[] response = dns.buildAResponse(query, (short) 2, 12, query.length, "10.0.0.1");
        short ancount = ByteBuffer.wrap(response).getShort(6);
        assertEquals(1, ancount);
    }

    @Test
    void buildAResponse_ipAddressIsCorrect() {
        byte[] query = buildQuery("bucket.localhost.floci.io", (short) 3);
        byte[] response = dns.buildAResponse(query, (short) 3, 12, query.length, "172.19.0.42");
        int questionLength = query.length - 12;
        ByteBuffer resp = ByteBuffer.wrap(response);
        resp.position(12 + questionLength + 10);
        short rdlen = resp.getShort();
        assertEquals(4, rdlen);
        assertEquals((byte) 172, resp.get());
        assertEquals((byte) 19, resp.get());
        assertEquals((byte) 0, resp.get());
        assertEquals((byte) 42, resp.get());
    }

    // ── composeUpstreams — forwarder upstream ordering ────────────────────────

    @Test
    void composeUpstreams_resolvConfFirstThenFallbacks() {
        List<String> upstreams = EmbeddedDnsServer.composeUpstreams(
                List.of("192.168.65.7"), List.of("8.8.8.8", "8.8.4.4"));
        assertEquals(List.of("192.168.65.7", "8.8.8.8", "8.8.4.4"), upstreams);
    }

    @Test
    void composeUpstreams_usesDockerResolverBaselineWhenResolvConfEmpty() {
        List<String> upstreams = EmbeddedDnsServer.composeUpstreams(
                List.of(), List.of("8.8.8.8"));
        // Docker's embedded resolver is the baseline, then the configured fallback.
        assertEquals(List.of("127.0.0.11", "8.8.8.8"), upstreams);
    }

    @Test
    void composeUpstreams_skipsLoopbackAndBlankEntries() {
        List<String> upstreams = EmbeddedDnsServer.composeUpstreams(
                List.of("127.0.0.1", "  ", "10.0.0.2"), List.of("", "8.8.8.8"));
        assertEquals(List.of("10.0.0.2", "8.8.8.8"), upstreams);
    }

    @Test
    void composeUpstreams_dedupesAcrossResolvConfAndFallbacks() {
        List<String> upstreams = EmbeddedDnsServer.composeUpstreams(
                List.of("8.8.8.8"), List.of("8.8.8.8", "8.8.4.4"));
        assertEquals(List.of("8.8.8.8", "8.8.4.4"), upstreams);
    }

    @Test
    void composeUpstreams_toleratesNullFallbacks() {
        List<String> upstreams = EmbeddedDnsServer.composeUpstreams(List.of("10.0.0.2"), null);
        assertEquals(List.of("10.0.0.2"), upstreams);
    }

    // ── forwarding — response buffer size regression ──────────────────────────

    @Test
    void forwardToUpstreams_returnsResponseLargerThan512BytesIntact() throws Exception {
        // Regression guard: a 512-byte receive buffer silently truncated EDNS0 responses from
        // CDN-backed public hosts, corrupting the answer forwarded back to sidecar containers.
        byte[] bigResponse = new byte[1500];
        for (int i = 0; i < bigResponse.length; i++) {
            bigResponse[i] = (byte) (i & 0xFF);
        }

        try (DatagramSocket responder = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            startResponder(responder, bigResponse);
            byte[] query = buildQuery("storage.googleapis.com", (short) 0x1234);

            byte[] response = dns.forwardToUpstreams(
                    query, List.of("127.0.0.1"), responder.getLocalPort());

            assertEquals(bigResponse.length, response.length,
                    "response larger than 512 bytes must be forwarded without truncation");
            assertArrayEquals(bigResponse, response);
        }
    }

    /** Replies to the first datagram received with a fixed payload, on a daemon thread. */
    private void startResponder(DatagramSocket responder, byte[] responsePayload) {
        Thread t = new Thread(() -> {
            try {
                DatagramPacket req = new DatagramPacket(new byte[4096], 4096);
                responder.receive(req);
                responder.send(new DatagramPacket(
                        responsePayload, responsePayload.length, req.getAddress(), req.getPort()));
            } catch (Exception ignored) {
                // socket closed when the test completes
            }
        });
        t.setDaemon(true);
        t.start();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private byte[] encodeName(String name) {
        String[] labels = name.split("\\.");
        int len = 1;
        for (String l : labels) {
            len += 1 + l.length();
        }
        byte[] buf = new byte[len];
        int pos = 0;
        for (String label : labels) {
            buf[pos++] = (byte) label.length();
            for (char c : label.toCharArray()) {
                buf[pos++] = (byte) c;
            }
        }
        buf[pos] = 0;
        return buf;
    }

    private byte[] buildQuery(String name, short txId) {
        byte[] encodedName = encodeName(name);
        ByteBuffer buf = ByteBuffer.allocate(12 + encodedName.length + 4);
        buf.putShort(txId);
        buf.putShort((short) 0x0100);
        buf.putShort((short) 1);
        buf.putShort((short) 0);
        buf.putShort((short) 0);
        buf.putShort((short) 0);
        buf.put(encodedName);
        buf.putShort((short) 1);
        buf.putShort((short) 1);
        return buf.array();
    }
}