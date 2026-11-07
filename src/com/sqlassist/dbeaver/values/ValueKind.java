package com.sqlassist.dbeaver.values;

/** What a string value contains, judged from its shape. */
public enum ValueKind {
    JSON, XML, TEXT;

    public static ValueKind detect(String content) {
        String s = content.strip();
        if ((s.startsWith("{") && s.endsWith("}")) || (s.startsWith("[") && s.endsWith("]"))) {
            return JSON;
        }
        if (s.startsWith("<") && s.endsWith(">")) {
            return XML;
        }
        return TEXT;
    }
}
