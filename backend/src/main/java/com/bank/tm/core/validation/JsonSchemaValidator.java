package com.bank.tm.core.validation;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Kontrat şemalarımızın kullandığı JSON Schema alt kümesi için hafif doğrulayıcı:
 * type (tekil veya dizi), properties, required, additionalProperties=false, enum, items, pattern, $ref (#/$defs/..).
 * Daha geniş bir destek gerekirse networknt/json-schema-validator ile değiştirilebilir; arayüz aynı kalır.
 */
public final class JsonSchemaValidator {

    private final JsonNode root;

    public JsonSchemaValidator(JsonNode schema) {
        this.root = schema;
    }

    public List<String> validate(JsonNode instance) {
        List<String> errors = new ArrayList<>();
        validate(root, instance, "$", errors);
        return errors;
    }

    private void validate(JsonNode schema, JsonNode node, String path, List<String> errors) {
        if (errors.size() > 50) return;
        if (schema.has("$ref")) {
            validate(resolve(schema.get("$ref").asText()), node, path, errors);
            return;
        }
        if (schema.has("type") && !typeMatches(schema.get("type"), node)) {
            errors.add(path + ": beklenen tür " + schema.get("type") + ", gelen " + describe(node));
            return;
        }
        if (schema.has("enum")) {
            boolean found = false;
            for (JsonNode allowed : schema.get("enum")) {
                if (allowed.equals(node) || (allowed.isNumber() && node.isNumber()
                        && allowed.asDouble() == node.asDouble())) {
                    found = true;
                    break;
                }
            }
            if (!found) errors.add(path + ": izin verilmeyen değer " + node + " (izinli: " + schema.get("enum") + ")");
        }
        if (schema.has("pattern") && node.isTextual()
                && !Pattern.compile(schema.get("pattern").asText()).matcher(node.asText()).find()) {
            errors.add(path + ": '" + node.asText() + "' kalıba uymuyor " + schema.get("pattern").asText());
        }
        if (node.isObject()) {
            JsonNode props = schema.get("properties");
            if (schema.has("required")) {
                for (JsonNode req : schema.get("required")) {
                    if (!node.has(req.asText())) errors.add(path + ": zorunlu alan eksik '" + req.asText() + "'");
                }
            }
            JsonNode additional = schema.get("additionalProperties");
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> f = fields.next();
                if (props != null && props.has(f.getKey())) {
                    validate(props.get(f.getKey()), f.getValue(), path + "." + f.getKey(), errors);
                } else if (additional != null && additional.isBoolean() && !additional.asBoolean()) {
                    errors.add(path + ": tanımsız alan '" + f.getKey() + "'");
                }
            }
        }
        if (node.isArray() && schema.has("items")) {
            int i = 0;
            for (JsonNode item : node) {
                validate(schema.get("items"), item, path + "[" + i++ + "]", errors);
            }
        }
    }

    private JsonNode resolve(String ref) {
        if (!ref.startsWith("#/")) throw new IllegalArgumentException("Yalnızca yerel $ref desteklenir: " + ref);
        JsonNode current = root;
        for (String part : ref.substring(2).split("/")) {
            current = current.get(part);
            if (current == null) throw new IllegalArgumentException("Çözülemeyen $ref: " + ref);
        }
        return current;
    }

    private static boolean typeMatches(JsonNode type, JsonNode node) {
        if (type.isArray()) {
            for (JsonNode t : type) {
                if (typeMatches(t.asText(), node)) return true;
            }
            return false;
        }
        return typeMatches(type.asText(), node);
    }

    private static boolean typeMatches(String type, JsonNode node) {
        return switch (type) {
            case "object" -> node.isObject();
            case "array" -> node.isArray();
            case "string" -> node.isTextual();
            case "boolean" -> node.isBoolean();
            case "integer" -> node.isIntegralNumber() || (node.isNumber() && node.asDouble() == Math.rint(node.asDouble()));
            case "number" -> node.isNumber();
            case "null" -> node.isNull();
            default -> true;
        };
    }

    private static String describe(JsonNode node) {
        return node.isNull() ? "null" : node.getNodeType().name().toLowerCase();
    }
}
