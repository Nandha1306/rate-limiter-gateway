package io.github.nandha1306.gateway.filter;

import io.github.nandha1306.gateway.config.ApiKeyConfig;
import io.github.nandha1306.gateway.config.GatewayProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Looks up which client an API key belongs to, holding only SHA-256 hashes of the keys in memory.
 */
@Component
public class ApiKeyRegistry {
    private final Map<String, ClientIdentity> clientsByKeyHash;

    @Autowired
    public ApiKeyRegistry(GatewayProperties properties) {
        this(properties.apiKeys());
    }

    ApiKeyRegistry(Map<String, ApiKeyConfig> apiKeys) {
        this.clientsByKeyHash = apiKeys.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                entry -> sha256(entry.getKey()),
                entry -> new ClientIdentity(entry.getValue().clientId(), entry.getValue().tier())));
    }

    /**
     * Returns the client that owns the API key; empty for a missing, blank or unknown key.
     */
    public Optional<ClientIdentity> lookup(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(clientsByKeyHash.get(sha256(apiKey)));
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
