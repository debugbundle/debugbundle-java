package com.debugbundle.sdk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public final class DebugBundleFileWriter {
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE
    );
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
    );

    private DebugBundleFileWriter() {
    }

    public static Path writeEventFile(Path configuredDirectory, String service, String content) throws IOException {
        Path directory = prepareDirectory(configuredDirectory);
        String filename = "%d-%06d-%s.events.json".formatted(
                Instant.now().toEpochMilli(),
                SEQUENCE.incrementAndGet(),
                sanitizeServiceName(service)
        );
        Path target = directory.resolve(filename).normalize();
        if (!target.getParent().equals(directory) || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unsafe DebugBundle event file target.");
        }

        Path temp = Files.createTempFile(directory, "." + filename + "-", "-" + randomHex() + ".tmp");
        setFilePermissions(temp);
        try {
            Files.writeString(
                    temp,
                    content,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
            if (Files.isSymbolicLink(target)) {
                throw new IOException("Refusing to replace symlinked DebugBundle event file target.");
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            setFilePermissions(target);
            return target;
        } catch (IOException | RuntimeException error) {
            Files.deleteIfExists(temp);
            throw error;
        }
    }

    public static void writeMarker(Path marker) throws IOException {
        Path directory = prepareDirectory(marker.getParent());
        Path target = directory.resolve(marker.getFileName()).normalize();
        if (!target.getParent().equals(directory) || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unsafe DebugBundle marker target.");
        }
        Files.writeString(
                target,
                "",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        );
        setFilePermissions(target);
    }

    public static String sanitizeServiceName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown-service";
        }
        return raw.replaceAll("[^A-Za-z0-9._-]", "-");
    }

    private static Path prepareDirectory(Path configuredDirectory) throws IOException {
        if (configuredDirectory == null) {
            throw new IOException("DebugBundle event directory is required.");
        }
        rejectTraversal(configuredDirectory);
        Path directory = configuredDirectory.toAbsolutePath().normalize();
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)) {
            throw new IOException("DebugBundle event directory must not be a symlink.");
        }
        setDirectoryPermissions(directory);
        return directory;
    }

    private static void rejectTraversal(Path path) throws IOException {
        for (Path segment : path) {
            if ("..".equals(segment.toString())) {
                throw new IOException("DebugBundle event directory must not contain path traversal.");
            }
        }
    }

    private static void setDirectoryPermissions(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, DIRECTORY_PERMISSIONS);
        } catch (UnsupportedOperationException ignored) {
        }
    }

    private static void setFilePermissions(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, FILE_PERMISSIONS);
        } catch (UnsupportedOperationException ignored) {
        }
    }

    private static String randomHex() {
        byte[] bytes = new byte[8];
        SECURE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
