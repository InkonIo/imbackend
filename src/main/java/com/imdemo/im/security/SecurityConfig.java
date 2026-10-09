package com.imdemo.im.security;

import com.imdemo.im.repo.UserRepository;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtService jwtService,
                                           UserRepository userRepository) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        // внутренние пересылки на /error не должны превращать 403/500 в 401
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/auth/login").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("SUPER_ADMIN")
                        .requestMatchers("/api/audit/**").hasAnyRole("SUPER_ADMIN", "DIRECTOR")
                        .requestMatchers("/api/review/**").hasAnyRole("SUPER_ADMIN", "DIRECTOR")
                        .requestMatchers("/api/templates/**").hasAnyRole("SUPER_ADMIN", "DIRECTOR")
                        .requestMatchers("/api/schedule/my", "/api/schedule/today", "/api/schedule/me/**").authenticated()
                        .requestMatchers("/api/schedule/**").hasAnyRole("SUPER_ADMIN", "DIRECTOR")
                        .requestMatchers("/api/insights/**").hasAnyRole("SUPER_ADMIN", "DIRECTOR")
                        .requestMatchers("/api/shelf-life/admin/**").hasAnyRole("SUPER_ADMIN", "DIRECTOR")
                        .requestMatchers("/api/ext/**").hasAnyRole("SUPER_ADMIN", "DIRECTOR")
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        // 401: нет токена или он невалиден → фронт отправит на вход
                        .authenticationEntryPoint((req, res, ex) -> writeJson(res, 401, "Нужно войти заново"))
                        // 403: вошёл, но прав не хватает → фронт покажет ошибку, из аккаунта не выкинет
                        .accessDeniedHandler((req, res, ex) -> writeJson(res, 403, "Нет доступа")))
                .addFilterBefore(new JwtAuthFilter(jwtService, userRepository),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static void writeJson(HttpServletResponse res, int status, String message) throws IOException {
        res.setStatus(status);
        res.setCharacterEncoding("UTF-8");
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"" + message + "\"}");
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.origins:}") List<String> extraOrigins) {

        List<String> origins = new ArrayList<>(List.of(
                // локальная разработка
                "http://localhost:5173",
                "http://127.0.0.1:5173",
                // Vercel: основной домен и автоматические адреса деплоев
                "https://imfront.vercel.app",
                "https://imfront-git-master-lokuis-projects.vercel.app",
                "https://imfront-o39zli455-lokuis-projects.vercel.app",
                // все будущие деплои и превью этого проекта
                "https://imfront-*-lokuis-projects.vercel.app"
        ));
        extraOrigins.stream().filter(o -> !o.isBlank()).forEach(origins::add);

        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOriginPatterns(origins);
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Device-Id"));
        cfg.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }
}