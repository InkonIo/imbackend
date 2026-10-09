package com.imdemo.im.ext;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Клиент kln: вход по логину и паролю из .env (KLN_LOGIN / KLN_PASSWORD), токен хранится только в памяти.
 * Только чтение (GET). Токен в лог не пишется. При 401 один раз входит заново, при 429 ждёт и повторяет.
 */
@Component
public class KlnClient {

    private static final Pattern TOKEN = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");

    @Value("${kln.base-url:https://api.kln.rtest.kz/api/v1}")
    private String baseUrl;
    @Value("${kln.login:}")
    private String login;
    @Value("${kln.password:}")
    private String password;
    @Value("${kln.login-field:login}")
    private String loginField;
    @Value("${kln.password-field:password}")
    private String passwordField;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private volatile String token;

    public boolean configured() {
        return login != null && !login.isBlank() && password != null && !password.isBlank();
    }

    /** GET по пути относительно base-url, например "/branches?all=true". Возвращает тело как есть. */
    public String get(String path) {
        if (!configured()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "kln не настроен: добавьте KLN_LOGIN и KLN_PASSWORD в .env и перезапустите приложение");
        }
        for (int attempt = 0; attempt < 4; attempt++) {
            if (token == null) token = doLogin();
            HttpResponse<String> r = send(path);
            int code = r.statusCode();
            if (code == 401) {
                token = null;
                if (attempt == 0) continue;
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "kln не принял вход (401). Проверьте KLN_LOGIN и KLN_PASSWORD");
            }
            if (code == 429) {
                sleep(5000L * (attempt + 1));
                continue;
            }
            if (code / 100 != 2) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "kln ответил " + code + " на " + path);
            }
            return r.body();
        }
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "kln не отвечает (лимит запросов)");
    }

    private HttpResponse<String> send(String path) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(40))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .GET().build();
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "kln недоступен: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "запрос к kln прерван");
        }
    }

    private String doLogin() {
        String body = "{\"" + loginField + "\":" + q(login) + ",\"" + passwordField + "\":" + q(password) + "}";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/auth/login"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "kln: вход не удался (" + r.statusCode() + "). Проверьте KLN_LOGIN и KLN_PASSWORD");
            }
            Matcher m = TOKEN.matcher(r.body());
            if (!m.find()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "kln: в ответе входа нет токена");
            return m.group(1);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "kln недоступен: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "вход в kln прерван");
        }
    }

    static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}