package com.wenting.mediaserver.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Collections;
import java.util.Map;

/**
 * JSON envelope inspired by ZLMediaKit HTTP API (code/msg/data).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ApiResponse {

    private final int code;
    private final String msg;
    private final Map<String, Object> data;

    public ApiResponse(int code, String msg, Map<String, Object> data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public int getCode() {
        return code;
    }

    public String getMsg() {
        return msg;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public static ApiResponse ok(Map<String, Object> data) {
        return new ApiResponse(0, "success", data);
    }

    public static ApiResponse error(int code, String msg) {
        return new ApiResponse(code, msg, null);
    }

    public static ApiResponse okSimple(String key, Object value) {
        return new ApiResponse(0, "success", Collections.singletonMap(key, value));
    }
}
