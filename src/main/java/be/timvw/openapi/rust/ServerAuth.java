package be.timvw.openapi.rust;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * {@code serverAuthentication} (docs/design/server-authentication.md): the security schemes whose credentials the
 * server reads, as variants of {@code Credential}, and per operation the requirements it passes to
 * {@code Authenticator::authenticate} and whether its trait method gets the claims (required) or an {@code Option} of
 * them (optional: an empty requirement among them).
 */
final class ServerAuth {

    private ServerAuth() {
    }

    /** How a scheme's credential is read: {@code bearer}, {@code basic}, {@code header}, {@code query}, {@code cookie}. */
    enum Kind { BEARER, BASIC, HEADER, QUERY, COOKIE, NONE }

    /** A security scheme of the spec: its name, its {@code Credential} variant and where its credential is. */
    record Scheme(String name, String variant, Kind kind, String key, String doc) {
    }

    /** The schemes of {@code components.securitySchemes}, in order; warns about those whose credentials are not read. */
    static Map<String, Scheme> schemes(OpenAPI openAPI, Consumer<String> warn) {
        Map<String, Scheme> schemes = new LinkedHashMap<>();
        if (openAPI.getComponents() == null || openAPI.getComponents().getSecuritySchemes() == null) {
            return schemes;
        }
        Set<String> variants = new HashSet<>();
        for (Map.Entry<String, SecurityScheme> e : openAPI.getComponents().getSecuritySchemes().entrySet()) {
            String name = e.getKey();
            SecurityScheme s = e.getValue();
            Kind kind = Kind.NONE;
            String key = null;
            String doc;
            SecurityScheme.Type type = s.getType();
            String http = s.getScheme() == null ? "" : s.getScheme().toLowerCase(Locale.ROOT);
            if (type == SecurityScheme.Type.HTTP && http.equals("bearer")
                    || type == SecurityScheme.Type.OAUTH2 || type == SecurityScheme.Type.OPENIDCONNECT) {
                kind = Kind.BEARER;
                doc = "the token of `Authorization: Bearer <token>`";
            } else if (type == SecurityScheme.Type.HTTP && http.equals("basic")) {
                kind = Kind.BASIC;
                doc = "the user name and password of `Authorization: Basic <base64>`";
            } else if (type == SecurityScheme.Type.APIKEY && s.getName() != null && s.getIn() != null) {
                key = s.getName();
                switch (s.getIn()) {
                    case HEADER -> kind = Kind.HEADER;
                    case QUERY -> kind = Kind.QUERY;
                    case COOKIE -> kind = Kind.COOKIE;
                    default -> { }
                }
                doc = "the API key in the " + s.getIn().toString().toLowerCase(Locale.ROOT) + " `" + key + "`";
            } else {
                doc = "not read: " + (type == null ? "no type" : type.toString()) + (http.isEmpty() ? "" : " " + http);
            }
            if (kind == Kind.NONE) {
                warn.accept("the credentials of the security scheme '" + name + "' (" + doc.substring(10)
                        + ") are not read; an operation that requires it gets its requirements, and no credential");
            }
            String variant = variant(name);
            while (!variants.add(variant)) {
                variant = variant + "_";
            }
            schemes.put(name, new Scheme(name, variant, kind, key, doc));
        }
        return schemes;
    }

    /** {@code ApiKeyAuth}, {@code bearerAuth} as {@code BearerAuth}, {@code api_key} as {@code ApiKey}. */
    static String variant(String name) {
        StringBuilder out = new StringBuilder();
        boolean upper = true;
        for (char c : name.toCharArray()) {
            if (!Character.isLetterOrDigit(c)) {
                upper = true;
                continue;
            }
            out.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        if (out.length() == 0 || Character.isDigit(out.charAt(0))) {
            out.insert(0, "Scheme");
        }
        return out.toString();
    }

    /**
     * The authentication of an operation, for its plan ({@code auth}, {@code authOptional}, {@code requirements},
     * {@code authSchemes}, {@code authQuery}, {@code claims}), or {@code null} when it has none: no {@code security},
     * {@code security: []}, or only empty requirements.
     */
    static Map<String, Object> operation(OpenAPI openAPI, Operation spec, Map<String, Scheme> schemes) {
        List<SecurityRequirement> security = spec.getSecurity() != null ? spec.getSecurity() : openAPI.getSecurity();
        if (security == null || security.isEmpty()) {
            return null;
        }
        boolean optional = false;
        List<String> alternatives = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        for (SecurityRequirement requirement : security) {
            if (requirement == null || requirement.isEmpty()) {
                optional = true;
                alternatives.add("&[]");
                continue;
            }
            List<String> all = new ArrayList<>();
            for (Map.Entry<String, List<String>> e : requirement.entrySet()) {
                List<String> scopes = new ArrayList<>();
                for (String scope : e.getValue() == null ? List.<String>of() : e.getValue()) {
                    scopes.add("\"" + ServerRules.literal(scope) + "\"");
                }
                all.add("super::Requirement { scheme: \"" + ServerRules.literal(e.getKey()) + "\", scopes: &["
                        + String.join(", ", scopes) + "] }");
                Scheme scheme = schemes.get(e.getKey());
                if (scheme != null && scheme.kind() != Kind.NONE) {
                    used.add(e.getKey());
                }
            }
            alternatives.add("&[" + String.join(", ", all) + "]");
        }
        if (alternatives.stream().allMatch("&[]"::equals)) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("auth", true);
        m.put("authOptional", optional);
        m.put("claims", optional ? "Option<Self::Claims>" : "Self::Claims");
        m.put("requirements", "&[" + String.join(", ", alternatives) + "]");
        List<String> names = new ArrayList<>();
        boolean query = false;
        for (String name : used) {
            names.add("\"" + ServerRules.literal(name) + "\"");
            query |= schemes.get(name).kind() == Kind.QUERY;
        }
        m.put("authSchemes", "&[" + String.join(", ", names) + "]");
        m.put("authQuery", query);
        return m;
    }

    /** The {@code Credential} variants and how {@code credentials()} reads each, for {@code src/server.rs}. */
    static List<Map<String, Object>> credentials(Map<String, Scheme> schemes) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Scheme s : schemes.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", ServerRules.literal(s.name()));
            m.put("variant", s.variant());
            m.put("doc", s.doc());
            m.put("basic", s.kind() == Kind.BASIC);
            String key = s.key() == null ? "" : ServerRules.literal(s.key());
            String push = "credentials.push(Credential::" + s.variant() + "(Secret::new(value)));";
            String read = switch (s.kind()) {
                case BEARER -> "if let Some(value) = authorization(headers, \"Bearer\") {\n            " + push
                        + "\n        }";
                case BASIC -> "if let Some((username, password)) = authorization(headers, \"Basic\").and_then(basic) {"
                        + "\n            credentials.push(Credential::" + s.variant()
                        + " { username, password: Secret::new(password) });\n        }";
                case HEADER -> "if let Some(value) = headers.get(\"" + key + "\").and_then(|v| v.to_str().ok()) {"
                        + "\n            " + push + "\n        }";
                case QUERY -> "if let Some((_, value)) = query.iter().find(|(name, _)| name == \"" + key + "\") {"
                        + "\n            " + push.replace("Secret::new(value)", "Secret::new(value.as_str())")
                        + "\n        }";
                case COOKIE -> "if let Some(value) = cookie(headers, \"" + key + "\") {\n            " + push
                        + "\n        }";
                case NONE -> null;
            };
            m.put("read", read);
            m.put("readable", read != null);
            out.add(m);
        }
        return out;
    }

    /** The {@code WWW-Authenticate} challenge of a {@code 401}: {@code Bearer} when a scheme takes a bearer token. */
    static String challenge(Map<String, Scheme> schemes) {
        if (schemes.values().stream().anyMatch(s -> s.kind() == Kind.BEARER)) {
            return "Bearer";
        }
        if (schemes.values().stream().anyMatch(s -> s.kind() == Kind.BASIC)) {
            return "Basic";
        }
        return null;
    }
}
