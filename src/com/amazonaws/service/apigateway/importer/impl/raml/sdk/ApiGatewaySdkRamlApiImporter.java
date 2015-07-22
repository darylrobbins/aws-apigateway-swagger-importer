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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.lang.String.format;

/**
 * Created by daryl on 15-07-21.
 */
public class ApiGatewaySdkRamlApiImporter extends GenericApiImporter implements RamlApiImporter  {

    private static final Log LOG = LogFactory.getLog(ApiGatewaySdkRamlApiImporter.class);

    @Inject
    private Raml raml;


    @Override
    public String createApi(Raml raml, String name) {
        this.raml = raml;

        final RestApi api = createApi(getApiName(raml, name), "Imported from RAML");

        try {
            final Resource rootResource = getRootResource(api).get();
            deleteDefaultModels(api);
            createModels(api, raml.getConsolidatedSchemas(), raml.getMediaType());
            createResources(api, rootResource, raml.getBasePath(), raml.getMediaType(), raml.getResources(), true);
        } catch (Throwable t) {
            getLog().error("Error creating API, rolling back", t);
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

    @Override
    public void updateApi(String apiId, Raml raml) {
        throw new UnsupportedOperationException("Not currently supported");
    }


    @Override
    protected Log getLog() {
        return LOG;
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

    private void createMethods(final RestApi api, final Resource resource, org.raml.model.Resource path, String apiProduces) {
        final Map<ActionType, Action> actions = path.getActions();

        actions.entrySet().forEach(x -> {
            getLog().info(format("Creating method for api id %s and resource id %s with method %s", api.getId(), resource.getId(), x.getKey()));
            createMethod(api, resource, x.getKey(), x.getValue(), apiProduces);

            sleep();
        });
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
