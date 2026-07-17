package com.example.myeongranghoe.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class GeminiAiClient {
    private static final Logger log = LoggerFactory.getLogger(GeminiAiClient.class);
    private static final String DEFAULT_MODEL = "gemini-2.0-flash";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String apiKey;
    private final String model;

    public GeminiAiClient(
            @Value("${app.ai.gemini.api-key:${GEMINI_API_KEY:}}") String apiKey,
            @Value("${app.ai.gemini.model:${GEMINI_MODEL:gemini-2.0-flash}}") String model
    ) {
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = normalizeModel(model);
    }

    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    public Optional<String> generate(String prompt) {
        if (!isConfigured() || prompt == null || prompt.isBlank()) {
            return Optional.empty();
        }

        try {
            String encodedModel = URLEncoder.encode(model, StandardCharsets.UTF_8);
            URI uri = URI.create(
                    "https://generativelanguage.googleapis.com/v1beta/models/"
                            + encodedModel
                            + ":generateContent"
            );

            Map<String, Object> body = Map.of(
                    "contents", List.of(Map.of(
                            "parts", List.of(Map.of("text", prompt))
                    )),
                    "generationConfig", Map.of(
                            "temperature", 0.35,
                            "maxOutputTokens", 600
                    )
            );

            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(12))
                    .header("Content-Type", "application/json")
                    .header("x-goog-api-key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn(
                        "Gemini API request failed. model={}, status={}, body={}",
                        model,
                        response.statusCode(),
                        truncate(response.body())
                );
                return Optional.empty();
            }

            JsonNode parts = objectMapper.readTree(response.body())
                    .path("candidates")
                    .path(0)
                    .path("content")
                    .path("parts");
            if (!parts.isArray() || parts.isEmpty()) {
                return Optional.empty();
            }

            String text = parts.path(0).path("text").asText("").trim();
            return text.isBlank() ? Optional.empty() : Optional.of(text);
        } catch (Exception e) {
            log.warn("Gemini API request failed before response. model={}, reason={}", model, e.toString());
            return Optional.empty();
        }
    }

    private String normalizeModel(String value) {
        String normalized = value == null || value.isBlank() ? DEFAULT_MODEL : value.trim();
        if (normalized.startsWith("models/")) {
            normalized = normalized.substring("models/".length());
        }
        if (normalized.endsWith(":generateContent")) {
            normalized = normalized.substring(0, normalized.length() - ":generateContent".length());
        }
        return normalized.isBlank() ? DEFAULT_MODEL : normalized;
    }

    private String truncate(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String oneLine = value.replace("\r", " ").replace("\n", " ").trim();
        return oneLine.length() > 500 ? oneLine.substring(0, 500) + "..." : oneLine;
    }
}
