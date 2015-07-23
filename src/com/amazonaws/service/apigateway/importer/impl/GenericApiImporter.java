package com.amazonaws.service.apigateway.importer.impl;

import com.amazonaws.service.apigateway.importer.impl.swagger.sdk.ApiGatewaySdkSwaggerApiImporter;
import com.amazonaws.services.apigateway.model.*;
import com.google.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.amazonaws.service.apigateway.importer.util.PatchUtils.createPatchDocument;
import static com.amazonaws.service.apigateway.importer.util.PatchUtils.createReplaceOperation;
import static java.lang.String.format;

/**
 * Created by dennis on 2015-07-22.
 */
public abstract class GenericApiImporter {

    protected static final String DEFAULT_PRODUCES_CONTENT_TYPE = "application/json";
    protected static final String EXTENSION_AUTH = "x-amazon-apigateway-auth";
    protected static final String EXTENSION_INTEGRATION = "x-amazon-apigateway-integration";

    @Inject
    protected ApiGateway apiGateway;

    protected abstract Log getLog();
    
    protected void deleteApi(RestApi api) {
        getLog().info("Deleting API " + api.getId());
        api.deleteRestApi();
    }

    protected RestApi createApi(String name, String description) {
        getLog().info("Creating API with name " + name);

        CreateRestApiInput input = new CreateRestApiInput();
        input.setName(name);
        input.setDescription(description);

        return apiGateway.createRestApi(input);
    }

    protected RestApi getApi(String id) {
        return apiGateway.getRestApiById(id);
    }

    protected Resource createResource(RestApi api, String parentResourceId, String pathPart) {
        CreateResourceInput input = new CreateResourceInput();
        input.setPathPart(pathPart);

        Resource resource = api.getResourceById(parentResourceId);

        return resource.createResource(input);
    }

    protected void createModel(RestApi api, String modelName, String description, String schema, String modelContentType) {

        CreateModelInput input = new CreateModelInput();

        input.setName(modelName);
        input.setDescription(description);
        input.setContentType(modelContentType);
        input.setSchema(schema);

        api.createModel(input);
    }

    protected void deleteDefaultModels(RestApi api) {
        api.getModels().getItem().stream().forEach(model -> {
            getLog().info("Removing default model " + model.getName());
            try {
                model.deleteModel();
            } catch (Throwable ignored) {} // todo: temporary catch until API fix
        });
    }

    protected Optional<Resource> getResource(RestApi api, String parentResourceId, String pathPart) {
        for (Resource r : api.getResources().getItem()) {
            if (pathEquals(pathPart, r.getPathPart()) && r.getParentId().equals(parentResourceId)) {
                return Optional.of(r);
            }
        }

        return Optional.empty();
    }

    protected boolean pathEquals(String p1, String p2) {
        return (StringUtils.isBlank(p1) && StringUtils.isBlank(p2)) || p1.equals(p2);
    }

    protected Optional<Resource> getResource(RestApi api, String fullPath) {
        for (Resource r : api.getResources().getItem()) {
            if (r.getPath().equals(fullPath)) {
                return Optional.of(r);
            }
        }

        return Optional.empty();
    }

    protected Optional<Resource> getRootResource(RestApi api) {
        for (Resource r : api.getResources().getItem()) {
            if ("/".equals(r.getPath())) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    protected Optional<Model> getModel(RestApi api, String modelName) {
        try {
            return Optional.of(api.getModelByName(modelName));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    protected void updateModel(RestApi api, String modelName, String schema) {
        api.getModelByName(modelName).updateModel(createPatchDocument(createReplaceOperation("/schema", schema)));
    }

    protected boolean methodExists(Resource resource, String httpMethod) {
        return resource.getResourceMethods().get(httpMethod.toUpperCase()) != null;
    }

    protected void deleteResource(Resource resource) {
        if (resource._isLinkAvailable("resource:delete")) {
            resource.deleteResource();
        }
        // can't delete root resource
    }

    protected void sleep() {
        try {
            Thread.sleep(500);  // todo: temporary hack to get around throttling limits - sdk should backoff and retry when throttled
        } catch (InterruptedException ignored) {}
    }

    protected Resource createResource(RestApi api, String parentResourceId, String parentPart, String part) {
        final Optional<Resource> existingResource = getResource(api, parentResourceId, part);

        // create resource if doesn't exist
        if (!existingResource.isPresent()) {
            getLog().info("Creating resource '" + part + "' with parent '" + parentPart + "'");
            sleep();
            return createResource(api, parentResourceId, part);
        } else {
            return existingResource.get();
        }
    }

    protected String getStringValue(Object in) {
        return in == null ? null : String.valueOf(in);  // use null value instead of "null"
    }

    public void deploy(String apiId, String deploymentStage) {
        getLog().info(String.format("Creating deployment for API %s and stage %s", apiId, deploymentStage));

        CreateDeploymentInput input = new CreateDeploymentInput();
        input.setStageName(deploymentStage);

        apiGateway.getRestApiById(apiId).createDeployment(input);
    }

    public void deleteApi(String apiId) {
        deleteApi(apiGateway.getRestApiById(apiId));
    }

    protected void rollback(RestApi api) {
        deleteApi(api);
    }

    /**
     * Build the full resource path, including base path, add any missing leading '/', remove any trailing '/',
     * and remove any double '/'
     * @param basePath the base path
     * @param resourcePath the resource path
     * @return the full path
     */
    public String buildResourcePath(String basePath, String resourcePath) {
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

    /*
         * Get the content-type to use for models and responses based on the method "produces" or the api "produces" content-types
         *
         * First look in the method produces and favor application/json, otherwise return the first method produces type
         * If no method produces, fall back to api produces and favor application/json, otherwise return the first api produces type
         * If no produces are defined on the method or api, default to application/json
         */
    // todo: check this logic for apis/methods producing multiple content-types
    // note: assumption - models in an api will always use one of the api "produces" content types, favoring application/json. models created from operation responses may use the operation "produces" content type
    protected String getProducesContentType(List<String> apiProduces, List<String> methodProduces) {

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
}
