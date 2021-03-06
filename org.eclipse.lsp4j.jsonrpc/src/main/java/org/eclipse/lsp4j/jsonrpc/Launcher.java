/*******************************************************************************
 * Copyright (c) 2016 TypeFox GmbH (http://www.typefox.io) and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.lsp4j.jsonrpc;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

import org.eclipse.lsp4j.jsonrpc.json.ConcurrentMessageProcessor;
import org.eclipse.lsp4j.jsonrpc.json.JsonRpcMethod;
import org.eclipse.lsp4j.jsonrpc.json.JsonRpcMethodProvider;
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler;
import org.eclipse.lsp4j.jsonrpc.json.StreamMessageConsumer;
import org.eclipse.lsp4j.jsonrpc.json.StreamMessageProducer;
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints;
import org.eclipse.lsp4j.jsonrpc.validation.ReflectiveMessageValidator;

public interface Launcher<T> {
	
	/**
	 * 
	 * @param localService
	 * @param remoteInterface
	 * @param in
	 * @param out
	 * @return
	 */
	static <T> Launcher<T> createLauncher(Object localService, Class<T> remoteInterface, InputStream in, OutputStream out) {
		return createLauncher(localService, remoteInterface, in, out, false, null);
	}
	
	/**
	 * 
	 * @param localService
	 * @param remoteInterface
	 * @param in
	 * @param out
	 * @param validate
	 * @param trace
	 * @return
	 */
	static <T> Launcher<T> createLauncher(Object localService, Class<T> remoteInterface, InputStream in, OutputStream out, boolean validate, PrintWriter trace) {
		Function<MessageConsumer, MessageConsumer> wrapper = consumer -> {
			MessageConsumer result = consumer;
			if (trace != null) {
				result = message -> {
					trace.println(message);
					consumer.consume(message);
				};
			}
			if (validate) {
				result = new ReflectiveMessageValidator(result);
			}
			return result;
		};
		return createIoLauncher(localService, remoteInterface, in, out, Executors.newCachedThreadPool(), wrapper);
	}
	
	/**
	 * Create a new Launcher for a given local service object, a given remote interface and an input and output stream.
	 * 
	 * @param localService - an object on which classes RPC methods are looked up
	 * @param remoteInterface - an interface on which RPC methods are looked up
	 * @param in - inputstream to listen for incoming messages
	 * @param out - outputstream to send outgoing messages
	 */
	static <T> Launcher<T> createLauncher(Object localService, Class<T> remoteInterface, InputStream in, OutputStream out, ExecutorService executorService, Function<MessageConsumer, MessageConsumer> wrapper) {
		return createIoLauncher(localService, remoteInterface, in, out, executorService, wrapper);
	}
	
	static <T> Launcher<T> createIoLauncher(Object localService, Class<T> remoteInterface, InputStream in, OutputStream out, ExecutorService executorService, Function<MessageConsumer, MessageConsumer> wrapper) {
		Map<String, JsonRpcMethod> supportedMethods = new LinkedHashMap<String, JsonRpcMethod>();
		supportedMethods.putAll(ServiceEndpoints.getSupportedMethods(remoteInterface));
		
		if (localService instanceof JsonRpcMethodProvider) {
			JsonRpcMethodProvider rpcMethodProvider = (JsonRpcMethodProvider) localService;
			supportedMethods.putAll(rpcMethodProvider.supportedMethods());
		} else {
			supportedMethods.putAll(ServiceEndpoints.getSupportedMethods(localService.getClass()));
		}
		
		MessageJsonHandler jsonHandler = new MessageJsonHandler(supportedMethods);
		MessageConsumer outGoingMessageStream = new StreamMessageConsumer(out, jsonHandler);
		outGoingMessageStream = wrapper.apply(outGoingMessageStream);
		RemoteEndpoint serverEndpoint = new RemoteEndpoint(outGoingMessageStream, ServiceEndpoints.toEndpoint(localService));
		jsonHandler.setMethodProvider(serverEndpoint);
		// wrap incoming message stream
		MessageConsumer messageConsumer = wrapper.apply(serverEndpoint);
		StreamMessageProducer reader = new StreamMessageProducer(in, jsonHandler);
		
		T remoteProxy = ServiceEndpoints.toServiceObject(serverEndpoint, remoteInterface);
		
		return new Launcher<T> () {

			@Override
			public Future<?> startListening() {
				return ConcurrentMessageProcessor.startProcessing(reader, messageConsumer, executorService);
			}

			@Override
			public T getRemoteProxy() {
				return remoteProxy;
			}
			
		};
	}
	
	Future<?> startListening();
	
	T getRemoteProxy();
	
}
