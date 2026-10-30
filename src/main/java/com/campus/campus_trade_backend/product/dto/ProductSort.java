package com.campus.campus_trade_backend.product.dto;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

public enum ProductSort {
    NEWEST,
    PRICE_ASC,
    PRICE_DESC;

    public static ProductSort from(String value) {
        try {
            return ProductSort.valueOf((value == null ? "NEWEST" : value).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "sort 仅支持 NEWEST、PRICE_ASC、PRICE_DESC");
        }
    }
}
