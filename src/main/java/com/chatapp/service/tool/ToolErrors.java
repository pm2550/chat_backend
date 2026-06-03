package com.chatapp.service.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Small shared helpers for the workspace agent tools: consistent error nodes + id parsing. */
final class ToolErrors {

    private ToolErrors() {
    }

    /** Build a soft-error result: {"error": {"code": ..., "message": ...}}. */
    static ObjectNode node(ObjectMapper objectMapper, String code, String message) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode error = root.putObject("error");
        error.put("code", code);
        error.put("message", message != null ? message : code);
        return root;
    }

    /** A required positive id param; throws {@link ToolExecutionException} when missing/invalid. */
    static Long requiredId(JsonNode params, String field) {
        JsonNode node = params.path(field);
        if (node.isMissingNode() || node.isNull()) {
            throw new ToolExecutionException("invalid_params", field + " is required");
        }
        return positiveId(node, field);
    }

    /**
     * An optional id param: null only when truly absent/null. A value that is PRESENT but
     * invalid (0, negative, non-integer) is an error, NOT silently treated as "absent" — that
     * would, for example, turn a bad file_id into an accidental create instead of an edit.
     */
    static Long optionalId(JsonNode params, String field) {
        JsonNode node = params.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        return positiveId(node, field);
    }

    private static Long positiveId(JsonNode node, String field) {
        long value;
        if (node.isIntegralNumber()) {
            value = node.asLong();
        } else if (node.isTextual() && node.asText().trim().matches("\\d+")) {
            // LLMs sometimes pass ids as strings; accept a plain digit string.
            value = Long.parseLong(node.asText().trim());
        } else {
            throw new ToolExecutionException("invalid_params", field + " must be a positive integer");
        }
        if (value <= 0) {
            throw new ToolExecutionException("invalid_params", field + " must be a positive integer");
        }
        return value;
    }
}
