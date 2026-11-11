package ru.ldpe.dialogauth;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.UUID;
import java.util.function.BooleanSupplier;

public interface AuthBackend {
    boolean exists(UUID uuid);
    void register(UUID uuid, String username, char[] password, BooleanSupplier active)
            throws IOException, GeneralSecurityException;
    boolean verify(UUID uuid, String username, char[] password, BooleanSupplier active)
            throws IOException, GeneralSecurityException;

    default boolean exists(UUID uuid, String username, boolean offlineMode) {
        return exists(uuid);
    }

    default void register(UUID uuid, String username, char[] password, boolean offlineMode,
                          BooleanSupplier active) throws IOException, GeneralSecurityException {
        register(uuid, username, password, active);
    }

    default boolean verify(UUID uuid, String username, char[] password, boolean offlineMode,
                           BooleanSupplier active) throws IOException, GeneralSecurityException {
        return verify(uuid, username, password, active);
    }
}
