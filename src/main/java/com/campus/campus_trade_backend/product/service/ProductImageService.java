package com.campus.campus_trade_backend.product.service;

import com.campus.campus_trade_backend.product.dto.ProductImageResponse;
import com.campus.campus_trade_backend.product.entity.ProductEntity;
import com.campus.campus_trade_backend.product.entity.ProductImageEntity;
import com.campus.campus_trade_backend.product.mapper.ProductImageMapper;
import com.campus.campus_trade_backend.product.mapper.ProductMapper;
import com.campus.campus_trade_backend.product.storage.FileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Service
public class ProductImageService {
    private static final Logger log = LoggerFactory.getLogger(ProductImageService.class);
    private static final int MAX_IMAGES = 6;
    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024;

    private final ProductMapper productMapper;
    private final ProductImageMapper imageMapper;
    private final FileStorage storage;

    public ProductImageService(ProductMapper productMapper, ProductImageMapper imageMapper, FileStorage storage) {
        this.productMapper = productMapper;
        this.imageMapper = imageMapper;
        this.storage = storage;
    }

    @Transactional
    public List<ProductImageResponse> upload(long productId, long sellerId, List<MultipartFile> files) {
        if (files == null || files.isEmpty() || files.size() > MAX_IMAGES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "每次需要上传 1 至 6 张图片");
        }
        ProductEntity product = requireEditableProduct(productId, sellerId);
        int existing = imageMapper.countByProductId(productId);
        if (existing + files.size() > MAX_IMAGES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "每件商品最多上传 6 张图片");
        }

        List<String> storedKeys = new ArrayList<>();
        registerRollbackCleanup(storedKeys);
        for (int index = 0; index < files.size(); index++) {
            MultipartFile file = files.get(index);
            ImageType type = validate(file);
            FileStorage.StoredFile stored = storage.save(file, type.extension());
            storedKeys.add(stored.storageKey());

            ProductImageEntity image = new ProductImageEntity();
            image.setProductId(product.getId());
            image.setStorageKey(stored.storageKey());
            image.setOriginalName(safeOriginalName(file.getOriginalFilename(), type.extension()));
            image.setContentType(type.contentType());
            image.setFileSize(stored.size());
            image.setSortOrder(existing + index);
            imageMapper.insert(image);
        }
        return list(productId);
    }

    @Transactional
    public void delete(long productId, long imageId, long sellerId) {
        requireEditableProduct(productId, sellerId);
        ProductImageEntity image = imageMapper.findById(imageId);
        if (image == null || image.getProductId() != productId) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品图片不存在");
        }
        if (imageMapper.delete(imageId, productId) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "商品图片删除失败，请重试");
        }
        deleteAfterCommit(image.getStorageKey());
    }

    public ImageContent content(long imageId) {
        ProductImageEntity image = imageMapper.findById(imageId);
        if (image == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品图片不存在");
        try {
            return new ImageContent(storage.load(image.getStorageKey()), image.getContentType(), image.getFileSize());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品图片文件不存在", e);
        }
    }

    public List<ProductImageResponse> list(long productId) {
        return imageMapper.listByProductId(productId).stream().map(ProductImageService::toResponse).toList();
    }

    private ProductEntity requireEditableProduct(long productId, long sellerId) {
        ProductEntity product = productMapper.findAnyByIdForUpdate(productId);
        if (product == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在");
        if (!product.getSellerId().equals(sellerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能管理自己商品的图片");
        }
        if ("RESERVED".equals(product.getStatus()) || "SOLD".equals(product.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "预订或售出商品不能修改图片");
        }
        return product;
    }

    private static ImageType validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能上传空图片");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "单张图片不能超过 5 MB");
        }
        byte[] header;
        try (InputStream input = file.getInputStream()) {
            header = input.readNBytes(12);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无法读取上传图片", e);
        }
        if (header.length >= 3 && unsigned(header[0]) == 0xFF && unsigned(header[1]) == 0xD8
                && unsigned(header[2]) == 0xFF) return ImageType.JPEG;
        if (header.length >= 8 && unsigned(header[0]) == 0x89 && header[1] == 'P' && header[2] == 'N'
                && header[3] == 'G' && unsigned(header[4]) == 0x0D && unsigned(header[5]) == 0x0A
                && unsigned(header[6]) == 0x1A && unsigned(header[7]) == 0x0A) return ImageType.PNG;
        if (header.length >= 12 && header[0] == 'R' && header[1] == 'I' && header[2] == 'F'
                && header[3] == 'F' && header[8] == 'W' && header[9] == 'E'
                && header[10] == 'B' && header[11] == 'P') return ImageType.WEBP;
        throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "仅支持真实内容为 JPEG、PNG 或 WebP 的图片");
    }

    private static int unsigned(byte value) { return value & 0xFF; }

    private static String safeOriginalName(String original, String extension) {
        String name = original == null ? "image" + extension : original.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.isBlank()) name = "image" + extension;
        return name.length() <= 255 ? name : name.substring(name.length() - 255);
    }

    private static ProductImageResponse toResponse(ProductImageEntity image) {
        return new ProductImageResponse(image.getId(), "/api/product-images/" + image.getId() + "/content",
                image.getOriginalName(), image.getContentType(), image.getFileSize(), image.getSortOrder());
    }

    private void registerRollbackCleanup(List<String> storedKeys) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    storedKeys.forEach(ProductImageService.this::deleteQuietly);
                }
            }
        });
    }

    private void deleteAfterCommit(String storageKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() { deleteQuietly(storageKey); }
        });
    }

    private void deleteQuietly(String storageKey) {
        try { storage.delete(storageKey); }
        catch (RuntimeException e) { log.warn("无法清理商品图片文件 storageKey={}", storageKey, e); }
    }

    private enum ImageType {
        JPEG("image/jpeg", ".jpg"), PNG("image/png", ".png"), WEBP("image/webp", ".webp");
        private final String contentType;
        private final String extension;
        ImageType(String contentType, String extension) {
            this.contentType = contentType;
            this.extension = extension;
        }
        String contentType() { return contentType; }
        String extension() { return extension; }
    }

    public record ImageContent(Resource resource, String contentType, long size) {
    }
}
