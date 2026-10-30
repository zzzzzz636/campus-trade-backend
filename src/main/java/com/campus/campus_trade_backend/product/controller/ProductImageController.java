package com.campus.campus_trade_backend.product.controller;

import com.campus.campus_trade_backend.product.dto.ProductImageResponse;
import com.campus.campus_trade_backend.product.service.ProductImageService;
import jakarta.validation.constraints.Min;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.List;

import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.HttpStatus.NO_CONTENT;

@RestController
public class ProductImageController {
    private final ProductImageService imageService;

    public ProductImageController(ProductImageService imageService) {
        this.imageService = imageService;
    }

    @PostMapping(value = "/api/products/{productId}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(CREATED)
    public List<ProductImageResponse> upload(@AuthenticationPrincipal Jwt jwt,
                                             @PathVariable @Min(1) long productId,
                                             @RequestPart("files") List<MultipartFile> files) {
        return imageService.upload(productId, Long.parseLong(jwt.getSubject()), files);
    }

    @DeleteMapping("/api/products/{productId}/images/{imageId}")
    @ResponseStatus(NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable @Min(1) long productId,
                       @PathVariable @Min(1) long imageId) {
        imageService.delete(productId, imageId, Long.parseLong(jwt.getSubject()));
    }

    @GetMapping("/api/product-images/{imageId}/content")
    public ResponseEntity<Resource> content(@PathVariable @Min(1) long imageId) {
        ProductImageService.ImageContent image = imageService.content(imageId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .contentLength(image.size())
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                .body(image.resource());
    }
}
