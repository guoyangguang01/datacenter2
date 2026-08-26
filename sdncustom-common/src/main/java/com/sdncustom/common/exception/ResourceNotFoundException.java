package com.sdncustom.common.exception;

public class ResourceNotFoundException extends BusinessException {

    public ResourceNotFoundException(String resource, String id) {
        super(404, String.format("%s not found: %s", resource, id));
    }
}
