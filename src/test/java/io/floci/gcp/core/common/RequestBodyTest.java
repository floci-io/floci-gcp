package io.floci.gcp.core.common;

import jakarta.ws.rs.container.ContainerRequestContext;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequestBodyTest {

    private static final byte[] BODY = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);

    @Test
    void readsTheBodyOnceAndLeavesItForTheHandler() throws IOException {
        InputStream[] stream = {new ByteArrayInputStream(BODY)};
        ContainerRequestContext context = context(true);
        when(context.getEntityStream()).thenAnswer(call -> stream[0]);
        doAnswer(call -> stream[0] = call.getArgument(0)).when(context).setEntityStream(any());

        assertArrayEquals(BODY, RequestBody.of(context));
        assertArrayEquals(BODY, RequestBody.of(context));

        verify(context, times(1)).getEntityStream();
        assertArrayEquals(BODY, stream[0].readAllBytes());
    }

    @Test
    void aRequestWithoutEntityHasAnEmptyBody() throws IOException {
        ContainerRequestContext context = context(false);

        assertEquals(0, RequestBody.of(context).length);

        verify(context, never()).getEntityStream();
    }

    private static ContainerRequestContext context(boolean hasEntity) {
        Map<String, Object> properties = new HashMap<>();
        ContainerRequestContext context = mock(ContainerRequestContext.class);
        when(context.hasEntity()).thenReturn(hasEntity);
        when(context.getProperty(anyString())).thenAnswer(call -> properties.get(call.<String>getArgument(0)));
        doAnswer(call -> properties.put(call.getArgument(0), call.getArgument(1)))
                .when(context).setProperty(anyString(), any());
        return context;
    }
}
