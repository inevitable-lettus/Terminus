package csc210.algorithm.fabric;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Keeps the Fabric token in a file only this user can read, so a restarted
 * {@code bus serve} can prove the app id is still its own.
 */
public final class TokenStore {

    private final Path file;

    public TokenStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    /** The saved token, or null if there is none. */
    public String load() throws IOException {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        String text = Files.readString(file, StandardCharsets.UTF_8).strip();
        return text.isEmpty() ? null : text;
    }

    public void save(String token) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.deleteIfExists(temp);
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.createFile(temp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } else {
            Files.createFile(temp);
        }
        Files.writeString(temp, token, StandardCharsets.UTF_8);
        Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    public void clear() throws IOException {
        Files.deleteIfExists(file);
    }
}
