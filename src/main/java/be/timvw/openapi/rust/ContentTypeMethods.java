package be.timvw.openapi.rust;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.openapitools.codegen.utils.ModelUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Milestone 3.6: one generated method per content type of an operation ({@code contentTypeMethods}) and a typed stream
 * for a {@code text/event-stream} response ({@code typedEventStreams}).
 *
 * <p>The stock generator renders one method per operation: it sends the request body as the first media type the spec
 * lists and decodes the first media type of the success response. This class plans the other methods an operation
 * gets. The existing method keeps its name and what it does (the first media types); every other combination of a
 * request media type and a response shape is a method named after the operation with a suffix per side, request first:
 * {@code create_image_edit_json} sends the JSON body, {@code create_response_stream} streams the events and
 * {@code create_image_edit_json_stream} does both.</p>
 *
 * <ul>
 *   <li>request body media types ({@code contentTypeMethods}): {@code _json} ({@code application/json} and
 *       {@code +json}), {@code _multipart} ({@code multipart/form-data}), {@code _form}
 *       ({@code application/x-www-form-urlencoded}), {@code _text} ({@code text/plain}), {@code _bytes}
 *       ({@code application/octet-stream}), otherwise the subtype ({@code application/sdp}: {@code _sdp});</li>
 *   <li>success response shapes, one method per shape rather than per media type, since media types of one shape
 *       render to the same Rust type: {@code _stream} ({@code text/event-stream}, with {@code typedEventStreams}),
 *       {@code _json}, {@code _text} (other {@code text/*}) and {@code _bytes} (anything else), the last three with
 *       {@code contentTypeMethods}.</li>
 * </ul>
 *
 * <p>An operation whose first success media type is {@code text/event-stream} has its own method return the stream:
 * decoding the body as JSON could never succeed. An event schema that models the SSE envelope ({@code event} and
 * {@code data} properties, as the Assistants API's {@code AssistantStreamEvent}) cannot be decoded from the
 * {@code data:} payload alone and gets no stream.</p>
 */
final class ContentTypeMethods {

    static final String EVENT_STREAM = "text/event-stream";

    /** The shape a response media type renders to. */
    enum Shape {
        STREAM("stream"), JSON("json"), TEXT("text"), BYTES("bytes");

        final String suffix;

        Shape(String suffix) {
            this.suffix = suffix;
        }
    }

    /**
     * One method of an operation.
     *
     * @param requestMediaType the media type of the request body, {@code null} without one
     * @param shape the shape of the success response, {@code null} without content
     * @param suffix what the method's name adds to the operation id, empty for the operation's own method
     * @param operation the operation restricted to these media types, the original for the operation's own method
     * @param otherResponse whether the response shape is not the one of the operation's own method
     */
    record Method(String requestMediaType, Shape shape, String suffix, Operation operation, boolean otherResponse) {
        boolean primary() {
            return suffix.isEmpty();
        }

        boolean eventStream() {
            return shape == Shape.STREAM;
        }
    }

    private ContentTypeMethods() {
    }

    static Shape shape(String mediaType) {
        String m = mediaType.toLowerCase(Locale.ROOT).trim();
        if (m.startsWith(EVENT_STREAM)) {
            return Shape.STREAM;
        }
        if (isJson(m)) {
            return Shape.JSON;
        }
        if (m.startsWith("text/")) {
            return Shape.TEXT;
        }
        return Shape.BYTES;
    }

    static boolean isJson(String mediaType) {
        String m = mediaType.toLowerCase(Locale.ROOT);
        int semi = m.indexOf(';');
        if (semi >= 0) {
            m = m.substring(0, semi);
        }
        m = m.trim();
        return m.equals("application/json") || m.endsWith("+json");
    }

    /** The suffix of a method that sends the request body as this media type. */
    static String requestSuffix(String mediaType) {
        String m = mediaType.toLowerCase(Locale.ROOT);
        int semi = m.indexOf(';');
        if (semi >= 0) {
            m = m.substring(0, semi);
        }
        m = m.trim();
        if (isJson(m)) {
            return "json";
        }
        switch (m) {
            case "multipart/form-data":
                return "multipart";
            case "application/x-www-form-urlencoded":
                return "form";
            case "text/plain":
                return "text";
            case "application/octet-stream":
                return "bytes";
            default:
                String subtype = m.substring(m.indexOf('/') + 1).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
                return subtype.isEmpty() ? "body" : subtype;
        }
    }

    /** Whether a body of this media type is sent by the stock template as is (JSON, a form or a multipart form). */
    static boolean stockBody(String mediaType) {
        String m = mediaType.toLowerCase(Locale.ROOT);
        return isJson(m) || m.startsWith("multipart/") || m.startsWith("application/x-www-form-urlencoded");
    }

    /**
     * The methods of an operation, its own first.
     *
     * @param contentTypeMethods one method per request media type and per response shape
     * @param typedEventStreams an event-stream response is a stream
     * @param onSkipped told why an event stream gets no method
     */
    static List<Method> plan(OpenAPI openAPI, Operation operation, ApiResponse methodResponse,
            boolean contentTypeMethods, boolean typedEventStreams, java.util.function.Consumer<String> onSkipped) {
        RequestBody body = operation.getRequestBody() == null ? null
                : ModelUtils.getReferencedRequestBody(openAPI, operation.getRequestBody());
        List<String> requestTypes = body == null || body.getContent() == null ? List.of()
                : new ArrayList<>(body.getContent().keySet());
        Content responseContent = methodResponse == null ? null
                : ModelUtils.getReferencedApiResponse(openAPI, methodResponse).getContent();
        List<String> responseTypes = responseContent == null ? List.of() : new ArrayList<>(responseContent.keySet());

        String primaryRequest = requestTypes.isEmpty() ? null : requestTypes.get(0);
        Shape primaryShape = responseTypes.isEmpty() ? null : shape(responseTypes.get(0));

        // The response shapes, in the order their first media type is listed.
        Map<Shape, String> shapes = new LinkedHashMap<>();
        for (String type : responseTypes) {
            shapes.putIfAbsent(shape(type), type);
        }
        String streamType = shapes.get(Shape.STREAM);
        boolean streamable = typedEventStreams && streamType != null;
        if (streamable && isEnvelope(openAPI, responseContent.get(streamType).getSchema())) {
            onSkipped.accept(operation.getOperationId() + ": the " + EVENT_STREAM + " schema models the SSE envelope "
                    + "(event and data), which a data: payload alone does not hold; no typed stream");
            streamable = false;
        }

        List<Shape> responseShapes = new ArrayList<>();
        if (primaryShape != null) {
            responseShapes.add(primaryShape);
        }
        for (Shape s : shapes.keySet()) {
            if (s == primaryShape) {
                continue;
            }
            if (s == Shape.STREAM ? streamable : contentTypeMethods) {
                responseShapes.add(s);
            }
        }
        List<String> requests = new ArrayList<>();
        requests.add(primaryRequest);
        if (contentTypeMethods) {
            requests.addAll(requestTypes.subList(Math.min(1, requestTypes.size()), requestTypes.size()));
        }

        List<Method> methods = new ArrayList<>();
        for (String request : requests) {
            for (Shape s : responseShapes.isEmpty() ? java.util.Collections.<Shape>singletonList(null) : responseShapes) {
                boolean ownRequest = request == null || request.equals(primaryRequest);
                boolean ownResponse = s == null || s == primaryShape;
                StringBuilder suffix = new StringBuilder();
                if (!ownRequest) {
                    suffix.append('_').append(requestSuffix(request));
                }
                if (!ownResponse) {
                    suffix.append('_').append(s.suffix);
                }
                if (ownRequest && ownResponse) {
                    // The operation's own method: what the stock generator renders. A primary event stream that cannot
                    // be decoded is left as the stock rendering.
                    Shape own = s == Shape.STREAM && !streamable ? null : s;
                    methods.add(0, new Method(request, own, "", operation, false));
                    continue;
                }
                methods.add(new Method(request, s, suffix.toString(),
                        restrict(openAPI, operation, methodResponse, body, request, s), !ownResponse));
            }
        }
        return methods;
    }

    /** A copy of the operation whose request body and method response hold only the given media types. */
    private static Operation restrict(OpenAPI openAPI, Operation operation, ApiResponse methodResponse,
            RequestBody body, String request, Shape shape) {
        Operation copy = new Operation()
                .tags(operation.getTags())
                .summary(operation.getSummary())
                .description(operation.getDescription())
                .externalDocs(operation.getExternalDocs())
                .operationId(operation.getOperationId())
                .parameters(operation.getParameters())
                .callbacks(operation.getCallbacks())
                .deprecated(operation.getDeprecated())
                .security(operation.getSecurity())
                .servers(operation.getServers());
        copy.setExtensions(operation.getExtensions() == null ? null : new LinkedHashMap<>(operation.getExtensions()));
        if (body != null) {
            RequestBody b = new RequestBody()
                    .description(body.getDescription())
                    .required(body.getRequired());
            b.setExtensions(body.getExtensions());
            Content content = new Content();
            if (request != null && body.getContent() != null) {
                content.addMediaType(request, body.getContent().get(request));
            }
            b.setContent(content);
            copy.setRequestBody(b);
        }
        ApiResponses responses = new ApiResponses();
        if (operation.getResponses() != null) {
            for (Map.Entry<String, ApiResponse> e : operation.getResponses().entrySet()) {
                ApiResponse r = e.getValue();
                if (r == methodResponse && shape != null) {
                    ApiResponse resolved = ModelUtils.getReferencedApiResponse(openAPI, r);
                    ApiResponse only = new ApiResponse()
                            .description(resolved.getDescription())
                            .headers(resolved.getHeaders())
                            .links(resolved.getLinks());
                    Content content = new Content();
                    for (Map.Entry<String, MediaType> m : resolved.getContent().entrySet()) {
                        if (shape(m.getKey()) == shape) {
                            content.addMediaType(m.getKey(), m.getValue());
                        }
                    }
                    only.setContent(content);
                    r = only;
                }
                responses.addApiResponse(e.getKey(), r);
            }
        }
        copy.setResponses(responses);
        return copy;
    }

    /**
     * Whether an event schema models the SSE envelope: an object with required {@code event} and {@code data}
     * properties, directly or as a branch of a union, as the Assistants API's {@code AssistantStreamEvent} does. The
     * {@code data:} payload is then only the {@code data} property.
     */
    static boolean isEnvelope(OpenAPI openAPI, Schema<?> schema) {
        return isEnvelope(openAPI, schema, new HashSet<>());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean isEnvelope(OpenAPI openAPI, Schema<?> schema, Set<Schema<?>> seen) {
        Schema<?> s = MultipartRules.resolve(openAPI, schema);
        if (s == null || !seen.add(s)) {
            return false;
        }
        List<String> required = s.getRequired();
        if (required != null && required.contains("event") && required.contains("data") && s.getProperties() != null
                && s.getProperties().containsKey("event") && s.getProperties().containsKey("data")) {
            return true;
        }
        for (List<Schema> branches : java.util.Arrays.asList((List<Schema>) (List) s.getOneOf(),
                (List<Schema>) (List) s.getAnyOf(), (List<Schema>) (List) s.getAllOf())) {
            if (branches == null) {
                continue;
            }
            for (Schema branch : branches) {
                if (isEnvelope(openAPI, branch, seen)) {
                    return true;
                }
            }
        }
        return false;
    }
}
