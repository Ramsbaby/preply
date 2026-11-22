package com.ramsbaby.preply.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.UserCredentials;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

@Component
@Slf4j
public class GmailDelivery {
    private final String credentialFile;
    private final String sender;
    private final ObjectMapper json;

    public GmailDelivery(@Value("${APP_GMAIL_CREDENTIALS:}") String credentialFile,
                         @Value("${APP_GMAIL_SENDER:}") String sender, ObjectMapper json) {
        this.credentialFile = credentialFile;
        this.sender = sender;
        this.json = json;
    }
    boolean enabled() { return !credentialFile.isBlank(); }
    String sender() { return sender; }

    void send(MimeMessage message) throws Exception {
        try (var in = Files.newInputStream(Path.of(credentialFile))) {
            var credentials = UserCredentials.fromStream(in);
            var bytes = new ByteArrayOutputStream();
            message.writeTo(bytes);
            var raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
            var req = new NetHttpTransport().createRequestFactory(new HttpCredentialsAdapter(credentials))
                    .buildPostRequest(new GenericUrl("https://gmail.googleapis.com/gmail/v1/users/me/messages/send"),
                            new ByteArrayContent("application/json", json.writeValueAsBytes(Map.of("raw", raw))));
            req.setNumberOfRetries(0);
            req.setConnectTimeout(10000);
            req.setReadTimeout(20000);
            var res = req.execute();
            try {
                var receipt = json.readTree(res.getContent()).path("id").asText();
                if (receipt.isBlank()) throw new IllegalStateException("Gmail returned no delivery receipt");
                log.info("Gmail accepted Preply summary: {}", receipt);
            } finally { res.disconnect(); }
        }
    }
}
