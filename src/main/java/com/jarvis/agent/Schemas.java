package com.jarvis.agent;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Pequeñas ayudas para describir los parámetros de las herramientas (esquema JSON). */
public final class Schemas {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private Schemas() {}

    /** Esquema de objeto con las propiedades dadas. required = todas. */
    public static ObjectNode object(ObjectNode properties, String... required) {
        ObjectNode s = F.objectNode();
        s.put("type", "object");
        s.set("properties", properties);
        ArrayNode req = s.putArray("required");
        for (String r : required) req.add(r);
        return s;
    }

    public static ObjectNode props() { return F.objectNode(); }

    public static ObjectNode string(String desc) {
        ObjectNode p = F.objectNode();
        p.put("type", "string");
        p.put("description", desc);
        return p;
    }

    public static ObjectNode integer(String desc) {
        ObjectNode p = F.objectNode();
        p.put("type", "integer");
        p.put("description", desc);
        return p;
    }
}
