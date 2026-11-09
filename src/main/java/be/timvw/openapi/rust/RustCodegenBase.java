/*
 * Derived from openapi-generator 7.27.0 (https://openapi-generator.tech):
 * org.openapitools.codegen.languages.AbstractRustCodegen and org.openapitools.codegen.languages.RustClientCodegen.
 *
 * Copyright 2018 OpenAPI-Generator Contributors (https://openapi-generator.tech)
 * Copyright 2018 SmartBear Software
 * Modifications copyright 2026 Tim Van Wassenhove
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modifications: only the async reqwest + reqwest-middleware client is kept (no hyper, hyper0x, reqwest-trait,
 * blocking client, single request parameter, unsigned or best-fit integers, enum name suffix, boxed-model, mockall,
 * token source, AWS v4 signature or bon builder options); the two classes are merged into one base class that reads
 * its templates from {@code timvw-rust/}.
 */

package be.timvw.openapi.rust;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.samskivert.mustache.Mustache;
import io.swagger.v3.oas.models.media.Discriminator;
import io.swagger.v3.oas.models.media.FileSchema;
import io.swagger.v3.oas.models.media.Schema;
import org.openapitools.codegen.CliOption;
import org.openapitools.codegen.CodegenConfig;
import org.openapitools.codegen.CodegenConstants;
import org.openapitools.codegen.CodegenDiscriminator;
import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenOperation;
import org.openapitools.codegen.CodegenParameter;
import org.openapitools.codegen.CodegenProperty;
import org.openapitools.codegen.CodegenResponse;
import org.openapitools.codegen.CodegenType;
import org.openapitools.codegen.DefaultCodegen;
import org.openapitools.codegen.GeneratorLanguage;
import org.openapitools.codegen.SupportingFile;
import org.openapitools.codegen.meta.features.ClientModificationFeature;
import org.openapitools.codegen.meta.features.DocumentationFeature;
import org.openapitools.codegen.meta.features.GlobalFeature;
import org.openapitools.codegen.meta.features.ParameterFeature;
import org.openapitools.codegen.meta.features.SchemaSupportFeature;
import org.openapitools.codegen.meta.features.SecurityFeature;
import org.openapitools.codegen.meta.features.WireFormatFeature;
import org.openapitools.codegen.model.ModelMap;
import org.openapitools.codegen.model.ModelsMap;
import org.openapitools.codegen.model.OperationsMap;
import org.openapitools.codegen.templating.mustache.ReplaceAllLambda;
import org.openapitools.codegen.utils.ModelUtils;
import org.openapitools.codegen.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.openapitools.codegen.utils.StringUtils.camelize;
import static org.openapitools.codegen.utils.StringUtils.escape;
import static org.openapitools.codegen.utils.StringUtils.underscore;

/**
 * The Rust language layer of {@code timvw-rust}: type mapping, identifier naming and escaping, the model and operation
 * post-processing the templates rely on, the supporting files and the crate options ({@code packageName},
 * {@code packageVersion}, {@code useChrono}, {@code useSerdePathToError}, {@code supportMultipleResponses},
 * {@code reqwestDefaultFeatures}). It renders an async {@code reqwest} client on {@code reqwest-middleware}, from the
 * templates under {@code timvw-rust/}.
 *
 * <p>Ported from openapi-generator 7.27.0's {@code AbstractRustCodegen} and {@code RustClientCodegen} (Apache License
 * 2.0), restricted to what {@code timvw-rust} generates; for that configuration it produces what those classes
 * produce. {@link TimvwRustCodegen} adds the generator's own rules on top.</p>
 */
public abstract class RustCodegenBase extends DefaultCodegen implements CodegenConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("org.openapitools.codegen.timvw.RustCodegenBase");

    /** Classpath directory holding every template the generator renders. */
    public static final String TEMPLATES = "timvw-rust";

    public static final String REQWEST_LIBRARY = "reqwest";

    public static final String PACKAGE_NAME = CodegenConstants.PACKAGE_NAME;
    public static final String PACKAGE_VERSION = CodegenConstants.PACKAGE_VERSION;
    public static final String EXTERN_CRATE_NAME = "externCrateName";
    public static final String USE_SERDE_PATH_TO_ERROR = "useSerdePathToError";
    public static final String USE_CHRONO = "useChrono";
    public static final String SUPPORT_MULTIPLE_RESPONSES = "supportMultipleResponses";
    public static final String REQWEST_DEFAULT_FEATURES = "reqwestDefaultFeatures";
    /** Cargo.toml {@code publish}, {@code repository}, {@code documentation} and {@code homepage} (as stock -g rust). */
    public static final String PUBLISH_RUST_REGISTRY = "publishRustRegistry";
    public static final String REPOSITORY_URL = "repositoryUrl";
    public static final String DOCUMENTATION_URL = "documentationUrl";
    public static final String HOME_PAGE_URL = "homePageUrl";

    /** Vendor extension holding the name a parameter is bound to inside the API function. */
    static final String VENDOR_EXTENSION_PARAM_IDENTIFIER = "x-rust-param-identifier";
    /** Operation extension of stock {@code -g rust} for one parameter struct per operation; not supported. */
    static final String GROUP_PARAMETERS = "x-group-parameters";

    /** Characters kept as they are when an identifier is sanitized. */
    private static final List<String> CHARACTERS_TO_ALLOW = Collections.singletonList("_");
    /** Keywords that cannot be raw identifiers ({@code r#self} is invalid): they get a prefix instead. */
    private static final Set<String> KEYWORDS_WITHOUT_RAW_IDENTIFIERS = Set.of("super", "self", "Self", "extern",
            "crate");

    protected String packageName = "openapi";
    protected String packageVersion = "1.0.0";
    protected String apiDocPath = "docs/";
    protected String modelDocPath = "docs/";
    protected String apiFolder = "src/apis";
    protected String modelFolder = "src/models";

    private boolean useSerdePathToError = false;
    private boolean useChrono = true;
    private boolean supportMultipleResponses = false;
    private List<String> reqwestDefaultFeatures = Arrays.asList("native-tls");

    /** The API has at least one UUID: only then does the crate depend on {@code uuid}. */
    private boolean hasUUIDs = false;
    /** The API has at least one date or date-time: only then does the crate depend on {@code chrono}. */
    private boolean usesChronoTypes = false;

    public RustCodegenBase() {
        super();

        modifyFeatureSet(features -> features
                .includeDocumentationFeatures(DocumentationFeature.Readme)
                .wireFormatFeatures(EnumSet.of(WireFormatFeature.JSON, WireFormatFeature.XML, WireFormatFeature.Custom))
                .securityFeatures(EnumSet.of(
                        SecurityFeature.BasicAuth,
                        SecurityFeature.BearerToken,
                        SecurityFeature.ApiKey,
                        SecurityFeature.OAuth2_Implicit))
                .excludeGlobalFeatures(
                        GlobalFeature.XMLStructureDefinitions,
                        GlobalFeature.Callbacks,
                        GlobalFeature.LinkObjects,
                        GlobalFeature.ParameterStyling)
                .excludeSchemaSupportFeatures(SchemaSupportFeature.Polymorphism)
                .includeParameterFeatures(ParameterFeature.Cookie)
                .includeSchemaSupportFeatures(SchemaSupportFeature.oneOf)
                .includeClientModificationFeatures(
                        ClientModificationFeature.BasePath,
                        ClientModificationFeature.UserAgent));

        // All strict and reserved keywords of https://doc.rust-lang.org/reference/keywords.html (case-sensitive).
        reservedWords = new HashSet<>(Arrays.asList(
                "as", "break", "const", "continue", "crate", "else", "enum", "extern", "false", "fn", "for",
                "if", "impl", "in", "let", "loop", "match", "mod", "move", "mut", "pub", "ref", "return",
                "self", "Self", "static", "struct", "super", "trait", "true", "type", "unsafe", "use", "where",
                "while", "async", "await", "dyn", "abstract", "become", "box", "do", "final", "macro",
                "override", "priv", "typeof", "unsized", "virtual", "yield", "try"));

        modelTemplateFiles.put("model.mustache", ".rs");
        modelDocTemplateFiles.put("model_doc.mustache", ".md");
        apiDocTemplateFiles.put("api_doc.mustache", ".md");
        apiTemplateFiles.put("api.mustache", ".rs");

        hideGenerationTimestamp = Boolean.TRUE;

        embeddedTemplateDir = templateDir = TEMPLATES;

        defaultIncludes = new HashSet<>(Arrays.asList("map", "array"));

        languageSpecificPrimitives = new HashSet<>(Arrays.asList(
                "i8", "i16", "i32", "i64",
                "u8", "u16", "u32", "u64",
                "f32", "f64", "isize", "usize",
                "char", "bool", "str", "String"));

        instantiationTypes.clear();

        typeMapping.clear();
        typeMapping.put("integer", "i32");
        typeMapping.put("long", "i64");
        typeMapping.put("number", "f64");
        typeMapping.put("float", "f32");
        typeMapping.put("double", "f64");
        typeMapping.put("boolean", "bool");
        typeMapping.put("string", "String");
        typeMapping.put("array", "Vec");
        typeMapping.put("map", "std::collections::HashMap");
        typeMapping.put("UUID", "uuid::Uuid");
        typeMapping.put("URI", "String");
        // chrono until processOpts reads useChrono
        typeMapping.put("date", "chrono::NaiveDate");
        typeMapping.put("DateTime", "chrono::DateTime<chrono::FixedOffset>");
        typeMapping.put("password", "String");
        typeMapping.put("decimal", "String");
        // reqwest's multipart file needs AsRef<Path>
        typeMapping.put("file", "std::path::PathBuf");
        typeMapping.put("binary", "crate::models::File");
        typeMapping.put("ByteArray", "String");
        typeMapping.put("object", "serde_json::Value");
        typeMapping.put("AnyType", "serde_json::Value");

        cliOptions.clear();
        cliOptions.add(new CliOption(PACKAGE_NAME, "Rust package name (convention: lowercase).")
                .defaultValue("openapi"));
        cliOptions.add(new CliOption(PACKAGE_VERSION, "Rust package version; defaults to the spec's info.version, "
                + "then 1.0.0.").defaultValue("1.0.0"));
        cliOptions.add(CliOption.newBoolean(USE_SERDE_PATH_TO_ERROR,
                "Decode responses through serde_path_to_error, so a decoding error names the path of the value",
                false));
        cliOptions.add(CliOption.newBoolean(USE_CHRONO,
                "Represent date as chrono::NaiveDate and date-time as chrono::DateTime<chrono::FixedOffset>; "
                        + "with false both are String", true));
        cliOptions.add(CliOption.newBoolean(SUPPORT_MULTIPLE_RESPONSES,
                "An operation returns ResponseContent<<Operation>Success>, an enum of every 2xx response, instead of "
                        + "the decoded body of the first", false));
        cliOptions.add(new CliOption(REQWEST_DEFAULT_FEATURES,
                "Default features of the generated crate, passed on to reqwest (comma-separated; empty for none)")
                .defaultValue("native-tls"));
        cliOptions.add(new CliOption(PUBLISH_RUST_REGISTRY,
                "Registry the crate may be published to: Cargo.toml publish = [\"<value>\"]; unset leaves it out"));
        cliOptions.add(new CliOption(REPOSITORY_URL, "Cargo.toml repository; unset leaves it out"));
        cliOptions.add(new CliOption(DOCUMENTATION_URL, "Cargo.toml documentation; unset leaves it out"));
        cliOptions.add(new CliOption(HOME_PAGE_URL, "Cargo.toml homepage; unset leaves it out"));

        supportedLibraries.put(REQWEST_LIBRARY, "HTTP client: reqwest with reqwest-middleware (async).");
        setLibrary(REQWEST_LIBRARY);
    }

    @Override
    public CodegenType getTag() {
        return CodegenType.CLIENT;
    }

    @Override
    public GeneratorLanguage generatorLanguage() {
        return GeneratorLanguage.RUST;
    }

    /**
     * Stores the library without checking it, so that {@link #processOpts} can reject anything but {@code reqwest}
     * with its own message.
     */
    @Override
    public void setLibrary(String library) {
        this.library = library;
    }

    // ---- options ---------------------------------------------------------------------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    public void processOpts() {
        super.processOpts();

        if (additionalProperties.containsKey(PACKAGE_NAME)) {
            packageName = (String) additionalProperties.get(PACKAGE_NAME);
        } else {
            packageName = "openapi";
        }

        // The spec's info.version unless packageVersion is set; 1.0.0 without either.
        if (additionalProperties.containsKey(PACKAGE_VERSION)) {
            packageVersion = (String) additionalProperties.get(PACKAGE_VERSION);
        } else if (openAPI != null && openAPI.getInfo() != null && openAPI.getInfo().getVersion() != null) {
            packageVersion = openAPI.getInfo().getVersion();
        }

        if (additionalProperties.containsKey(USE_SERDE_PATH_TO_ERROR)) {
            useSerdePathToError = convertPropertyToBoolean(USE_SERDE_PATH_TO_ERROR);
        }
        writePropertyBack(USE_SERDE_PATH_TO_ERROR, useSerdePathToError);

        if (additionalProperties.containsKey(USE_CHRONO)) {
            setUseChrono(convertPropertyToBoolean(USE_CHRONO));
        }
        writePropertyBack(USE_CHRONO, useChrono);

        if (additionalProperties.containsKey(SUPPORT_MULTIPLE_RESPONSES)) {
            supportMultipleResponses = convertPropertyToBoolean(SUPPORT_MULTIPLE_RESPONSES);
        }
        writePropertyBack(SUPPORT_MULTIPLE_RESPONSES, supportMultipleResponses);

        if (additionalProperties.containsKey(REQWEST_DEFAULT_FEATURES)) {
            Object value = additionalProperties.get(REQWEST_DEFAULT_FEATURES);
            if (value instanceof List) {
                reqwestDefaultFeatures = (List<String>) value;
            } else if (value instanceof String) {
                String str = (String) value;
                reqwestDefaultFeatures = str.isEmpty() ? new ArrayList<>() : Arrays.asList(str.split(",\\s*"));
            }
        }
        additionalProperties.put(REQWEST_DEFAULT_FEATURES, reqwestDefaultFeatures);

        additionalProperties.put(PACKAGE_NAME, packageName);
        additionalProperties.put(PACKAGE_VERSION, packageVersion);
        // The name a crate is imported by has every '-' replaced with '_'.
        additionalProperties.put(EXTERN_CRATE_NAME, packageName.replace('-', '_'));
        additionalProperties.put("apiDocPath", apiDocPath);
        additionalProperties.put("modelDocPath", modelDocPath);

        modelPackage = packageName;
        apiPackage = packageName;

        supportingFiles.add(new SupportingFile("README.mustache", "", "README.md"));
        supportingFiles.add(new SupportingFile("git_push.sh.mustache", "", "git_push.sh"));
        supportingFiles.add(new SupportingFile("gitignore.mustache", "", ".gitignore"));
        supportingFiles.add(new SupportingFile(".travis.yml", "", ".travis.yml"));
        supportingFiles.add(new SupportingFile("model_mod.mustache", modelFolder, "mod.rs"));
        supportingFiles.add(new SupportingFile("lib.mustache", "src", "lib.rs"));
        supportingFiles.add(new SupportingFile("Cargo.mustache", "", "Cargo.toml"));
        supportingFiles.add(new SupportingFile("api_mod.mustache", apiFolder, "mod.rs"));
        supportingFiles.add(new SupportingFile("configuration.mustache", apiFolder, "configuration.rs"));

        // Sanitizes a version for Cargo.toml: v1.2.3-beta => 1.2.3-beta, 5 => 5.0.0, 5.2 => 5.2.0.
        additionalProperties.put("lambdaVersion", (Mustache.Lambda) (fragment, writer) -> {
            String content = fragment.execute().trim().replace("v", "").replace("V", "");
            String[] parts = content.split("[.]");
            if (parts.length == 1) {
                content += ".0.0";
            } else if (parts.length == 2) {
                content += ".0";
            }
            writer.write(content);
        });
    }

    private void setUseChrono(boolean useChrono) {
        this.useChrono = useChrono;
        typeMapping.put("date", useChrono ? "chrono::NaiveDate" : "String");
        typeMapping.put("DateTime", useChrono ? "chrono::DateTime<chrono::FixedOffset>" : "String");
    }

    protected boolean isSupportMultipleResponses() {
        return supportMultipleResponses;
    }

    @Override
    protected ImmutableMap.Builder<String, Mustache.Lambda> addMustacheLambdas() {
        // Lifetime names are variable names without the raw identifier prefix: `#` is not valid in them.
        return super.addMustacheLambdas().put("lifetimeName", new ReplaceAllLambda("^r#", "r_"));
    }

    // ---- files -----------------------------------------------------------------------------------------------------

    @Override
    public String apiFileFolder() {
        return (outputFolder + File.separator + apiFolder).replace("/", File.separator);
    }

    @Override
    public String modelFileFolder() {
        return (outputFolder + File.separator + modelFolder).replace("/", File.separator);
    }

    @Override
    public String modelFilename(String templateName, String modelName) {
        String suffix = modelTemplateFiles().get(templateName);
        return modelFileFolder() + File.separator + toModelFilename(modelName) + suffix;
    }

    @Override
    public String apiDocFileFolder() {
        return (outputFolder + "/" + apiDocPath).replace('/', File.separatorChar);
    }

    @Override
    public String modelDocFileFolder() {
        return (outputFolder + "/" + modelDocPath).replace('/', File.separatorChar);
    }

    // ---- escaping and identifiers ------------------------------------------------------------------------------------

    @Override
    public String escapeQuotationMark(String input) {
        // remove " to avoid code injection
        return input.replace("\"", "");
    }

    @Override
    public String escapeUnsafeCharacters(String input) {
        return input.replace("*/", "*_/").replace("/*", "/_*");
    }

    /** Rust keywords are case-sensitive. */
    @Override
    public boolean isReservedWord(String word) {
        return word != null && reservedWords.contains(word);
    }

    @Override
    public String escapeReservedWord(String name) {
        if (reservedWordsMappings().containsKey(name)) {
            return reservedWordsMappings().get(name);
        }
        return "r#" + name;
    }

    @Override
    public String addRegularExpressionDelimiter(String pattern) {
        return pattern;
    }

    /** A {@code pattern} as the spec writes it: the generated {@code validate()} puts it into a raw string literal. */
    @Override
    public String toRegularExpression(String pattern) {
        return pattern;
    }

    public enum CasingType { CAMEL_CASE, SNAKE_CASE }

    /**
     * Sanitizes a Rust identifier (field, variable, type, parameter, ...): characters other than {@code [A-Za-z0-9_]}
     * are replaced, a leading digit gets {@code escapePrefix}, and a keyword becomes a raw identifier ({@code r#type})
     * or, where raw identifiers cannot be used (file names, and the keywords that do not support them), gets
     * {@code escapePrefix}.
     */
    public String sanitizeIdentifier(String name, CasingType casingType, String escapePrefix, String type,
                                     boolean allowRawIdentifiers) {
        String originalName = name;

        Function<String, String> casingFunction;
        switch (casingType) {
            case CAMEL_CASE:
                // MyIDList => my_id_list => MyIdList, and SCREAMING_SNAKE_CASE => ScreamingSnakeCase
                casingFunction = input -> camelize(underscore(input));
                break;
            case SNAKE_CASE:
                casingFunction = StringUtils::underscore;
                break;
            default:
                throw new IllegalArgumentException("Unknown CasingType");
        }

        name = name.replaceAll("[\\.\\-]", "_");

        // Special character escapes, e.g. "@type" => "At_type", without adding a trailing underscore.
        if (!Strings.isNullOrEmpty(name)) {
            boolean endedWithUnderscore = name.endsWith("_");
            name = escape(name, specialCharReplacements, CHARACTERS_TO_ALLOW, "_");
            if (!endedWithUnderscore && name.endsWith("_")) {
                name = org.apache.commons.lang3.StringUtils.chop(name);
            }
        }

        name = sanitizeName(name);
        name = casingFunction.apply(name);

        // After casing, as CamelCase strips leading underscores.
        if (name.matches("^\\d.*")) {
            name = casingFunction.apply(escapePrefix + '_' + name);
        }

        // Keywords are case-sensitive, so after casing.
        if (isReservedWord(name)) {
            if (KEYWORDS_WITHOUT_RAW_IDENTIFIERS.contains(name) || !allowRawIdentifiers) {
                name = casingFunction.apply(escapePrefix + '_' + name);
            } else {
                name = "r#" + name;
            }
        }

        String casedOriginalName = casingFunction.apply(originalName);
        if (!casedOriginalName.equals(name)) {
            // Every rename here is mechanical (a keyword as r#type, [Done] as LeftSquareBracketDoneRightSquareBracket,
            // application/json as ApplicationSlashJson) and the wire keeps the spec's name (serde rename): nothing for
            // the user to act on, and the base generator asks for some names thousands of times. Two fields of one
            // model that end up with the same name fail generation (postProcessModels).
            LOGGER.debug("{} cannot be used as a {} name. Renamed to {}", casedOriginalName, type, name);
        }
        return name;
    }

    @Override
    public String toVarName(String name) {
        if (nameMapping.containsKey(name)) {
            return nameMapping.get(name);
        }
        return sanitizeIdentifier(name, CasingType.SNAKE_CASE, "param", "field/variable", true);
    }

    @Override
    public String toParamName(String name) {
        if (parameterNameMapping.containsKey(name)) {
            return parameterNameMapping.get(name);
        }
        if (name != null && name.contains("::")) {
            // a body that is an array of uuid::Uuid is named after its items' Rust type: uuid, not uuid_colon_colon_uuid
            String type = name.contains("<") ? name.substring(0, name.indexOf('<')) : name;
            // only the outer type's path counts: HashMap<String, models::Foo> is hash_map
            name = type.contains("::") ? type.substring(type.lastIndexOf("::") + 2) : type;
        }
        return sanitizeIdentifier(name, CasingType.SNAKE_CASE, "param", "parameter", true);
    }

    @Override
    public String toOperationId(String operationId) {
        return sanitizeIdentifier(operationId, CasingType.SNAKE_CASE, "call", "method", true);
    }

    private String addModelNamePrefixAndSuffix(String name) {
        if (!Strings.isNullOrEmpty(modelNamePrefix)) {
            name = modelNamePrefix + "_" + name;
        }
        if (!Strings.isNullOrEmpty(modelNameSuffix)) {
            name = name + "_" + modelNameSuffix;
        }
        return name;
    }

    @Override
    public String toModelName(String name) {
        if (modelNameMapping.containsKey(name)) {
            return modelNameMapping.get(name);
        }
        return sanitizeIdentifier(addModelNamePrefixAndSuffix(name), CasingType.CAMEL_CASE, "model", "model", false);
    }

    @Override
    public String toModelFilename(String name) {
        return sanitizeIdentifier(addModelNamePrefixAndSuffix(name), CasingType.SNAKE_CASE, "model", "model file",
                false);
    }

    @Override
    public String toModelDocFilename(String name) {
        return toModelName(name);
    }

    @Override
    public String toEnumVarName(String name, String datatype) {
        if (enumNameMapping.containsKey(name)) {
            return enumNameMapping.get(name);
        }
        // An empty string is the variant Empty (openapi-generator issue 13453).
        if (Strings.isNullOrEmpty(name)) {
            return "Empty";
        }
        return sanitizeIdentifier(name, CasingType.CAMEL_CASE, "variant", "enum variant", true);
    }

    /** The name of an inline enum (component enums are named by {@link #toModelName}). */
    @Override
    public String toEnumName(CodegenProperty property) {
        return sanitizeIdentifier(property.baseName, CasingType.CAMEL_CASE, "enum", "enum", false);
    }

    private static final Set<String> NUMERIC_ENUM_TYPES = Set.of("integer", "number", "i8", "i16", "i32", "i64",
            "u8", "u16", "u32", "u64", "f32", "f64");

    @Override
    public String toEnumValue(String value, String datatype) {
        return NUMERIC_ENUM_TYPES.contains(datatype) ? value : escapeText(value);
    }

    @Override
    public String toEnumDefaultValue(String value, String datatype) {
        return toEnumVarName(value, datatype);
    }

    private String addApiNamePrefixAndSuffix(String name) {
        if (Strings.isNullOrEmpty(name)) {
            name = "default";
        }
        if (!Strings.isNullOrEmpty(apiNamePrefix)) {
            name = apiNamePrefix + "_" + name;
        }
        if (!Strings.isNullOrEmpty(apiNameSuffix)) {
            name = name + "_" + apiNameSuffix;
        }
        return name;
    }

    @Override
    public String toApiName(String name) {
        return sanitizeIdentifier(addApiNamePrefixAndSuffix(name), CasingType.CAMEL_CASE, "api", "API", false);
    }

    @Override
    public String toApiFilename(String name) {
        return sanitizeIdentifier(addApiNamePrefixAndSuffix(name), CasingType.SNAKE_CASE, "api", "API file", false);
    }

    // ---- types -------------------------------------------------------------------------------------------------------

    /** The Rust type of a schema; aliases are resolved first. */
    @Override
    @SuppressWarnings("rawtypes")
    public String getTypeDeclaration(Schema schema) {
        Schema p = unaliasSchema(schema);
        if (ModelUtils.isArraySchema(p)) {
            return typeMapping.get("array") + "<" + getTypeDeclaration(ModelUtils.getSchemaItems(p)) + ">";
        } else if (ModelUtils.isMapSchema(p)) {
            return typeMapping.get("map") + "<" + typeMapping.get("string") + ", "
                    + getTypeDeclaration(ModelUtils.getAdditionalProperties(p)) + ">";
        } else if (!org.apache.commons.lang3.StringUtils.isEmpty(p.get$ref())) {
            try {
                return "models::" + toModelName(ModelUtils.getSimpleRef(p.get$ref()));
            } catch (Exception e) {
                LOGGER.warn("Error obtaining the datatype from schema (model):{}. Datatype default to Object", p);
                LOGGER.error(e.getMessage(), e);
                return "Object";
            }
        } else if (p instanceof FileSchema) {
            return typeMapping.get("file");
        }

        String oasType = getSchemaType(p);
        if (typeMapping.containsKey(oasType)) {
            return typeMapping.get(oasType);
        }
        if (typeMapping.containsValue(oasType) || languageSpecificPrimitives.contains(oasType)) {
            return oasType;
        }
        return "models::" + toModelName(oasType);
    }

    /**
     * The mapped type of a schema. An integer is {@code i32} without a format, and sized and signed by its format
     * ({@code int8} to {@code int64}, {@code uint8} to {@code uint64}) otherwise; bounds never pick a type.
     */
    @Override
    @SuppressWarnings("rawtypes")
    public String getSchemaType(Schema p) {
        String schemaType = super.getSchemaType(p);
        String type = typeMapping.getOrDefault(schemaType, schemaType);
        if ("integer".equals(p.getType())) {
            if (Strings.isNullOrEmpty(p.getFormat())) {
                return "i32";
            }
            switch (p.getFormat()) {
                case "int8":
                    return "i8";
                case "int16":
                    return "i16";
                case "int32":
                    return "i32";
                case "int64":
                    return "i64";
                case "uint8":
                    return "u8";
                case "uint16":
                    return "u16";
                case "uint32":
                    return "u32";
                case "uint64":
                    return "u64";
                default:
                    break;
            }
        }
        return type;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public String toDefaultValue(Schema p) {
        return p.getDefault() != null ? p.getDefault().toString() : null;
    }

    // ---- models ------------------------------------------------------------------------------------------------------

    /**
     * Names the {@code oneOf} variants: after the discriminator mapping, the {@code $ref}, or the title or type of
     * an inline branch.
     */
    @Override
    @SuppressWarnings("rawtypes")
    public CodegenModel fromModel(String name, Schema model) {
        Map<String, Schema> allDefinitions = ModelUtils.getSchemas(this.openAPI);
        CodegenModel mdl = super.fromModel(name, model);

        mdl.vendorExtensions.put("x-upper-case-name", name.toUpperCase(Locale.ROOT));
        if (!org.apache.commons.lang3.StringUtils.isEmpty(model.get$ref())) {
            Schema schema = allDefinitions.get(ModelUtils.getSimpleRef(model.get$ref()));
            mdl.dataType = typeMapping.get(schema.getType());
        }
        if (ModelUtils.isArraySchema(model)) {
            if (typeMapping.containsKey(mdl.arrayModelType)) {
                mdl.arrayModelType = typeMapping.get(mdl.arrayModelType);
            } else {
                mdl.arrayModelType = toModelName(mdl.arrayModelType);
            }
        } else if (ModelUtils.hasAnyOf(mdl) || ModelUtils.hasOneOf(mdl)) {
            mdl.dataType = getSchemaType(model);
        }

        Schema additionalProperties = ModelUtils.getAdditionalProperties(model);
        if (additionalProperties != null) {
            mdl.additionalPropertiesType = getTypeDeclaration(additionalProperties);
        }

        if (mdl.getComposedSchemas() != null && mdl.getComposedSchemas().getOneOf() != null
                && !mdl.getComposedSchemas().getOneOf().isEmpty()) {
            nameOneOfVariants(mdl, model);
        }
        return mdl;
    }

    @SuppressWarnings("rawtypes")
    private void nameOneOfVariants(CodegenModel mdl, Schema model) {
        List<CodegenProperty> newOneOfs = mdl.getComposedSchemas().getOneOf().stream()
                .map(CodegenProperty::clone)
                .collect(Collectors.toList());
        List<Schema> schemas = ModelUtils.getInterfaces(model);
        if (newOneOfs.size() != schemas.size()) {
            throw new RuntimeException("oneOf size does not match the model");
        }

        Map<String, String> refsMapping = Optional.ofNullable(model.getDiscriminator())
                .map(Discriminator::getMapping).orElse(Collections.emptyMap());
        // Several mapping keys can point to the same $ref: group them by it.
        Map<String, List<String>> mappedNamesByRef = refsMapping.entrySet().stream()
                .collect(Collectors.groupingBy(Map.Entry::getValue,
                        Collectors.mapping(Map.Entry::getKey, Collectors.toList())));

        for (int i = 0; i < newOneOfs.size(); i++) {
            CodegenProperty oneOf = newOneOfs.get(i);
            Schema schema = schemas.get(i);
            if (mappedNamesByRef.containsKey(schema.get$ref())) {
                // a mapped name, each used once
                String mappedName = mappedNamesByRef.get(schema.get$ref()).remove(0);
                oneOf.setBaseName(mappedName);
                oneOf.setName(toModelName(mappedName));
            } else if (!org.apache.commons.lang3.StringUtils.isEmpty(schema.get$ref())) {
                String refName = ModelUtils.getSimpleRef(schema.get$ref());
                if (refName != null) {
                    oneOf.setName(toModelName(refName));
                    oneOf.setBaseName(refName);
                }
            } else if (oneOf.isArray) {
                // the item type tells arrays apart, unless a title names the variant
                String collectionWithTypeName = toModelName(schema.getType()) + oneOf.containerTypeMapped
                        + oneOf.items.baseType;
                oneOf.setName(Optional.ofNullable(schema.getTitle()).orElse(collectionWithTypeName));
            } else {
                // an inline primitive: its title, else its type, else the property's base type
                String oneOfName = Optional.ofNullable(schema.getTitle())
                        .or(() -> Optional.ofNullable(ModelUtils.getType(schema)))
                        .orElse(oneOf.baseType);
                oneOf.setName(toModelName(oneOfName));
            }
        }
        mdl.getComposedSchemas().setOneOf(newOneOfs);
    }

    @Override
    public Map<String, ModelsMap> postProcessAllModels(Map<String, ModelsMap> objs) {
        objs = super.postProcessAllModels(objs);
        for (ModelsMap modelsMap : objs.values()) {
            for (ModelMap modelMap : modelsMap.getModels()) {
                CodegenModel cm = modelMap.getModel();
                CodegenDiscriminator discriminator = cm.discriminator;
                if (discriminator == null || discriminator.getMappedModels() == null) {
                    continue;
                }
                // A mapping that names the base itself leaves no struct to wrap, and an enum-typed tag would be
                // written twice (the child's copy keeps its default): keep the inline variants.
                if (discriminator.getMappedModels().stream().anyMatch(m -> cm.name.equals(m.getSchemaName()))
                        || cm.allVars.stream().anyMatch(v -> discriminator.getPropertyBaseName().equals(v.baseName)
                        && v.getIsEnumOrRef())) {
                    discriminator.getVendorExtensions().put("x-rust-inline-variants", true);
                    continue;
                }
                // A mapped child's discriminator doubles as the union's serde tag, which is consumed before the child
                // deserializes: mark it so the template defaults it and skips it while unset.
                for (CodegenDiscriminator.MappedModel mapped : discriminator.getMappedModels()) {
                    if (mapped.getModel() == null) {
                        continue;
                    }
                    for (CodegenProperty var : mapped.getModel().vars) {
                        if (discriminator.getPropertyBaseName().equals(var.baseName)) {
                            var.isDiscriminator = true;
                        }
                    }
                }
            }
        }
        return objs;
    }

    private static final String[] EXTERNAL_TYPE_PREFIXES = {"std::", "serde_json::", "uuid::", "chrono::", "url::"};

    @Override
    public ModelsMap postProcessModels(ModelsMap objs) {
        for (ModelMap model : objs.getModels()) {
            CodegenModel cm = model.getModel();
            failOnFieldNameCollisions(cm);

            if (cm.discriminator != null) {
                // serde handles the discriminator: it is no field of the model
                String reservedVarName = cm.discriminator.getPropertyBaseName();
                for (CodegenProperty cp : cm.vars) {
                    if (cp.baseName.equals(reservedVarName)) {
                        cm.vars.remove(cp);
                        break;
                    }
                }
                // Mapping keys sharing a model get a variant each: PersonStudent, PersonTeacher.
                if (cm.discriminator.getMapping() != null && hasDuplicateValues(cm.discriminator.getMapping())) {
                    for (Map.Entry<String, List<String>> s : invertMap(cm.discriminator.getMapping()).entrySet()) {
                        if (s.getValue().size() > 1) {
                            for (CodegenDiscriminator.MappedModel m : cm.discriminator.getMappedModels()) {
                                if (s.getValue().contains(m.getMappingName())) {
                                    m.setModelName(m.getModelName() + StringUtils.camelize(m.getMappingName()));
                                }
                            }
                        }
                    }
                }
            }

            // structs with byte arrays are annotated with serde_as
            for (CodegenProperty cp : cm.vars) {
                if (cp.isByteArray) {
                    cm.vendorExtensions.put("x-rust-has-byte-array", true);
                    break;
                }
            }

            // structs with integer enum properties import serde_repr once
            if (!cm.isEnum) {
                for (CodegenProperty cp : cm.vars) {
                    if (cp.isEnum && cp.isInteger) {
                        cm.vendorExtensions.put("x-rust-has-integer-property-enum", true);
                        break;
                    }
                }
            }

            // the documented type of each property is the generated one (HashSet for uniqueItems)
            for (CodegenProperty cp : cm.vars) {
                String docType = cp.datatypeWithEnum != null && !cp.datatypeWithEnum.isEmpty()
                        ? cp.datatypeWithEnum : cp.dataType;
                if (Boolean.TRUE.equals(cp.getUniqueItems()) && docType.startsWith("Vec<")) {
                    docType = docType.replace("Vec<", "HashSet<");
                }
                cp.vendorExtensions.put("x-doc-type", docType);

                // only models of this crate are linked
                boolean shouldLink = false;
                if (cp.complexType != null && !cp.complexType.isEmpty()) {
                    shouldLink = Arrays.stream(EXTERNAL_TYPE_PREFIXES).noneMatch(cp.complexType::startsWith);
                }
                cp.vendorExtensions.put("x-should-link", shouldLink);
            }
        }
        return postProcessModelsEnum(objs);
    }

    @Override
    public void postProcessModelProperty(CodegenModel model, CodegenProperty property) {
        super.postProcessModelProperty(model, property);

        // nullable and not required is a double Option, deserialized with serde_with
        if (property.isNullable && !property.required) {
            additionalProperties.put("serdeWith", true);
        }

        // Mustache has no OR: the template asks for this flag instead
        property.vendorExtensions.put("isMandatory", !property.isNullable && property.required);

        // base64 byte arrays are deserialized with serde_with; a nullable optional one with the crate's own double
        // option, as serde_as does not work with serde_with's
        if (property.isByteArray) {
            additionalProperties.put("serdeWith", true);
            if (property.isNullable && !property.required) {
                additionalProperties.put("serdeAsDoubleOption", true);
            }
        }
    }

    // ---- operations --------------------------------------------------------------------------------------------------

    /** Binds every parameter to {@code p_<location>_<name>} inside the function, so names never collide. */
    @Override
    public void postProcessParameter(CodegenParameter parameter) {
        super.postProcessParameter(parameter);
        String location = "";
        if (parameter.isPathParam) {
            location = "path_";
        } else if (parameter.isQueryParam) {
            location = "query_";
        } else if (parameter.isHeaderParam) {
            location = "header_";
        } else if (parameter.isBodyParam) {
            location = "body_";
        } else if (parameter.isCookieParam) {
            location = "cookie_";
        } else if (parameter.isFormParam) {
            location = "form_";
        }
        String name = parameter.paramName.startsWith("r#") ? parameter.paramName.substring(2) : parameter.paramName;
        parameter.vendorExtensions.putIfAbsent(VENDOR_EXTENSION_PARAM_IDENTIFIER, "p_" + location + name);
    }

    @Override
    public OperationsMap postProcessOperationsWithModels(OperationsMap objs, List<ModelMap> allModels) {
        boolean useAsyncFileStream = false;
        for (CodegenOperation operation : objs.getOperations().getOperation()) {
            Object grouped = operation.vendorExtensions.get(GROUP_PARAMETERS);
            if (grouped != null && !Boolean.FALSE.equals(grouped)) {
                throw new IllegalArgumentException(String.format("%s: operation %s: %s is not supported: every "
                                + "parameter is an argument of the API function; remove the extension",
                        getName(), operation.operationId, GROUP_PARAMETERS));
            }

            for (CodegenParameter param : operation.allParams) {
                // An any-type path, query or header value is a string; a body keeps serde_json::Value.
                if (param.isAnyType && (param.isPathParam || param.isQueryParam || param.isHeaderParam)) {
                    param.dataType = "String";
                    param.isPrimitiveType = true;
                    param.isString = true;
                }
                // A free-form object is serde_json::Value, not a model: primitive, so no models:: prefix is added.
                if (param.isFreeFormObject && !param.isContainer) {
                    param.isPrimitiveType = true;
                }
            }

            if (operation.pathParams != null) {
                for (CodegenParameter pathParam : operation.pathParams) {
                    if (!pathParam.baseName.contains("-")) {
                        continue;
                    }
                    String newName = pathParam.baseName.replace("-", "_");
                    LOGGER.info("{} cannot be used as a path param. Renamed to {}", pathParam.baseName, newName);
                    operation.path = operation.path.replace("{" + pathParam.baseName + "}", "{" + newName + "}");
                    pathParam.baseName = newName;
                }
            }

            // A parameter, a success or an error response of uuid or chrono type (or an array or map of one): the
            // crate depends on uuid or chrono, as it does for a model property.
            if (!hasUUIDs && (usesType(operation, "uuid::Uuid") || operation.allParams.stream()
                    .anyMatch(p -> p.isUuid || (p.items != null && p.items.isUuid)))) {
                hasUUIDs = true;
            }
            if (useChrono && !usesChronoTypes && (usesType(operation, "chrono::")
                    || operation.allParams.stream().anyMatch(p -> p.isDate || p.isDateTime))) {
                usesChronoTypes = true;
            }

            // A file body or multipart file is streamed from disk: the crate needs tokio, once.
            if (!useAsyncFileStream && (operation.bodyParams.stream().anyMatch(p -> p.isFile)
                    || operation.formParams.stream().anyMatch(p -> p.isFile))) {
                useAsyncFileStream = true;
                additionalProperties.put("useAsyncFileStream", Boolean.TRUE);
                operation.vendorExtensions.put("useAsyncFileStream", Boolean.TRUE);
            }

            operation.httpMethod = operation.httpMethod.toUpperCase(Locale.ROOT);

            if (operation.producesTextPlain() && "String".equals(operation.returnType)) {
                operation.vendorExtensions.put("x-supports-plain-text", Boolean.TRUE);
            }
        }

        if (!hasUUIDs) {
            for (ModelMap map : allModels) {
                CodegenModel m = map.getModel();
                if (m.getIsUuid() || anyProperty(fieldsAndVariants(m), p -> p.isUuid
                        || (p.dataType != null && p.dataType.contains("uuid::Uuid")))) {
                    hasUUIDs = true;
                    break;
                }
            }
        }
        if (useChrono && !usesChronoTypes) {
            for (ModelMap map : allModels) {
                CodegenModel m = map.getModel();
                if (m.getIsDate() || anyProperty(fieldsAndVariants(m), p -> p.isDate || p.isDateTime
                        || (p.dataType != null && p.dataType.contains("chrono::")))) {
                    usesChronoTypes = true;
                    break;
                }
            }
        }

        additionalProperties.put("hasUUIDs", hasUUIDs);
        additionalProperties.put("usesChronoTypes", useChrono && usesChronoTypes);
        return objs;
    }

    /** Whether a parameter, the return type or a response of the operation names the Rust type {@code type}. */
    private static boolean usesType(CodegenOperation operation, String type) {
        List<String> types = new ArrayList<>();
        types.add(operation.returnType);
        for (CodegenParameter p : operation.allParams) {
            types.add(p.dataType);
            if (p.items != null) {
                types.add(p.items.dataType);
            }
        }
        if (operation.responses != null) {
            for (CodegenResponse r : operation.responses) {
                types.add(r.dataType);
            }
        }
        return types.stream().anyMatch(t -> t != null && t.contains(type));
    }

    /**
     * Two properties whose names become the same Rust field ({@code foo-bar} and {@code foo_bar} are both
     * {@code foo_bar}) would not compile (E0124): fail with both spec names, so the spec or a {@code nameMappings}
     * entry can tell them apart.
     */
    void failOnFieldNameCollisions(CodegenModel cm) {
        if (cm.vars == null) {
            return;
        }
        Map<String, String> seen = new HashMap<>();
        for (CodegenProperty p : cm.vars) {
            String other = seen.putIfAbsent(p.name, p.baseName);
            if (other != null && !other.equals(p.baseName)) {
                throw new IllegalArgumentException(String.format("%s: model %s: properties '%s' and '%s' both become "
                                + "the Rust field %s; rename one in the spec or map one with --name-mappings",
                        getName(), cm.classname, other, p.baseName, p.name));
            }
        }
    }

    /** A model's fields and, for a union, its variants (a uuid or date-time variant needs the crate too). */
    private static List<CodegenProperty> fieldsAndVariants(CodegenModel m) {
        List<CodegenProperty> all = new ArrayList<>();
        if (m.vars != null) {
            all.addAll(m.vars);
        }
        if (m.getComposedSchemas() != null) {
            for (List<CodegenProperty> list : Arrays.asList(m.getComposedSchemas().getOneOf(),
                    m.getComposedSchemas().getAnyOf(), m.getComposedSchemas().getAllOf())) {
                if (list != null) {
                    all.addAll(list);
                }
            }
        }
        return all;
    }

    /** Whether a property, its items, its map values or its nested properties satisfy {@code check}. */
    private static boolean anyProperty(List<CodegenProperty> properties, Function<CodegenProperty, Boolean> check) {
        for (CodegenProperty property : properties) {
            if (check.apply(property)) {
                return true;
            }
            if (property.items != null && anyProperty(Collections.singletonList(property.items), check)) {
                return true;
            }
            if (property.additionalProperties != null
                    && anyProperty(Collections.singletonList(property.additionalProperties), check)) {
                return true;
            }
            if (property.vars != null && anyProperty(property.vars, check)) {
                return true;
            }
        }
        return false;
    }

    static <K, V> Map<V, List<K>> invertMap(Map<K, V> map) {
        Map<V, List<K>> inverted = new HashMap<>();
        for (Map.Entry<K, V> entry : map.entrySet()) {
            inverted.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());
        }
        return inverted;
    }

    static <K, V> boolean hasDuplicateValues(Map<K, V> map) {
        return new HashSet<>(map.values()).size() < map.size();
    }
}
