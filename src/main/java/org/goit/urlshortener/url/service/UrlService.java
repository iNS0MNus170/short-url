package org.goit.urlshortener.url.service;

import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.goit.urlshortener.common.exception.ExceptionMessages;
import org.goit.urlshortener.common.exception.ShortUrlException;
import org.goit.urlshortener.url.model.Url;
import org.goit.urlshortener.auth.model.User;
import org.goit.urlshortener.url.model.dto.UrlCreateRequest;
import org.goit.urlshortener.url.repository.UrlRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.goit.urlshortener.common.exception.ExceptionMessages.*;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UrlService {

    private final UrlRepository urlRepository;
    private final UrlValidationService urlValidator;
    private final ShortCodeGenerator shortCodeGenerator;

    @Value("${url.expiry.default-days:1}")
    private int defaultExpiryDays;

    public Page<Url> findUrlsByUser(@NotNull User user, Pageable pageable) {
        log.info("Fetching URLs for user with id={}, pageable={}", user.getId(), pageable);
        return urlRepository.findByUser(user, pageable);
    }

    @Transactional(rollbackFor = Exception.class)
    public Url createUrl(UrlCreateRequest request, @NotNull User currentUser) {
        log.info("Creating a new URL for user with id={}", currentUser.getId());
        urlValidator.validateUrl(request.originalUrl());
        log.debug("URL validation passed: {}", request.originalUrl());

        String shortCode;
        if (request.shortCode() != null && !request.shortCode().isEmpty()) {
            log.debug("Using custom shortCode: {}", request.shortCode());
            if (urlRepository.existsByShortCode(request.shortCode())) {
                throw new ShortUrlException(SHORT_CODE_ALREADY_EXISTS.getMessage());
            }
            shortCode = request.shortCode();
        } else {
            shortCode = shortCodeGenerator.generateUniqueShortCode(urlRepository::existsByShortCode);
            log.debug("Generated unique shortCode: {}", shortCode);
        }

        LocalDateTime createdAt = LocalDateTime.now();
        LocalDateTime expiresAt = createdAt.plusDays(defaultExpiryDays);

        Url newUrl = Url.builder()
                .originalUrl(request.originalUrl())
                .shortCode(shortCode)
                .createdAt(createdAt)
                .expiresAt(expiresAt)
                .clickCount(0L)
                .user(currentUser)
                .build();

        Url savedUrl = urlRepository.save(newUrl);
        log.info("URL saved successfully: id={}, shortCode={}, for user with id={}",
                savedUrl.getId(), savedUrl.getShortCode(), currentUser.getId());
        return savedUrl;
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteUrl(Long urlId, @NotNull User currentUser) {
        log.info("Request to delete URL with id={} by user with id={}", urlId, currentUser.getId());

        Url url = urlRepository.findByIdAndUser(urlId, currentUser)
                .orElseThrow(() -> new ShortUrlException(URL_NOT_FOUND_OR_UNAUTHORIZED.getMessage()));

        urlRepository.delete(url);
        log.info("URL with id={} was deleted by user with id={}", urlId, currentUser.getId());
    }

    public Url findByShortCode(String shortCode) {
        log.info("Fetching URL by shortCode={}", shortCode);

        return urlRepository.findByShortCode(shortCode)
                .orElseThrow(() -> {
                    log.warn("URL not found for shortCode={}", shortCode);
                    return new ShortUrlException(URL_NOT_FOUND_OR_UNAUTHORIZED.getMessage());
                });
    }

    @Transactional(readOnly = true)
    public Url findByIdAndUser(Long urlId, @NotNull User user) {
        log.info("Fetching URL with id={} for user with id={}", urlId, user.getId());

        return urlRepository.findByIdAndUser(urlId, user)
                .orElseThrow(() -> new ShortUrlException(URL_NOT_FOUND_OR_UNAUTHORIZED.getMessage()));
    }

    public Url getValidUrl(String shortCode) {
        Url url = urlRepository.findByShortCode(shortCode)
                .orElseThrow(() -> {
                    log.warn("URL not found or shortCode is invalid: {}", shortCode);
                    return new ShortUrlException(URL_NOT_FOUND.getMessage());
                });

        if (url.getExpiresAt() != null && url.getExpiresAt().isBefore(LocalDateTime.now())) {
            log.warn("URL with shortCode={} has expired", shortCode);
            throw new ShortUrlException(URL_EXPIRED.getMessage());
        }

        return url;
    }

    @Transactional
    public void incrementClickCount(Url url) {
        log.info("Request to increment clickCount for URL with shortCode={}", url.getShortCode());
        url.setClickCount(url.getClickCount() + 1);
        Url updatedUrl = urlRepository.save(url);
        log.info("ClickCount for URL with shortCode={} incremented to {}", url.getShortCode(), updatedUrl.getClickCount());
    }

    public Page<Url> listUrlsByStatus(@NotNull User user, @NotNull String status, @NotNull Pageable pageable) {
        log.info("Listing URLs for user id={}, status={}, pageable={}", user.getId(), status, pageable);

        return switch (status.toLowerCase()) {
            case "active" -> urlRepository.findActiveUrlsByUser(user, pageable);
            case "expired" -> urlRepository.findExpiredUrlsByUser(user, pageable);
            case "all" -> urlRepository.findByUser(user, pageable);
            default -> throw new IllegalArgumentException("Invalid status: " + status);
        };
    }

    @Transactional(rollbackFor = Exception.class)
    public Url updateUrl(Long id, Url url, @NotNull User currentUser) {
        log.info("Request to update URL with id={} by user with id={}", id, currentUser.getId());

        // Validate URL ownership
        Url existingUrl = urlRepository.findByIdAndUser(id, currentUser)
                .orElseThrow(() -> new ShortUrlException(ExceptionMessages.URL_NOT_FOUND_OR_UNAUTHORIZED.getMessage()));

        // Check if short code already exists (and belongs to a different URL)
        if (!existingUrl.getShortCode().equals(url.getShortCode()) &&
                urlRepository.existsByShortCode(url.getShortCode())) {
            throw new ShortUrlException(ExceptionMessages.SHORT_CODE_ALREADY_EXISTS.getMessage());
        }

        // Validate the new original URL format
        urlValidator.validateUrl(url.getOriginalUrl());

        // Update and save the URL
        existingUrl.setOriginalUrl(url.getOriginalUrl());
        existingUrl.setShortCode(url.getShortCode());
        Url updatedUrl = urlRepository.save(existingUrl);

        log.info("URL with id={} successfully updated by user with id={}", id, currentUser.getId());
        return updatedUrl;
    }


}
