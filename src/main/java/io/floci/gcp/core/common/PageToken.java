package io.floci.gcp.core.common;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

public final class PageToken {

    private PageToken() {}

    public static String encode(int offset) {
        return Base64.getEncoder().encodeToString(
                Integer.toString(offset).getBytes(StandardCharsets.UTF_8));
    }

    /** Encodes paging state for APIs whose clients omit the page size after the first request. */
    public static String encode(int offset, int pageSize) {
        return Base64.getEncoder().encodeToString(
                (offset + ":" + pageSize).getBytes(StandardCharsets.UTF_8));
    }

    public static int decode(String token) {
        return decodeCursor(token).offset();
    }

    /** Offset plus an optional page size; offset-only tokens remain valid. */
    public static Cursor decodeCursor(String token) {
        if (token == null || token.isBlank()) {
            return new Cursor(0, null);
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":", -1);
            int offset = Integer.parseInt(parts[0]);
            Integer pageSize = parts.length == 2 ? Integer.parseInt(parts[1]) : null;
            return new Cursor(offset, pageSize);
        } catch (Exception e) {
            return new Cursor(0, null);
        }
    }

    public record Cursor(int offset, Integer pageSize) {}

    public record Page<T>(List<T> items, String nextPageToken) {}

    public static <T> Page<T> paginate(List<T> all, int pageSize, String pageToken) {
        if (pageSize <= 0) {
            return new Page<>(all, null);
        }
        int offset = decode(pageToken);
        if (offset < 0 || offset >= all.size()) {
            return new Page<>(List.of(), null);
        }
        int end = Math.min(offset + pageSize, all.size());
        List<T> page = List.copyOf(all.subList(offset, end));
        String next = end < all.size() ? encode(end) : null;
        return new Page<>(page, next);
    }
}
