package com.campus.campus_trade_backend.ai.client;

import com.campus.campus_trade_backend.ai.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** 复用原有 Responses API 的网络、超时和重试行为；不在日志中记录请求或密钥。 */
@Component
public class OpenAiResponsesGateway {
    private static final Logger log = LoggerFactory.getLogger(OpenAiResponsesGateway.class);
    private final AiProperties properties;
    private final HttpClient httpClient;

    @Autowired
    public OpenAiResponsesGateway(AiProperties properties) {
        this(properties, HttpClient.newBuilder()
                .connectTimeout(positive(properties.getConnectTimeout(), Duration.ofSeconds(3))).build());
    }

    OpenAiResponsesGateway(AiProperties properties, HttpClient httpClient) {
        this.properties = properties;
        this.httpClient = httpClient;
    }

    public boolean configured() {
        return properties.isEnabled() && properties.getApiKey() != null && !properties.getApiKey().isBlank()
                && properties.getModel() != null && !properties.getModel().isBlank();
    }

    public void ensureConfigured() {
        if (!properties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AI 辅助发布尚未启用，可继续手动填写并发布");
        }
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AI 服务尚未配置 API Key，可继续手动填写并发布");
        }
        if (properties.getModel() == null || properties.getModel().isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AI 模型尚未配置，可继续手动填写并发布");
        }
    }

    public String complete(String requestBody) {
        ensureConfigured();
        HttpRequest request = HttpRequest.newBuilder(endpoint())
                .timeout(positive(properties.getRequestTimeout(), Duration.ofSeconds(12)))
                .header("Authorization", "Bearer " + properties.getApiKey().trim())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() == 200) return response.body();
                if (isTransient(response.statusCode()) && attempt == 0) {
                    pauseBeforeRetry();
                    continue;
                }
                throw providerError(response.statusCode());
            } catch (HttpTimeoutException exception) {
                throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                        "AI 响应超时，可稍后重试或继续手动填写", exception);
            } catch (IOException exception) {
                if (attempt == 0) {
                    pauseBeforeRetry();
                    continue;
                }
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "AI 服务暂时无法连接，可继续手动填写", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "AI 请求已中断，可继续手动填写", exception);
            }
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "AI 服务暂不可用，可继续手动填写");
    }

    private URI endpoint() {
        String baseUrl = properties.getBaseUrl() == null ? "" : properties.getBaseUrl().trim();
        if (baseUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI 服务地址尚未配置");
        }
        try {
            URI uri = URI.create(baseUrl.replaceAll("/+$", "") + "/responses");
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("Unsupported AI endpoint");
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI 服务地址配置不正确", exception);
        }
    }

    private ResponseStatusException providerError(int statusCode) {
        log.warn("AI provider request failed with status={}", statusCode);
        if (statusCode == 401 || statusCode == 403) {
            return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AI 服务认证失败，请检查服务配置");
        }
        if (statusCode == 429 || statusCode >= 500) {
            return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AI 服务繁忙，可稍后重试或继续手动填写");
        }
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "AI 服务请求失败，请检查模型配置或继续手动填写");
    }

    private void pauseBeforeRetry() {
        try {
            Thread.sleep(Math.max(0, positive(properties.getRetryDelay(), Duration.ofMillis(200)).toMillis()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI 请求已中断", exception);
        }
    }

    private static boolean isTransient(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    private static Duration positive(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }
}
