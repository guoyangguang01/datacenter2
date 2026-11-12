#include <open62541/client.h>
#include <open62541/client_config_default.h>
#include <open62541/client_highlevel.h>
#include <open62541/types_generated.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

typedef struct {
    UA_Client *client;
} SdnClient;

static int64_t now_ns(void) {
    struct timespec ts;
    timespec_get(&ts, TIME_UTC);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static UA_StatusCode parse_node(const char *text, UA_NodeId *node) {
    if (text == NULL) {
        return UA_STATUSCODE_BADINVALIDARGUMENT;
    }

    UA_String encoded = UA_STRING_ALLOC(text);
    if (encoded.data == NULL) {
        return UA_STATUSCODE_BADOUTOFMEMORY;
    }

    UA_StatusCode status = UA_NodeId_parse(node, encoded);
    UA_String_clear(&encoded);
    return status;
}

void *SdnCreate(void) {
    SdnClient *handle = (SdnClient *)calloc(1, sizeof(SdnClient));
    if (handle == NULL) {
        return NULL;
    }

    handle->client = UA_Client_new();
    if (handle->client == NULL) {
        free(handle);
        return NULL;
    }

    UA_ClientConfig_setDefault(UA_Client_getConfig(handle->client));
    return handle;
}

int SdnConnect(void *raw, const char *endpoint) {
    SdnClient *handle = (SdnClient *)raw;
    if (handle == NULL || handle->client == NULL || endpoint == NULL) {
        return UA_STATUSCODE_BADINVALIDARGUMENT;
    }
    return UA_Client_connect(handle->client, endpoint);
}

int SdnDisconnect(void *raw) {
    SdnClient *handle = (SdnClient *)raw;
    if (handle == NULL || handle->client == NULL) {
        return UA_STATUSCODE_BADINVALIDARGUMENT;
    }
    UA_Client_disconnect(handle->client);
    return UA_STATUSCODE_GOOD;
}

static int write_numeric(char *output, int capacity, const char *format, double value) {
    int length = snprintf(output, (size_t)capacity, format, value);
    if (length < 0) {
        return UA_STATUSCODE_BADINTERNALERROR;
    }
    return UA_STATUSCODE_GOOD;
}

int SdnRead(void *raw, const char *nodeText, int dataType,
            char *buffer, int capacity, int *valueLength, int64_t *timestampNs) {
    SdnClient *handle = (SdnClient *)raw;
    if (handle == NULL || handle->client == NULL || nodeText == NULL ||
        buffer == NULL || valueLength == NULL || timestampNs == NULL || capacity <= 0) {
        return UA_STATUSCODE_BADINVALIDARGUMENT;
    }

    UA_NodeId node = UA_NODEID_NULL;
    UA_StatusCode status = parse_node(nodeText, &node);
    if (status != UA_STATUSCODE_GOOD) {
        return status;
    }

    UA_Variant value;
    UA_Variant_init(&value);
    status = UA_Client_readValueAttribute(handle->client, node, &value);
    UA_NodeId_clear(&node);
    if (status != UA_STATUSCODE_GOOD) {
        return status;
    }

    char text[160] = {0};
    switch (dataType) {
        case 0:
            if (UA_Variant_hasScalarType(&value, &UA_TYPES[UA_TYPES_BOOLEAN])) {
                snprintf(text, sizeof(text), "%s", *(UA_Boolean *)value.data ? "true" : "false");
            } else {
                status = UA_STATUSCODE_BADTYPEMISMATCH;
            }
            break;
        case 1:
            if (UA_Variant_hasScalarType(&value, &UA_TYPES[UA_TYPES_INT16])) {
                snprintf(text, sizeof(text), "%d", (int)*(UA_Int16 *)value.data);
            } else if (UA_Variant_hasScalarType(&value, &UA_TYPES[UA_TYPES_INT32])) {
                snprintf(text, sizeof(text), "%d", *(UA_Int32 *)value.data);
            } else {
                status = UA_STATUSCODE_BADTYPEMISMATCH;
            }
            break;
        case 2:
            if (UA_Variant_hasScalarType(&value, &UA_TYPES[UA_TYPES_INT32])) {
                snprintf(text, sizeof(text), "%d", *(UA_Int32 *)value.data);
            } else if (UA_Variant_hasScalarType(&value, &UA_TYPES[UA_TYPES_INT64])) {
                snprintf(text, sizeof(text), "%lld", (long long)*(UA_Int64 *)value.data);
            } else {
                status = UA_STATUSCODE_BADTYPEMISMATCH;
            }
            break;
        case 3:
            if (UA_Variant_hasScalarType(&value, &UA_TYPES[UA_TYPES_FLOAT])) {
                status = write_numeric(text, sizeof(text), "%.9g", *(UA_Float *)value.data);
            } else {
                status = UA_STATUSCODE_BADTYPEMISMATCH;
            }
            break;
        case 4:
            if (UA_Variant_hasScalarType(&value, &UA_TYPES[UA_TYPES_DOUBLE])) {
                status = write_numeric(text, sizeof(text), "%.17g", *(UA_Double *)value.data);
            } else if (UA_Variant_hasScalarType(&value, &UA_TYPES[UA_TYPES_FLOAT])) {
                status = write_numeric(text, sizeof(text), "%.9g", *(UA_Float *)value.data);
            } else {
                status = UA_STATUSCODE_BADTYPEMISMATCH;
            }
            break;
        case 5:
            if (UA_Variant_hasScalarType(&value, &UA_TYPES[UA_TYPES_STRING])) {
                UA_String *stringValue = (UA_String *)value.data;
                int length = (int)(stringValue->length < (size_t)capacity
                    ? stringValue->length
                    : (size_t)capacity);
                memcpy(buffer, stringValue->data, (size_t)length);
                *valueLength = length;
                *timestampNs = now_ns();
                UA_Variant_clear(&value);
                return UA_STATUSCODE_GOOD;
            }
            status = UA_STATUSCODE_BADTYPEMISMATCH;
            break;
        default:
            status = UA_STATUSCODE_BADTYPEMISMATCH;
            break;
    }

    if (status == UA_STATUSCODE_GOOD) {
        int length = (int)strnlen(text, sizeof(text));
        if (length > capacity) {
            length = capacity;
        }
        memcpy(buffer, text, (size_t)length);
        *valueLength = length;
        *timestampNs = now_ns();
    }

    UA_Variant_clear(&value);
    return status;
}

int SdnWrite(void *raw, const char *nodeText, int dataType, const char *valueText) {
    SdnClient *handle = (SdnClient *)raw;
    if (handle == NULL || handle->client == NULL || nodeText == NULL || valueText == NULL) {
        return UA_STATUSCODE_BADINVALIDARGUMENT;
    }

    UA_NodeId node = UA_NODEID_NULL;
    UA_StatusCode status = parse_node(nodeText, &node);
    if (status != UA_STATUSCODE_GOOD) {
        return status;
    }

    UA_Variant value;
    UA_Variant_init(&value);
    switch (dataType) {
        case 0: {
            UA_Boolean parsed = strcmp(valueText, "true") == 0 || strcmp(valueText, "1") == 0;
            UA_Variant_setScalarCopy(&value, &parsed, &UA_TYPES[UA_TYPES_BOOLEAN]);
            break;
        }
        case 1: {
            UA_Int16 parsed = (UA_Int16)strtol(valueText, NULL, 10);
            UA_Variant_setScalarCopy(&value, &parsed, &UA_TYPES[UA_TYPES_INT16]);
            break;
        }
        case 2: {
            UA_Int32 parsed = (UA_Int32)strtol(valueText, NULL, 10);
            UA_Variant_setScalarCopy(&value, &parsed, &UA_TYPES[UA_TYPES_INT32]);
            break;
        }
        case 3: {
            UA_Float parsed = (UA_Float)strtod(valueText, NULL);
            UA_Variant_setScalarCopy(&value, &parsed, &UA_TYPES[UA_TYPES_FLOAT]);
            break;
        }
        case 4: {
            UA_Double parsed = strtod(valueText, NULL);
            UA_Variant_setScalarCopy(&value, &parsed, &UA_TYPES[UA_TYPES_DOUBLE]);
            break;
        }
        case 5: {
            UA_String parsed = UA_STRING_ALLOC(valueText);
            if (parsed.data == NULL) {
                UA_NodeId_clear(&node);
                return UA_STATUSCODE_BADOUTOFMEMORY;
            }
            UA_Variant_setScalarCopy(&value, &parsed, &UA_TYPES[UA_TYPES_STRING]);
            UA_String_clear(&parsed);
            break;
        }
        default:
            UA_NodeId_clear(&node);
            return UA_STATUSCODE_BADTYPEMISMATCH;
    }

    status = UA_Client_writeValueAttribute(handle->client, node, &value);
    UA_Variant_clear(&value);
    UA_NodeId_clear(&node);
    return status;
}

void SdnDestroy(void *raw) {
    SdnClient *handle = (SdnClient *)raw;
    if (handle == NULL) {
        return;
    }

    if (handle->client != NULL) {
        UA_Client_delete(handle->client);
        handle->client = NULL;
    }
    free(handle);
}
