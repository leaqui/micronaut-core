/*
 * Copyright 2017-2018 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.micronaut.tracing.instrument.util;

import io.micronaut.core.async.publisher.Publishers;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import javax.annotation.Nonnull;

import static io.micronaut.tracing.interceptor.TraceInterceptor.logError;

/**
 * A reactive streams publisher that traces.
 *
 * @param <T> the type of element signaled
 *
 * @author graemerocher
 * @since 1.0
 */
public class TracingPublisher<T> implements Publisher<T> {

    private final Publisher<T> publisher;
    private final Tracer tracer;
    private final Tracer.SpanBuilder spanBuilder;
    private final Span parentSpan;
    private final boolean isSingle;

    /**
     * Creates a new tracing publisher for the given arguments.
     *
     * @param publisher The target publisher
     * @param tracer The tracer
     * @param operationName The operation name that should be started
     */
    public TracingPublisher(Publisher<T> publisher, Tracer tracer, String operationName) {
        this(publisher, tracer, tracer.buildSpan(operationName));
    }

    /**
     * Creates a new tracing publisher for the given arguments. This constructor will just add tracing of the
     * existing span if it is present.
     *
     * @param publisher The target publisher
     * @param tracer The tracer
     */
    public TracingPublisher(Publisher<T> publisher, Tracer tracer) {
        this(publisher, tracer, (Tracer.SpanBuilder) null);
    }

    /**
     * Creates a new tracing publisher for the given arguments.
     *
     * @param publisher The target publisher
     * @param tracer The tracer
     * @param spanBuilder The span builder that represents the span that will be created when the publisher is subscribed to
     */
    public TracingPublisher(Publisher<T> publisher, Tracer tracer, Tracer.SpanBuilder spanBuilder) {
        this(publisher, tracer, spanBuilder, Publishers.isSingle(publisher.getClass()));
    }


    /**
     * Creates a new tracing publisher for the given arguments.
     *
     * @param publisher The target publisher
     * @param tracer The tracer
     * @param spanBuilder The span builder that represents the span that will be created when the publisher is subscribed to
     * @param isSingle Does the publisher emit a single item
     */
    public TracingPublisher(Publisher<T> publisher, Tracer tracer, Tracer.SpanBuilder spanBuilder,  boolean isSingle) {
        this.publisher = publisher;
        this.tracer = tracer;
        this.spanBuilder = spanBuilder;
        this.parentSpan = tracer.activeSpan();
        this.isSingle = isSingle;
        if (parentSpan != null && spanBuilder != null) {
            spanBuilder.asChildOf(parentSpan);
        }
    }

    @Override
    public void subscribe(Subscriber<? super T> actual) {
        Span span;
        if (spanBuilder != null) {
            span = spanBuilder.start();
        } else {
            span = parentSpan;
        }
        if (span != null) {
            try (Scope ignored = tracer.scopeManager().activate(span, false)) {
                publisher.subscribe(new Subscriber<T>() {
                    boolean finished = false;
                    @Override
                    public void onSubscribe(Subscription s) {
                        try (Scope ignored = tracer.scopeManager().activate(span, false)) {
                            TracingPublisher.this.doOnSubscribe(span);
                            actual.onSubscribe(s);
                        }
                    }

                    @Override
                    public void onNext(T object) {
                        try (Scope ignored = tracer.scopeManager().activate(span, isSingle)) {
                            TracingPublisher.this.doOnNext(object, span);
                            actual.onNext(object);
                            if (isSingle) {
                                finished = true;
                                TracingPublisher.this.doOnFinish(span);
                            }
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                        try (Scope ignored = tracer.scopeManager().activate(span, true)) {
                            TracingPublisher.this.onError(t, span);
                            actual.onError(t);
                            finished = true;
                        }
                    }

                    @Override
                    public void onComplete() {
                        if (!finished) {
                            try (Scope ignored = tracer.scopeManager().activate(span, true)) {
                                actual.onComplete();
                                TracingPublisher.this.doOnFinish(span);
                            }
                        } else {
                            actual.onComplete();
                        }
                    }
                });
            }
        } else {
            publisher.subscribe(actual);
        }
    }

    /**
     * Designed for subclasses to override and implement custom behaviour when an item is emitted.
     *
     * @param object The object
     * @param span The span
     */
    @SuppressWarnings("WeakerAccess")
    protected void doOnNext(@Nonnull T object, @Nonnull Span span) {
        // no-op
    }

    /**
     * Designed for subclasses to override and implement custom on subscribe behaviour.
     *
     * @param span The span
     */
    @SuppressWarnings("WeakerAccess")
    protected void doOnSubscribe(@Nonnull Span span) {
        // no-op
    }

    /**
     * Designed for subclasses to override and implement custom on finish behaviour. Fired
     * prior to calling {@link Span#finish()}.
     *
     * @param span The span
     */
    @SuppressWarnings("WeakerAccess")
    protected void doOnFinish(@Nonnull Span span) {
        // no-op
    }

    /**
     * Designed for subclasses to override and implement custom on error behaviour.
     *
     * @param throwable The error
     * @param span The span
     */
    @SuppressWarnings("WeakerAccess")
    protected void doOnError(@Nonnull Throwable throwable, @Nonnull Span span) {
        // no-op
    }

    private void onError(Throwable t, Span span) {
        logError(span, t);
        doOnError(t, span);
    }
}
