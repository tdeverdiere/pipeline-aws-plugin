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
import com.amazonaws.services.codedeploy.model.RegisterApplicationRevisionRequest;
import com.amazonaws.services.codedeploy.model.RegisterApplicationRevisionResult;
import com.amazonaws.services.codedeploy.model.RevisionLocation;
import com.amazonaws.services.codedeploy.model.S3Location;
import com.amazonaws.services.codedeploy.model.BundleType;
import com.amazonaws.services.codedeploy.model.RevisionLocationType;
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

public class CDRegisterRevisionStep extends Step {

	private final String applicationName;
	private final String bucketName;
	private final String key;

	@DataBoundConstructor
	public CDRegisterRevisionStep(String applicationName, String bucketName, String key) {
		this.applicationName = applicationName;
		this.bucketName = bucketName;
		this.key = key;
	}

	public String getApplicationName() {
		return applicationName;
	}

	public String getBucketName() {
		return bucketName;
	}

	public String getKey() {
		return key;
	}

	@Override
	public StepExecution start(StepContext context) throws Exception {
		return new CDRegisterRevisionStep.Execution(this, context);
	}

	@Extension
	public static class DescriptorImpl extends StepDescriptor {

		@Override
		public Set<? extends Class<?>> getRequiredContext() {
			return StepUtils.requiresDefault();
		}

		@Override
		public String getFunctionName() {
			return "cdRegisterRevision";
		}

		@Override
		@Nonnull
		public String getDisplayName() {
			return "Register a revision of an application from S3";
		}
	}

	public static class Execution extends SynchronousNonBlockingStepExecution<String> {

		protected static final long serialVersionUID = 1L;

		protected final transient CDRegisterRevisionStep step;

		public Execution(CDRegisterRevisionStep step, StepContext context) {
			super(context);
			this.step = step;
		}

		@Override
		public String run() throws Exception {
			final String applicationName = this.step.getApplicationName();
			final String bucketName = this.step.getBucketName();
			final String key = this.step.getKey();

			final EnvVars envVars = this.getContext().get(EnvVars.class);
			final AmazonCodeDeploy amazonCodeDeploy = getAmazonCodeDeploy(envVars);

			Preconditions.checkArgument(applicationName != null && !applicationName.isEmpty(), "Application name must not be null or empty");
			Preconditions.checkArgument(bucketName != null && !bucketName.isEmpty(), "Bucket name must not be null or empty");
			Preconditions.checkArgument(key != null && !key.isEmpty(), "Key must not be null or empty");

			TaskListener listener = Execution.this.getContext().get(TaskListener.class);
			listener.getLogger().format("Register revision for %s from revision s3://%s/%s%n", applicationName, bucketName, key);


			RegisterApplicationRevisionRequest request = new RegisterApplicationRevisionRequest()
					.withApplicationName(applicationName)
					.withRevision(createRevisionFromS3(bucketName, key))
					;

			RegisterApplicationRevisionResult result = amazonCodeDeploy.registerApplicationRevision(request);

			listener.getLogger().println("Register revision done.");

			return String.format("%s", result.toString());
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


		private AmazonCodeDeploy getAmazonCodeDeploy(EnvVars envVars) {
			return AWSClientFactory.create(AmazonCodeDeployClientBuilder.standard(), envVars);
		}
	}

}
