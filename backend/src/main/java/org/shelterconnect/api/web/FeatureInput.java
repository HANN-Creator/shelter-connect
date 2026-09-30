package org.shelterconnect.api.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;

public final class FeatureInput {
    private FeatureInput() {}
    public static UUID id(String value) {
        try { UUID id = UUID.fromString(value); if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException(); return id; }
        catch (IllegalArgumentException | NullPointerException ex) { throw FeatureException.invalid(); }
    }
    public static void fields(JsonNode body, String... allowed) {
        if (body == null || !body.isObject()) throw FeatureException.invalid();
        var names = Set.of(allowed);
        if (body.properties().stream().anyMatch(e -> !names.contains(e.getKey()))) throw FeatureException.invalid();
    }
    public static String text(JsonNode body, String field, int max) {
        var value = body.path(field);
        if (!value.isString()) throw FeatureException.invalid();
        String text = value.asString().strip();
        if (text.isEmpty() || text.codePointCount(0,text.length()) > max || text.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t')) throw FeatureException.invalid();
        return text;
    }
    public static String query(String value) {
        if (value == null) return "";
        if (value.length() > 100 || value.chars().anyMatch(Character::isISOControl)) throw FeatureException.invalid();
        return value.strip();
    }
    public static int limit(String value) {
        if (value == null) return 20;
        if (!value.matches("[0-9]{1,2}") || Integer.parseInt(value) < 1 || Integer.parseInt(value) > 50) throw FeatureException.invalid();
        return Integer.parseInt(value);
    }
    public static Double coordinate(String value, double bound) {
        if (value == null) return null;
        try { double n=Double.parseDouble(value); if(!Double.isFinite(n) || Math.abs(n)>bound) throw new IllegalArgumentException(); return n; }
        catch (IllegalArgumentException ex) { throw FeatureException.invalid(); }
    }
    public static Instant instant(String value) {
        try { var at=Instant.parse(value); if(at.isBefore(Instant.EPOCH) || at.isAfter(Instant.parse("9999-12-31T23:59:59Z")) || at.getNano()%1000!=0) throw new IllegalArgumentException(); return at; }
        catch (RuntimeException ex) { throw FeatureException.invalid(); }
    }
    public static String scope(Object... values) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Arrays.toString(values).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    public static String cursor(String scope, String position) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((scope+"\n"+position).getBytes(StandardCharsets.UTF_8));
    }
    public static String position(String cursor, String scope) {
        if(cursor==null) return null;
        try {
            if(cursor.length()>1024 || !cursor.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
            String[] parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\n",-1);
            if(parts.length!=2 || !parts[0].equals(scope)) throw new IllegalArgumentException();
            return parts[1];
        } catch (RuntimeException ex) { throw new FeatureException(400,"INVALID_CURSOR","목록의 첫 페이지부터 다시 조회해 주세요."); }
    }
}
