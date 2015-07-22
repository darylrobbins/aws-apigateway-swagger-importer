package com.amazonaws.service.apigateway.importer.impl.raml;

import com.amazonaws.service.apigateway.importer.*;
import com.google.inject.Inject;
import com.wordnik.swagger.models.Swagger;
import io.swagger.parser.SwaggerParser;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.File;

import static java.lang.String.format;

/*
* Copyright 2010-2015 Amazon.com, Inc. or its affiliates. All Rights Reserved.
*
* Licensed under the Apache License, Version 2.0 (the "License").
* You may not use this file except in compliance with the License.
* A copy of the License is located at
*
*  http://aws.amazon.com/apache2.0
*
* or in the "license" file accompanying this file. This file is distributed
* on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
* express or implied. See the License for the specific language governing
* permissions and limitations under the License.
*/

import com.amazonaws.service.apigateway.importer.ApiFileImporter;
import com.amazonaws.service.apigateway.importer.SwaggerApiImporter;
import com.google.inject.Inject;
import com.wordnik.swagger.models.Swagger;
import io.swagger.parser.SwaggerParser;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.raml.model.Raml;
import org.raml.parser.visitor.RamlDocumentBuilder;

import java.io.File;

import static java.lang.String.format;

public class ApiGatewayRamlFileImporter implements ApiFileImporter  {

        private static final Log LOG = LogFactory.getLog(ApiGatewayRamlFileImporter.class);


        private final RamlApiImporter client;

        @Inject
        public ApiGatewayRamlFileImporter(RamlApiImporter client) {
            this.client = client;
        }

        @Override
        public String importApi(String filePath) {
            LOG.info(format("Attempting to create API from Swagger definition. " +
                    "Swagger file: %s", filePath));

            final Raml raml = parse(filePath);

            return client.createApi(raml, new File(filePath).getName());
        }

        @Override
        public void updateApi(String apiId, String filePath) {
            LOG.info(format("Attempting to update API from Swagger definition. " +
                    "API identifier: %s Swagger file: %s", apiId, filePath));

            final Raml raml = parse(filePath);

            client.updateApi(apiId, raml);
        }

        @Override
        public void deploy(String apiId, String deploymentStage) {
            client.deploy(apiId, deploymentStage);
        }

        @Override
        public void deleteApi(String apiId) {
            client.deleteApi(apiId);
        }

        private Raml parse(String filePath) {

            final Raml raml = new RamlDocumentBuilder().build("file://" + filePath);

            return raml;
        }

}
