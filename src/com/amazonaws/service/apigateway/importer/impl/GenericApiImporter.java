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

    protected void createIntegrationResponses(Integration integration, HashMap<String, HashMap> integ) {

        // todo: avoid unchecked casts
        HashMap<String, HashMap> responses = (HashMap<String, HashMap>) integ.get("responses");

        responses.entrySet().forEach(e -> {
            String pattern = e.getKey().equals("default") ? null : e.getKey();
            HashMap response = e.getValue();

            String status = (String) response.get("statusCode");

            PutIntegrationResponseInput input = new PutIntegrationResponseInput()
                    .withResponseParameters((Map<String, String>) response.get("responseParameters"))
                    .withResponseTemplates((Map<String, String>) response.get("responseTemplates"))
                    .withSelectionPattern(pattern);

            integration.putIntegrationResponse(input, status);
        });
    }

    protected void createIntegration(Method method, Map<String, Object> vendorExtensions) {
        if (!vendorExtensions.containsKey(EXTENSION_INTEGRATION)) {
            return;
        }

        HashMap<String, HashMap> integ =
                (HashMap<String, HashMap>) vendorExtensions.get(EXTENSION_INTEGRATION);

        IntegrationType type = IntegrationType.valueOf(getStringValue(integ.get("type")).toUpperCase());

        getLog().info("Creating integration with type " + type);

        PutIntegrationInput input = new PutIntegrationInput()
                .withType(type)
                .withUri(getStringValue(integ.get("uri")))
                .withCredentials(getStringValue(integ.get("credentials")))
                .withHttpMethod((getStringValue(integ.get("httpMethod"))))
                .withRequestParameters(integ.get("requestParameters"))
                .withRequestTemplates(integ.get("requestTemplates"))
                .withCacheNamespace(getStringValue(integ.get("cacheNamespace")))
                .withCacheKeyParameters((List<String>) integ.get("cacheKeyParameters"));

        Integration integration = method.putIntegration(input);

        createIntegrationResponses(integration, integ);
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
}
