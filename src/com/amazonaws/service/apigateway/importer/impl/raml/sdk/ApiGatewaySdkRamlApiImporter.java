package com.amazonaws.service.apigateway.importer.impl.raml.sdk;

import com.amazonaws.service.apigateway.importer.RamlApiImporter;
import com.amazonaws.service.apigateway.importer.impl.GenericApiImporter;
import com.amazonaws.services.apigateway.model.*;
import com.amazonaws.services.apigateway.model.Resource;
import com.google.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.raml.model.*;

import java.util.*;

import static com.amazonaws.service.apigateway.importer.util.PatchUtils.createAddOperation;


import static java.lang.String.format;

/**
 * Created by daryl on 15-07-21.
 */
public class ApiGatewaySdkRamlApiImporter extends GenericApiImporter implements RamlApiImporter {

    private static final Log LOG = LogFactory.getLog(ApiGatewaySdkRamlApiImporter.class);
    private static final String NAME_SANITIZE_REGEX = "[^A-Za-z0-9-]";
    private static final String DEFAULT_RESPONSE_STATUS = "500";
    private static final String DEFAULT_RESPONSE_MODEL = "Empty";

    @Inject
    private Raml raml;

    public String getEndpointBaseURI() {
        return endpointBaseURI;
    }

    public void setEndpointBaseURI(String endpointBaseURI) {
        this.endpointBaseURI = endpointBaseURI;
    }


    private String endpointBaseURI;

    @Override
    public String createApi(Raml raml, String name) {
        this.raml = raml;

        final RestApi api = createApi(getApiName(raml, name), "Imported from RAML");

        try {
            final Resource rootResource = getRootResource(api).get();
            createModels(api, raml.getConsolidatedSchemas());
            createResources(api, rootResource, raml.getBasePath(), raml.getResources(), true);
        } catch (Throwable t) {
            getLog().error("Error creating API, rolling back", t);
            rollback(api);
            throw t;
        }
        return api.getId();
    }

    private void createModels(RestApi api, Map<String, String> consolidatedSchemas) {
        for (Map.Entry<String, String> entry : consolidatedSchemas.entrySet()) {
            String schemaType = raml.getMediaType();
            String schema = entry.getValue();
            if (schemaType == null) {
                schemaType = schema.startsWith("{") ? "application/json" : "application/xml";
            }
            createModel(api, entry.getKey(), "Automatically imported", schema, schemaType);
        }

    }

    @Override
    public void updateApi(String apiId, Raml raml) {
        throw new UnsupportedOperationException("Not currently supported");
    }


    @Override
    protected Log getLog() {
        return LOG;
    }


    private void createResources(RestApi api, Resource rootResource, String basePath, Map<String, org.raml.model.Resource> paths, boolean createMethods) {
        //build path tree

        for (Map.Entry<String, org.raml.model.Resource> entry : paths.entrySet()) {

            // create the resource tree
            Resource parentResource = rootResource;
            String parentPart = null;

            System.out.println(entry.getValue().getResolvedUriParameters().keySet());

            final String fullPath = buildResourcePath(basePath, entry.getKey())
                    .replaceAll("\\{version\\}", raml.getVersion());    // prepend the base path to all paths
            final String[] parts = fullPath.split("/");

            for (int i = 1; i < parts.length; i++) { // exclude root resource as this will be created when the api is created
                parentResource = createResource(api, parentResource.getId(), parentPart, parts[i]);
                parentPart = parts[i];
            }

            createResources(api, rootResource, fullPath, entry.getValue().getResources(), createMethods);

            // Create resources for children
            if (createMethods) {
                // create methods on the leaf resource for each path
                createMethods(api, parentResource, entry.getValue());
            }
        }
    }

    private String getApiName(Raml raml, String fileName) {
        StringBuilder title = new StringBuilder();
        title.append(StringUtils.isNotBlank(raml.getTitle()) ? raml.getTitle() : fileName);
        if (StringUtils.isNotBlank(raml.getVersion())) title.append("V" + raml.getVersion());

        return title.toString();
    }

    private void createMethods(final RestApi api, final Resource resource, org.raml.model.Resource path) {
        final Map<ActionType, Action> actions = path.getActions();

        actions.entrySet().forEach(x -> {
            getLog().info(format("Creating method for api id %s and resource id %s with method %s", api.getId(), resource.getId(), x.getKey()));
            createMethod(api, resource, x.getKey(), x.getValue());

            sleep();
        });
    }

    public void createMethod(RestApi api, Resource resource, ActionType httpMethod, Action action) {
        PutMethodInput input = new PutMethodInput();

        input.setApiKeyRequired(isApiKeyRequired(action));
        input.setAuthorizationType("NONE");

        // create method
        Method method = resource.putMethod(input, httpMethod.toString());

        createMethodResponses(api, method, action.getResponses());
        createMethodParameters(api, method, action);
        createIntegration(method, action);
    }

    private void createIntegration(Method method, Action action) {
        //TODO: Create an integration Map json or YAML file
        Map<String, String> requestParameters = new HashMap<>();

        action.getQueryParameters().entrySet().forEach(entry -> {
            String name = entry.getKey();
            String integrationExpression = "integration.request.querystring." + name;
            requestParameters.put(integrationExpression, getMethodRequestQuerystring(name));
        });

        action.getResource().getUriParameters().entrySet().forEach(entry -> {
            String name = entry.getKey();
            String integrationExpression = "integration.request.path." + name;
            requestParameters.put(integrationExpression, getMethodRequestPath(name));
        });

        PutIntegrationInput input = new PutIntegrationInput()
                .withType(IntegrationType.HTTP)
                .withUri(getEndpointBaseURI() + action.getResource().getRelativeUri())
                .withHttpMethod(action.getType().name())
                .withRequestParameters(requestParameters);
        Integration integration = method.putIntegration(input);
        createIntegrationResponse(integration, action);
    }

    private void createIntegrationResponse(Integration integration, Action action) {
        Map<String, Response> responses = action.getResponses();
        if (!responses.containsKey(DEFAULT_RESPONSE_STATUS))
            addDefaultResponse(responses);
        responses.entrySet().forEach( entry -> {
            String status = entry.getKey();
            String pattern = status ==  DEFAULT_RESPONSE_STATUS ? null : status;
            Map<String, String> responseParameters = new HashMap<>();
            entry.getValue().getHeaders().entrySet().forEach( headerEntry -> {
                String name = headerEntry.getKey();
                responseParameters.put("integration.response.headers." + name, getMethodResponseHeader(name));
            });
            PutIntegrationResponseInput input = new PutIntegrationResponseInput()
                    .withResponseParameters(responseParameters)
                    .withSelectionPattern(pattern);
            integration.putIntegrationResponse(input, status);
        });
    }

    private void createMethodParameters(RestApi api, Method method, Action action) {
        List<PatchOperation> operations = new ArrayList<>();

        action.getQueryParameters().entrySet().forEach(entry -> {
            String name = entry.getKey();
            String expression = getMethodRequestQuerystring(name);
            operations.add(createAddOperation(expression, String.valueOf(entry.getValue().isRequired())));
        });

        action.getResource().getUriParameters().entrySet().forEach(entry -> {
            String name = entry.getKey();
            String expression = getMethodRequestPath(name);
            operations.add(createAddOperation(expression, String.valueOf(entry.getValue().isRequired())));
        });
        if (!operations.isEmpty()) {
            PatchDocument doc = new PatchDocument().withPatchOperations(operations);
            method.updateMethod(doc);
        }
    }

    private String getMethodRequestPath(String name) {
        return "method.request.path." + name;
    }

    private String getMethodRequestQuerystring(String name) {
        return "method.request.querystring." + name;
    }

    private void createMethodResponses(RestApi api, Method method, Map<String, Response> responses) {
        if (responses == null) {
            responses = new HashMap<>();
        }
        //Add default response if it doesn't exist.
        if (!responses.containsKey(DEFAULT_RESPONSE_STATUS)) {
            addDefaultResponse(responses);
        }

        responses.entrySet().forEach(entry -> {
            String status = entry.getKey();
            getLog().info(format("Creating method response for api %s and method %s and status %s",
                    api.getId(),
                    method.getHttpMethod(),
                    status));
            final PutMethodResponseInput input = createResponseInput(api, status, entry.getValue());
            method.putMethodResponse(input, status);

        });
    }

    private void addDefaultResponse(Map<String, Response> responses) {
        Response response = new Response();
        MimeType mimeType = new MimeType();
        mimeType.setSchema(DEFAULT_RESPONSE_MODEL);
        Map<String, MimeType> body = new HashMap<>();
        body.put("application/json", mimeType);
        response.setBody(body);
        responses.put(DEFAULT_RESPONSE_STATUS, response);
    }

    private PutMethodResponseInput createResponseInput(RestApi api, String status, Response response) {
        final PutMethodResponseInput input = new PutMethodResponseInput();

        //set response headers
        if (response.getHeaders() != null) {
            HashMap<String, Boolean> responseParameters = new HashMap<>();
            response.getHeaders().entrySet().forEach(entry -> {
                String name = entry.getKey();
                responseParameters.put(getMethodResponseHeader(name), entry.getValue().isRequired());
            });
            input.setResponseParameters(responseParameters);
        }

        if (response.hasBody()) {
            Map<String, String> responseModels = new HashMap<>();
            response.getBody().entrySet().forEach(entry -> {
                String schema = entry.getValue().getSchema();
                String contentType = entry.getKey();
                if (schema != null) {
                    String modelName;
                    Optional<Model> modelOpt = getModel(api, schema);

                    if (modelOpt.isPresent()) {
                        modelName = schema;
                    } else {
                        modelName = (status + "-" + contentType).replaceAll(NAME_SANITIZE_REGEX, "-");
                        createModel(api, modelName, "Automatically Imported", schema, contentType);
                    }
                    responseModels.put(contentType, modelName);
                }
            });
            if (!responseModels.isEmpty()) {
                input.setResponseModels(responseModels);
            }
        }

        return input;
    }

    private String getMethodResponseHeader(String name) {
        return "method.response.header." + name;
    }

    private boolean isApiKeyRequired(Action action) {
        // TODO: Use a more robust way of tagging the security schema
        for (SecurityReference securityReference : action.getSecuredBy()) {
            return "api_key".equals(securityReference.getName());
        }
        return false;
    }
}