package io.quarkus.arc.impl;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@Interceptor
@ActivateRequestContext
@Priority(Interceptor.Priority.PLATFORM_BEFORE + 100)
public class ActivateRequestContextInterceptor {

    @AroundInvoke
    Object aroundInvoke(InvocationContext ctx) throws Exception {
        switch (ReactiveType.valueOf(ctx.getMethod())) {
            case UNI:
                return invokeUni(ctx);
            case MULTI:
                return invokeMulti(ctx);
            case STAGE:
                return invokeStage(ctx);
            default:
                return invoke(ctx);
        }
    }

    private CompletionStage<?> invokeStage(InvocationContext ctx) {
        ManagedContext requestContext = Arc.container().requestContext();
        if (requestContext.isActive()) {
            return proceedWithStage(ctx);
        }

        return activate(requestContext)
                .thenCompose(v -> proceedWithStage(ctx))
                .whenComplete((r, t) -> requestContext.terminate());
    }

    private static CompletionStage<ManagedContext> activate(ManagedContext requestContext) {
        try {
            requestContext.activate();
            return CompletableFuture.completedStage(requestContext);
        } catch (Throwable t) {
            return CompletableFuture.failedStage(t);
        }
    }

    private CompletionStage<?> proceedWithStage(InvocationContext ctx) {
        try {
            return (CompletionStage<?>) ctx.proceed();
        } catch (Throwable t) {
            return CompletableFuture.failedStage(t);
        }
    }

    private Multi<?> invokeMulti(InvocationContext ctx) {
        return Multi.createFrom().deferred(() -> {
            ManagedContext requestContext = Arc.container().requestContext();
            if (requestContext.isActive()) {
                return proceedWithMulti(ctx);
            }

            return Multi.createFrom().deferred(() -> {
                requestContext.activate();
                return proceedWithMulti(ctx);
            }).onTermination().invoke(requestContext::terminate);
        });
    }

    private Multi<?> proceedWithMulti(InvocationContext ctx) {
        try {
            return (Multi<?>) ctx.proceed();
        } catch (Throwable t) {
            return Multi.createFrom().failure(t);
        }
    }

    private Uni<?> invokeUni(InvocationContext ctx) {
        return Uni.createFrom().deferred(() -> {
            ManagedContext requestContext = Arc.container().requestContext();
            if (requestContext.isActive()) {
                return proceedWithUni(ctx);
            }

            return Uni.createFrom().deferred(() -> {
                requestContext.activate();
                return proceedWithUni(ctx);
            }).eventually(requestContext::terminate);
        });
    }

    private Uni<?> proceedWithUni(InvocationContext ctx) {
        try {
            return (Uni<?>) ctx.proceed();
        } catch (Throwable t) {
            return Uni.createFrom().failure(t);
        }
    }

    private Object invoke(InvocationContext ctx) throws Exception {
        ManagedContext requestContext = Arc.container().requestContext();
        if (requestContext.isActive()) {
            return ctx.proceed();
        }

        try {
            requestContext.activate();
            return ctx.proceed();
        } finally {
            requestContext.terminate();
        }
    }
}
