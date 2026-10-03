package com.imdemo.im.service;

import com.imdemo.im.web.error.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

@Component
public class PhotoStorage {

    private static final ZoneId ZONE = ZoneId.of("Asia/Almaty");

    private static final Map<String, String> EXT_BY_TYPE = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp",
            "image/heic", "heic",
            "image/heif", "heif");

    private static final Map<String, String> TYPE_BY_EXT = Map.of(
            "jpg", "image/jpeg",
            "png", "image/png",
            "webp", "image/webp",
            "heic", "image/heic",
            "heif", "image/heif");

    private final Path root;

    public PhotoStorage(@Value("${app.upload-dir}") String dir) throws IOException {
        this.root = Path.of(dir).toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    /** Сохраняет файл и возвращает относительный путь вида 2026-10-02/uuid.jpg */
    public String save(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Файл пустой");
        }
        String ct = file.getContentType();
        String ext = ct == null ? null : EXT_BY_TYPE.get(ct.toLowerCase());
        if (ext == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Можно только фото: JPG, PNG, WEBP, HEIC");
        }
        String relative = LocalDate.now(ZONE) + "/" + UUID.randomUUID() + "." + ext;
        Path target = resolve(relative);
        try {
            Files.createDirectories(target.getParent());
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target);
            }
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось сохранить фото");
        }
        return relative;
    }

    public Path resolve(String relative) {
        Path p = root.resolve(relative).normalize();
        if (!p.startsWith(root)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Некорректный путь");
        }
        return p;
    }

    public String contentType(String relative) {
        int dot = relative.lastIndexOf('.');
        String ext = dot < 0 ? "" : relative.substring(dot + 1).toLowerCase();
        return TYPE_BY_EXT.getOrDefault(ext, "application/octet-stream");
    }

    public void delete(String relative) {
        try {
            Files.deleteIfExists(resolve(relative));
        } catch (IOException ignored) {
            // файл мог уже отсутствовать, запись в БД всё равно удаляем
        }
    }
}