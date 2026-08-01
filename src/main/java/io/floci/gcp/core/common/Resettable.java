package io.floci.gcp.core.common;

/**
 * Implemented by services or components that hold in-memory state which must be cleared
 * when the emulator state is reset or nuked.
 *
 * <p>Implementations are discovered via CDI ({@code Instance<Resettable>}) and invoked in
 * no particular order by the reset endpoint. Each {@link #clear()} must therefore be
 * self-contained (no ordering dependency on other Resettable beans) and idempotent.
 */
public interface Resettable {

    void clear();
}
