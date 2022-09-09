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

package org.springframework.integration.aws.outbound;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.springframework.expression.Expression;
import org.springframework.expression.common.LiteralExpression;
import org.springframework.integration.aws.support.AwsHeaders;
import org.springframework.integration.aws.support.SqsHeaderMapper;
import org.springframework.integration.expression.ValueExpression;
import org.springframework.integration.handler.AbstractMessageHandler;
import org.springframework.integration.mapping.HeaderMapper;
import org.springframework.integration.support.AbstractIntegrationMessageBuilder;
import org.springframework.messaging.Message;
import org.springframework.messaging.converter.GenericMessageConverter;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.messaging.core.DestinationResolver;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import software.amazon.awssdk.awscore.AwsRequest;

import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

/**
 * The {@link AbstractMessageHandler} implementation for the Amazon SQS
 * {@code sendMessage}.
 *
 * @author Artem Bilan
 * @author Rahul Pilani
 * @author Taylor Wicksell
 * @author Seth Kelly
 * @see SqsAsyncClient#sendMessage(SendMessageRequest)
 * @see com.amazonaws.handlers.AsyncHandler
 *
 */
public class SqsMessageHandler extends AbstractAwsMessageHandler<Map<String, MessageAttributeValue>> {

	private final SqsAsyncClient amazonSqs;

	private MessageConverter messageConverter;

	private Expression queueExpression;

	private Expression delayExpression;

	private Expression messageGroupIdExpression;

	private Expression messageDeduplicationIdExpression;

	public SqsMessageHandler(SqsAsyncClient amazonSqs) {
		this(amazonSqs, null);
	}

	public SqsMessageHandler(SqsAsyncClient amazonSqs, DestinationResolver<?> destinationResolver) {
		Assert.notNull(amazonSqs, "'amazonSqs' must not be null");
		Assert.notNull(destinationResolver, "'destinationResolver' must not be null");

		this.amazonSqs = amazonSqs;
		doSetHeaderMapper(new SqsHeaderMapper());
	}

	public void setQueue(String queue) {
		Assert.hasText(queue, "'queue' must not be empty");
		setQueueExpression(new LiteralExpression(queue));
	}

	public void setQueueExpressionString(String queueExpression) {
		setQueueExpression(EXPRESSION_PARSER.parseExpression(queueExpression));
	}

	public void setQueueExpression(Expression queueExpression) {
		Assert.notNull(queueExpression, "'queueExpression' must not be null");
		this.queueExpression = queueExpression;
	}

	public void setDelay(int delaySeconds) {
		setDelayExpression(new ValueExpression<>(delaySeconds));
	}

	public void setDelayExpressionString(String delayExpression) {
		setDelayExpression(EXPRESSION_PARSER.parseExpression(delayExpression));
	}

	public void setDelayExpression(Expression delayExpression) {
		Assert.notNull(delayExpression, "'delayExpression' must not be null");
		this.delayExpression = delayExpression;
	}

	public void setMessageGroupId(String messageGroupId) {
		setMessageGroupIdExpression(new LiteralExpression(messageGroupId));
	}

	public void setMessageGroupIdExpressionString(String groupIdExpression) {
		setMessageGroupIdExpression(EXPRESSION_PARSER.parseExpression(groupIdExpression));
	}

	public void setMessageGroupIdExpression(Expression messageGroupIdExpression) {
		Assert.notNull(messageGroupIdExpression, "'messageGroupIdExpression' must not be null");
		this.messageGroupIdExpression = messageGroupIdExpression;
	}

	public void setMessageDeduplicationId(String messageDeduplicationId) {
		setMessageDeduplicationIdExpression(new LiteralExpression(messageDeduplicationId));
	}

	public void setMessageDeduplicationIdExpressionString(String messageDeduplicationIdExpression) {
		setMessageDeduplicationIdExpression(EXPRESSION_PARSER.parseExpression(messageDeduplicationIdExpression));
	}

	public void setMessageDeduplicationIdExpression(Expression messageDeduplicationIdExpression) {
		Assert.notNull(messageDeduplicationIdExpression, "'messageDeduplicationIdExpression' must not be null");
		this.messageDeduplicationIdExpression = messageDeduplicationIdExpression;
	}

	public void setMessageConverter(MessageConverter messageConverter) {
		this.messageConverter = messageConverter;
	}

	@Override
	protected void onInit() {
		super.onInit();

		if (this.messageConverter == null) {
			this.messageConverter = new GenericMessageConverter(getConversionService());
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	protected Future<?> handleMessageToAws(Message<?> message) {
		Object payload = message.getPayload();
		if (payload instanceof SendMessageBatchRequest) {
			BiConsumer<SendMessageBatchResponse, Throwable> asyncHandler = obtainAsyncHandler(message,
					(SendMessageBatchRequest) payload);
			return this.amazonSqs.sendMessageBatch((SendMessageBatchRequest) payload).whenComplete(asyncHandler);
		}

		SendMessageRequest.Builder sendMessageRequest;
		if (payload instanceof SendMessageRequest) {
			sendMessageRequest = ((SendMessageRequest) payload).toBuilder();
		}
		else {
			String queue = message.getHeaders().get(AwsHeaders.QUEUE, String.class);
			if (!StringUtils.hasText(queue) && this.queueExpression != null) {
				queue = this.queueExpression.getValue(getEvaluationContext(), message, String.class);
			}
			Assert.state(queue != null,
					"'queue' must not be null for sending an SQS message. "
							+ "Consider configuring this handler with a 'queue'( or 'queueExpression') or supply an "
							+ "'aws_queue' message header");


			String queueUrl = this.amazonSqs.getQueueUrl(GetQueueUrlRequest.builder().queueName(queue).build()).join().queueUrl();
			String messageBody = (String) this.messageConverter.fromMessage(message, String.class);
			sendMessageRequest = SendMessageRequest.builder().queueUrl(queueUrl).messageBody(messageBody);

			if (this.delayExpression != null) {
				Integer delay = this.delayExpression.getValue(getEvaluationContext(), message, Integer.class);
				sendMessageRequest.delaySeconds(delay);
			}

			if (this.messageGroupIdExpression != null) {
				String messageGroupId = this.messageGroupIdExpression.getValue(getEvaluationContext(), message,
						String.class);
				sendMessageRequest.messageGroupId(messageGroupId);
			}

			if (this.messageDeduplicationIdExpression != null) {
				String messageDeduplicationId = this.messageDeduplicationIdExpression.getValue(getEvaluationContext(),
						message, String.class);
				sendMessageRequest.messageDeduplicationId(messageDeduplicationId);
			}

			HeaderMapper<Map<String, MessageAttributeValue>> headerMapper = getHeaderMapper();
			if (headerMapper != null) {
				mapHeaders(message, sendMessageRequest, headerMapper);
			}
		}
		SendMessageRequest request = sendMessageRequest.build();
		BiConsumer<SendMessageResponse, Throwable> asyncHandler = obtainAsyncHandler(message, request);
		return this.amazonSqs.sendMessage(request).whenComplete(asyncHandler);
	}

	private void mapHeaders(Message<?> message, SendMessageRequest.Builder sendMessageRequest,
			HeaderMapper<Map<String, MessageAttributeValue>> headerMapper) {

		HashMap<String, MessageAttributeValue> messageAttributes = new HashMap<>();
		headerMapper.fromHeaders(message.getHeaders(), messageAttributes);
		if (!messageAttributes.isEmpty()) {
			sendMessageRequest.messageAttributes(messageAttributes);
		}
	}

	@Override
	protected void additionalOnSuccessHeaders(AbstractIntegrationMessageBuilder<?> messageBuilder,
			AwsRequest request, Object result) {

		if (result instanceof SendMessageResponse sendMessageResult) {
			messageBuilder.setHeaderIfAbsent(AwsHeaders.MESSAGE_ID, sendMessageResult.messageId());
			messageBuilder.setHeaderIfAbsent(AwsHeaders.SEQUENCE_NUMBER, sendMessageResult.sequenceNumber());
		}
	}

}
