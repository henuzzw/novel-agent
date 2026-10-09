package com.novelagent.modelaccess.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.modelaccess.application.ChatGptAccessException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Per-user, owner-only files; OS file lock serializes rotating refresh tokens across JVMs. */
@Component
public class PrivateOAuthStore {
    private static final ConcurrentHashMap<Path, Object> LOCKS = new ConcurrentHashMap<>();
    private final Path root;
    private final ObjectMapper json;
    public PrivateOAuthStore(ObjectMapper json,
            @Value("${app.ai.chatgpt.credential-directory:${user.home}/.novel-agent/chatgpt}") String root) {
        this.json = json;
        if (root == null || root.isBlank()) throw storageFailure();
        this.root = Path.of(root).toAbsolutePath().normalize();
    }
    public <T> T locked(UUID user, Supplier<T> action) {
        Path directory = directory(user);
        synchronized (LOCKS.computeIfAbsent(directory, key -> new Object())) {
            try {
                Files.createDirectories(directory);
                protect(root, true); protect(directory, true);
                Path lock = directory.resolve("profile.lock");
                try (var channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS)) {
                    protect(lock, false);
                    try (var ignored = channel.lock()) { return action.get(); }
                }
            } catch (IOException error) { throw storageFailure(); }
        }
    }
    public Credentials read(UUID user) {
        Path path = directory(user).resolve("connection.json");
        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
            protect(path, false);
            return json.readValue(Files.readString(path), Credentials.class);
        } catch (IOException error) { throw storageFailure(); }
    }
    public String hostId() {
        Path path = root.resolve("host-id");
        synchronized (LOCKS.computeIfAbsent(root, key -> new Object())) {
            try {
                Files.createDirectories(root); protect(root, true);
                Path lock = root.resolve("host.lock");
                try (var channel = FileChannel.open(lock, StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    protect(lock, false);
                    try (var ignored = channel.lock()) {
                        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) atomicWrite(path, "urn:uuid:" + UUID.randomUUID());
                        protect(path, false);
                        String value = Files.readString(path).trim();
                        if (!value.startsWith("urn:uuid:")) throw storageFailure();
                        UUID id = UUID.fromString(value.substring("urn:uuid:".length()));
                        if (id.version() != 4) throw storageFailure();
                        return value;
                    }
                }
            } catch (IOException | IllegalArgumentException error) { throw storageFailure(); }
        }
    }
    public void save(UUID user, Credentials credentials) {
        try { atomicWrite(directory(user).resolve("connection.json"), json.writeValueAsString(credentials)); }
        catch (IOException error) { throw storageFailure(); }
    }
    private void atomicWrite(Path target, String value) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), ".oauth-", ".tmp");
        try {
            protect(temp, false);
            Files.writeString(temp, value);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    private Path directory(UUID user) { return root.resolve(user.toString()); }
    private static void protect(Path path, boolean directory) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Symbolic credential path rejected");
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
        } else {
            var acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (acl == null) throw new IOException("Owner-only permissions unavailable");
            acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(path))
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
        }
    }
    private static ChatGptAccessException storageFailure() {
        return new ChatGptAccessException("AUTHENTICATION", "无法安全读写 ChatGPT 凭据；请检查服务端私有目录权限");
    }
    public record Credentials(String clientId, String subject, String email, String idToken,
            String accessToken, String refreshToken, long expiresAt, String scope) {
        public boolean canGenerate() { return scope != null && List.of(scope.split("\\s+")).contains("chatgpt.tokens.use.direct"); }
        public Credentials signedOut() { return new Credentials(clientId, subject, email, null, null, null, 0, ""); }
        @Override public String toString() { return "Credentials[REDACTED]"; }
    }
}
