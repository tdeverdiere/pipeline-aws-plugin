/*
 * -
 * #%L
 * Pipeline: AWS Steps
 * %%
 * Copyright (C) 2016 Taimos GmbH
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

package de.taimos.pipeline.aws.codedeploy;

import com.amazonaws.services.codedeploy.AmazonCodeDeploy;
import com.amazonaws.services.codedeploy.AmazonCodeDeployClientBuilder;
import com.amazonaws.services.codedeploy.model.CreateDeploymentRequest;
import com.amazonaws.services.codedeploy.model.CreateDeploymentResult;
import com.amazonaws.services.codedeploy.model.RevisionLocation;
import com.amazonaws.services.codedeploy.model.S3Location;
import com.amazonaws.services.codedeploy.model.BundleType;
import com.amazonaws.services.codedeploy.model.RevisionLocationType;
import com.amazonaws.services.codedeploy.model.ListApplicationsResult;
import com.amazonaws.services.codedeploy.model.ListDeploymentGroupsRequest;
import com.amazonaws.services.codedeploy.model.ListDeploymentGroupsResult;
import com.google.common.base.Preconditions;
import de.taimos.pipeline.aws.AWSClientFactory;
import de.taimos.pipeline.aws.utils.StepUtils;
import hudson.EnvVars;
import hudson.Extension;
import hudson.model.TaskListener;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;
import java.util.Set;

public class CDCreateDeploymentStep extends Step {

	private final String applicationName;
	private final String deploymentGroupName;
	private final String bucketName;
	private final String key;

	@DataBoundConstructor
	public CDCreateDeploymentStep(String applicationName, String deploymentGroupName, String bucketName, String key) {
		this.applicationName = applicationName;
		this.deploymentGroupName = deploymentGroupName;
		this.bucketName = bucketName;
		this.key = key;
	}

	public String getApplicationName() {
		return applicationName;
	}

	public String getDeploymentGroupName() {
		return deploymentGroupName;
	}

	public String getBucketName() {
		return bucketName;
	}

	public String getKey() {
		return key;
	}

	@Override
	public StepExecution start(StepContext context) throws Exception {
		return new CDCreateDeploymentStep.Execution(this, context);
	}

	@Extension
	public static class DescriptorImpl extends StepDescriptor {

		@Override
		public Set<? extends Class<?>> getRequiredContext() {
			return StepUtils.requiresDefault();
		}

		@Override
		public String getFunctionName() {
			return "cdCreateDeployment";
		}

		@Override
		@Nonnull
		public String getDisplayName() {
			return "Create a deployment of an application to a deployment group";
		}
	}

	public static class Execution extends SynchronousNonBlockingStepExecution<String> {

		protected static final long serialVersionUID = 1L;

		protected final transient CDCreateDeploymentStep step;

		public Execution(CDCreateDeploymentStep step, StepContext context) {
			super(context);
			this.step = step;
		}

		@Override
		public String run() throws Exception {
			final String applicationName = this.step.getApplicationName();
			final String deploymentGroupName = this.step.getDeploymentGroupName();
			final String bucketName = this.step.getBucketName();
			final String key = this.step.getKey();

			final EnvVars envVars = this.getContext().get(EnvVars.class);
			final AmazonCodeDeploy amazonCodeDeploy = getAmazonCodeDeploy(envVars);

			Preconditions.checkArgument(applicationName != null && !applicationName.isEmpty(), "Application name must not be null or empty");
			Preconditions.checkArgument(deploymentGroupName != null && !deploymentGroupName.isEmpty(), "Deployment group name must not be null or empty");
			Preconditions.checkArgument(bucketName != null && !bucketName.isEmpty(), "Bucket name must not be null or empty");
			Preconditions.checkArgument(key != null && !key.isEmpty(), "Key must not be null or empty");

			verifyCodeDeployApplication(amazonCodeDeploy, applicationName, deploymentGroupName);

			TaskListener listener = Execution.this.getContext().get(TaskListener.class);
			listener.getLogger().format("Create deployment of %s to %s from revision s3://%s/%s%n", applicationName, deploymentGroupName, bucketName, key);

			CreateDeploymentRequest request = new CreateDeploymentRequest()
					.withApplicationName(applicationName)
					.withDeploymentGroupName(deploymentGroupName)
					.withDeploymentConfigName("CodeDeployDefault.OneAtATime")
					.withRevision(createRevisionFromS3(bucketName, key))
					;

			CreateDeploymentResult createDeploymentResult = amazonCodeDeploy.createDeployment(request);

			String deploymentId = createDeploymentResult.getDeploymentId();

			listener.getLogger().println("Create deployment setted.");
			return String.format("%s", deploymentId);
		}

		private RevisionLocation createRevisionFromS3(String bucketName, String key) {
			S3Location s3Location = new S3Location();
			s3Location.setBucket(bucketName);
			s3Location.setKey(key);
			s3Location.setBundleType(BundleType.Zip);

			RevisionLocation revisionLocation = new RevisionLocation();
			revisionLocation.setRevisionType(RevisionLocationType.S3);
			revisionLocation.setS3Location(s3Location);

			return revisionLocation;
		}

		private void verifyCodeDeployApplication(AmazonCodeDeploy amazonCodeDeploy, String applicationName, String deploymentGroupName) throws IllegalArgumentException {
			// Check that the application exists
			ListApplicationsResult applications = amazonCodeDeploy.listApplications();

			if (!applications.getApplications().contains(applicationName)) {
				throw new IllegalArgumentException("Cannot find application named '" + applicationName + "'");
			}

			// Check that the deployment group exists
			ListDeploymentGroupsResult deploymentGroups = amazonCodeDeploy.listDeploymentGroups(
					new ListDeploymentGroupsRequest()
							.withApplicationName(applicationName)
			);

			if (!deploymentGroups.getDeploymentGroups().contains(deploymentGroupName)) {
				throw new IllegalArgumentException("Cannot find deployment group named '" + deploymentGroupName + "'");
			}
		}

		private AmazonCodeDeploy getAmazonCodeDeploy(EnvVars envVars) {
			return AWSClientFactory.create(AmazonCodeDeployClientBuilder.standard(), envVars);
		}
	}

}
