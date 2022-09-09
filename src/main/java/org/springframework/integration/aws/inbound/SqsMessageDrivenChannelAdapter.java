/*
 * Copyright 2016-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.aws.inbound;

import java.time.Duration;
import java.util.Arrays;

import io.awspring.cloud.sqs.config.EndpointRegistrar;
import io.awspring.cloud.sqs.config.SqsMessageListenerContainerFactory;
import io.awspring.cloud.sqs.listener.MessageListener;
import io.awspring.cloud.sqs.listener.QueueNotFoundStrategy;
import io.awspring.cloud.sqs.listener.SqsMessageListenerContainer;
import io.awspring.cloud.sqs.listener.acknowledgement.handler.AcknowledgementMode;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.integration.aws.support.AwsHeaders;
import org.springframework.integration.endpoint.MessageProducerSupport;
import org.springframework.integration.support.management.IntegrationManagedResource;
import org.springframework.jmx.export.annotation.ManagedAttribute;
import org.springframework.jmx.export.annotation.ManagedOperation;
import org.springframework.jmx.export.annotation.ManagedResource;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.util.Assert;

/**
 * The {@link MessageProducerSupport} implementation for the Amazon SQS
 * {@code receiveMessage}. Works in 'listener' manner and delegates hard to the
 * {@link SqsMessageListenerContainer}.
 *
 * @author Artem Bilan
 * @author Patrick Fitzsimons
 * @see SqsMessageListenerContainerFactory
 * @see SqsMessageListenerContainer
 */
@ManagedResource
@IntegrationManagedResource
public class SqsMessageDrivenChannelAdapter extends MessageProducerSupport implements DisposableBean {

	private final SqsMessageListenerContainerFactory<Message<?>> simpleMessageListenerContainerFactory = new SqsMessageListenerContainerFactory<>();

	private final String[] queues;

	private SqsMessageListenerContainer<Message<?>> listenerContainer;

	private EndpointRegistrar endpointRegistrar;

	private Long queueStopTimeout;

	private AcknowledgementMode messageDeletionPolicy = AcknowledgementMode.ON_SUCCESS;

	public SqsMessageDrivenChannelAdapter(SqsAsyncClient amazonSqs, String... queues) {
		Assert.noNullElements(queues, "'queues' must not be empty");
		this.simpleMessageListenerContainerFactory.setSqsAsyncClient(amazonSqs);
		this.queues = Arrays.copyOf(queues, queues.length);
	}

	public void setMaxNumberOfMessages(Integer maxNumberOfMessages) {
		this.simpleMessageListenerContainerFactory.configure(options -> options.maxMessagesPerPoll(maxNumberOfMessages));
	}

	public void setVisibilityTimeout(Integer visibilityTimeout) {
		this.simpleMessageListenerContainerFactory.configure(options -> options.messageVisibility(Duration.ofMillis(visibilityTimeout)));
	}

	public void setWaitTimeOut(Integer waitTimeOut) {
		this.simpleMessageListenerContainerFactory.configure(options -> options.pollTimeout(Duration.ofSeconds(waitTimeOut)));
	}

	@Override
	public void setAutoStartup(boolean autoStartUp) {
		super.setAutoStartup(autoStartUp);
		// TODO this.simpleMessageListenerContainerFactory.setAutoStartup(autoStartUp);
	}

	public void setFailOnMissingQueue(boolean failOnMissingQueue) {
		this.simpleMessageListenerContainerFactory.configure(options -> options.queueNotFoundStrategy(failOnMissingQueue ? QueueNotFoundStrategy.FAIL : QueueNotFoundStrategy.CREATE));
	}

	public void setQueueStopTimeout(long queueStopTimeout) {
		this.queueStopTimeout = queueStopTimeout;
	}

	public void setMessageDeletionPolicy(AcknowledgementMode messageDeletionPolicy) {
		Assert.notNull(messageDeletionPolicy, "'messageDeletionPolicy' must not be null.");
		this.messageDeletionPolicy = messageDeletionPolicy;
	}

	@Override
	protected void onInit() {
		super.onInit();
		this.listenerContainer = this.simpleMessageListenerContainerFactory.createContainer("container");
		if (this.queueStopTimeout != null) {
			this.listenerContainer.configure(options -> options.shutdownTimeout(Duration.ofSeconds(this.queueStopTimeout)));
		}
		this.listenerContainer.setMessageListener(new IntegrationQueueMessageHandler());
	}

	@Override
	public String getComponentType() {
		return "aws:sqs-message-driven-channel-adapter";
	}

	@Override
	protected void doStart() {
		this.listenerContainer.start();
	}

	@Override
	protected void doStop() {
		this.listenerContainer.stop();
	}

	@ManagedOperation
	public void stop(String logicalQueueName) {
		// TODO this.listenerContainer.stop(logicalQueueName);
	}

	@ManagedOperation
	public void start(String logicalQueueName) {
		// TODO this.listenerContainer.start(logicalQueueName);
	}

	@ManagedOperation
	public boolean isRunning(String logicalQueueName) {
		// TODO return this.listenerContainer.isRunning(logicalQueueName);
		return this.listenerContainer.isRunning();
	}

	@ManagedAttribute
	public String[] getQueues() {
		return Arrays.copyOf(this.queues, this.queues.length);
	}

	@Override
	public void destroy() {
		this.listenerContainer.stop();
	}

	private class IntegrationQueueMessageHandler implements MessageListener<Message<?>> {

		@Override public void onMessage(Message<Message<?>> message) {
			MessageHeaders headers = message.getHeaders();

			Message<?> messageToSend = getMessageBuilderFactory().fromMessage(message)
					.removeHeaders("LogicalResourceId", "MessageId", "ReceiptHandle", "Acknowledgment")
					.setHeader(AwsHeaders.MESSAGE_ID, headers.get("MessageId"))
					.setHeader(AwsHeaders.RECEIPT_HANDLE, headers.get("ReceiptHandle"))
					.setHeader(AwsHeaders.RECEIVED_QUEUE, headers.get("LogicalResourceId"))
					.setHeader(AwsHeaders.ACKNOWLEDGMENT, headers.get("Acknowledgment")).build();

			sendMessage(messageToSend);
		}

	}

}
