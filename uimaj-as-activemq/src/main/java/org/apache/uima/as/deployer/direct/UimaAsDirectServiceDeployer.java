/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.uima.as.deployer.direct;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import org.apache.uima.aae.component.TopLevelServiceComponent;
import org.apache.uima.aae.component.dd.DeploymentDescriptorProcessor;
import org.apache.uima.aae.service.UimaASService;
import org.apache.uima.aae.service.builder.UimaAsDirectServiceBuilder;
import org.apache.uima.as.deployer.AbstractUimaASDeployer;
import org.apache.uima.resourceSpecifier.AnalysisEngineDeploymentDescriptionDocument;

public class UimaAsDirectServiceDeployer  extends AbstractUimaASDeployer {


	public UimaAsDirectServiceDeployer(CountDownLatch latch) {
		// pass in a latch object which will block until service
		// is initialized. The blocking will take place in super.waitUntilInitialized()
		super(latch);
		System.out.println("........ UimaAsDirectServiceDeployer() - Direct Deployment");
	}

	public UimaASService deploy(AnalysisEngineDeploymentDescriptionDocument dd,
			Map<String, String> deploymentProperties) throws Exception {
		UimaASService uimaAsService = null;
		try {
			DeploymentDescriptorProcessor ddProcessor = 
					new DeploymentDescriptorProcessor(dd);
			
			// process dd producing TopLevelServiceComponent. If the dd
			// is an aggregate, the component object will include a tree
			// of delegates. It basically combines information from both
			// a dd and resource specifier for all parts of the pipeline
			// aggregating instances of AnalysisEngineComponent created
			// for every delegate.
			TopLevelServiceComponent topLevelComponent = 
					ddProcessor.newComponent();
			
			// create an instance of a service for the client to use
			uimaAsService = new UimaAsDirectServiceBuilder().build(topLevelComponent, this);
			
			// start listeners
			uimaAsService.start();
			// block until all internal components initialize and are ready to process
			waitUntilInitialized();

		} catch (Exception e) {
			e.printStackTrace();
			throw e;
		}
		return uimaAsService;
	}
	public static void main(String[] args) {
		String dd4 = "../uimaj-as-activemq/src/test/resources/deployment/Deploy_AggregateAnnotator.xml";
		try {
			CountDownLatch latch = new CountDownLatch(1);

			UimaAsDirectServiceDeployer deployer = new UimaAsDirectServiceDeployer(latch);

			Map<String, String> deploymentProperties = new HashMap<String, String>();

			deploymentProperties.put(Deployment, DeploymentStrategy.LOCAL.name());

			AnalysisEngineDeploymentDescriptionDocument dd = AnalysisEngineDeploymentDescriptionDocument.Factory
					.parse(new File(dd4));

			deployer.deploy(dd, deploymentProperties);

		} catch (Exception e) {
			e.printStackTrace();
		}

	}
}