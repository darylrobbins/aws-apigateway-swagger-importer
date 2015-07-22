package com.amazonaws.service.apigateway.importer.impl.raml.sdk;

import com.amazonaws.service.apigateway.importer.RamlApiImporter;
import com.amazonaws.services.apigateway.model.*;
import com.amazonaws.services.apigateway.model.Resource;
import com.google.inject.Inject;
import com.wordnik.swagger.models.Operation;
import com.wordnik.swagger.models.Path;
import com.wordnik.swagger.models.Swagger;
import com.wordnik.swagger.models.auth.SecuritySchemeDefinition;
import com.wordnik.swagger.models.parameters.BodyParameter;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.raml.model.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.lang.String.format;

/**
 * Created by daryl on 15-07-21.
 */
public class ApiGatewaySdkRamlApiImporter implements RamlApiImporter {

    private static final Log LOG = LogFactory.getLog(ApiGatewaySdkRamlApiImporter.class);
    private static final String DEFAULT_PRODUCES_CONTENT_TYPE = "application/json";
    private static final String EXTENSION_AUTH = "x-amazon-apigateway-auth";
    private static final String EXTENSION_INTEGRATION = "x-amazon-apigateway-integration";

    @Inject
    private ApiGateway apiGateway;
    private Raml raml;


    @Override
    public String createApi(Raml raml, String name) {
        this.raml = raml;

        final RestApi api = createApi(getApiName(raml, name), "Imported from RAML");

        try {
            final Resource rootResource = getRootResource(api).get();
            // deleteDefaultModels(api);
            createModels(api, raml.getConsolidatedSchemas(), raml.getMediaType());
            createResources(api, rootResource, raml.getBasePath(), raml.getMediaType(), raml.getResources(), true);
        } catch (Throwable t) {
            LOG.error("Error creating API, rolling back", t);
            rollback(api);
            throw t;
        }
        return api.getId();
    }

    private void createModels(RestApi api, Map<String, String> consolidatedSchemas, String mediaType) {
        for (Map.Entry<String, String> entry : consolidatedSchemas.entrySet()) {
            createModel(api, entry.getKey(), "Automatically imported", entry.getValue(), mediaType);
        }

    }

    private void createModel(RestApi api, String modelName, String description, String schema, String modelContentType) {

        CreateModelInput input = new CreateModelInput();

        input.setName(modelName);
        input.setDescription(description);
        input.setContentType(modelContentType);
        input.setSchema(schema);

        LOG.error("Creating model " + modelName);

        api.createModel(input);
    }

    @Override
    public void updateApi(String apiId, Raml raml) {
        throw new UnsupportedOperationException("Not currently supported");
    }

    @Override
    public void deploy(String apiId, String deploymentStage) {
        throw new UnsupportedOperationException("Not currently supported");
    }

    @Override
    public void deleteApi(String apiId) {
        deleteApi(apiGateway.getRestApiById(apiId));
    }


    private void deleteApi(RestApi api) {
        LOG.info("Deleting API " + api.getId());
        api.deleteRestApi();
    }


    private void rollback(RestApi api) {
        deleteApi(api);
    }

    private RestApi createApi(String name, String description) {
        LOG.info("Creating API with name " + name);

        CreateRestApiInput input = new CreateRestApiInput();
        input.setName(name);
        input.setDescription(description);

        return apiGateway.createRestApi(input);
    }

    private void createResources(RestApi api, Resource rootResource, String basePath, String apiProduces, Map<String, org.raml.model.Resource> paths, boolean createMethods) {
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

            createResources(api, rootResource, fullPath, raml.getMediaType(), entry.getValue().getResources(), createMethods);

            // Create resources for children
            if (createMethods) {
                // create methods on the leaf resource for each path
                createMethods(api, parentResource, entry.getValue(), apiProduces);
            }
        }
    }

    private String getApiName(Raml raml, String fileName) {
        StringBuilder title = new StringBuilder();
        title.append(StringUtils.isNotBlank(raml.getTitle()) ? raml.getTitle() : fileName);
        if (StringUtils.isNotBlank(raml.getVersion())) title.append("V" + raml.getVersion());

        return title.toString();
    }

    private Optional<Resource> getRootResource(RestApi api) {
        for (Resource r : api.getResources().getItem()) {
            if ("/".equals(r.getPath())) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    private void createMethods(final RestApi api, final Resource resource, org.raml.model.Resource path, String apiProduces) {
        final Map<ActionType, Action> actions = path.getActions();

        actions.entrySet().forEach(x -> {
            LOG.info(format("Creating method for api id %s and resource id %s with method %s", api.getId(), resource.getId(), x.getKey()));
            createMethod(api, resource, x.getKey(), x.getValue(), apiProduces);

            sleep();
        });
    }

    /**
     * Build the full resource path, including base path, add any missing leading '/', remove any trailing '/',
     * and remove any double '/'
     * @param basePath the base path
     * @param resourcePath the resource path
     * @return the full path
     */
    String buildResourcePath(String basePath, String resourcePath) {
        if (basePath == null) {
            basePath = "";
        }
        String base = trimSlashes(basePath);
        if (!base.equals("")) {
            base = "/" + base;
        }
        String result = StringUtils.removeEnd(base + "/" + trimSlashes(resourcePath), "/");
        if (result.equals("")) {
            result = "/";
        }
        return result;
    }

    private String trimSlashes(String path) {
        return StringUtils.removeEnd(StringUtils.removeStart(path, "/"), "/");
    }

    private void sleep() {
        try {
            Thread.sleep(500);  // todo: temporary hack to get around throttling limits - sdk should backoff and retry when throttled
        } catch (InterruptedException ignored) {}
    }

    /*
     * Get the content-type to use for models and responses based on the method "produces" or the api "produces" content-types
     *
     * First look in the method produces and favor application/json, otherwise return the first method produces type
     * If no method produces, fall back to api produces and favor application/json, otherwise return the first api produces type
     * If no produces are defined on the method or api, default to application/json
     */
    // todo: check this logic for apis/methods producing multiple content-types
    // note: assumption - models in an api will always use one of the api "produces" content types, favoring application/json. models created from operation responses may use the operation "produces" content type
    private String getProducesContentType(List<String> apiProduces, List<String> methodProduces) {

        if (methodProduces != null && !methodProduces.isEmpty()) {
            if (methodProduces.stream().anyMatch(t -> t.equalsIgnoreCase(DEFAULT_PRODUCES_CONTENT_TYPE))) {
                return DEFAULT_PRODUCES_CONTENT_TYPE;
            }

            return methodProduces.get(0);
        }

        if (apiProduces != null && !apiProduces.isEmpty()) {
            if (apiProduces.stream().anyMatch(t -> t.equalsIgnoreCase(DEFAULT_PRODUCES_CONTENT_TYPE))) {
                return DEFAULT_PRODUCES_CONTENT_TYPE;
            }

            return apiProduces.get(0);
        }

        return DEFAULT_PRODUCES_CONTENT_TYPE;
    }

    private Resource createResource(RestApi api, String parentResourceId, String parentPart, String part) {
        final Optional<Resource> existingResource = getResource(api, parentResourceId, part);

        // create resource if doesn't exist
        if (!existingResource.isPresent()) {
            LOG.info("Creating resource '" + part + "' with parent '" + parentPart + "'");
            sleep();
            return createResource(api, parentResourceId, part);
        } else {
            return existingResource.get();
        }
    }

    private Resource createResource(RestApi api, String parentResourceId, String pathPart) {
        CreateResourceInput input = new CreateResourceInput();
        input.setPathPart(pathPart);

        Resource resource = api.getResourceById(parentResourceId);

        return resource.createResource(input);
    }


    private Optional<Resource> getResource(RestApi api, String parentResourceId, String pathPart) {
        for (Resource r : api.getResources().getItem()) {
            if (pathEquals(pathPart, r.getPathPart()) && r.getParentId().equals(parentResourceId)) {
                return Optional.of(r);
            }
        }

        return Optional.empty();
    }

    private boolean pathEquals(String p1, String p2) {
        return (StringUtils.isBlank(p1) && StringUtils.isBlank(p2)) || p1.equals(p2);
    }

    private Optional<Resource> getResource(RestApi api, String fullPath) {
        for (Resource r : api.getResources().getItem()) {
            if (r.getPath().equals(fullPath)) {
                return Optional.of(r);
            }
        }

        return Optional.empty();
    }


    public void createMethod(RestApi api, Resource resource, ActionType httpMethod,
                             Action action, String modelContentType) {
        PutMethodInput input = new PutMethodInput();

        input.setApiKeyRequired(isApiKeyRequired(action));
        input.setAuthorizationType("NONE");

        // create method
        Method method = resource.putMethod(input, httpMethod.toString());

//        createMethodResponses(api, method, modelContentType, op.getResponses());
//        createMethodParameters(api, method, op.getParameters());
//        createIntegration(method, op.getVendorExtensions());
    }

    private boolean isApiKeyRequired(Action action) {
        // TODO: Use a more robust way of tagging the security schema
        for (SecurityReference securityReference : action.getSecuredBy()) {
            return "api_key".equals(securityReference.getName());
        }
        return false;
    }


}
