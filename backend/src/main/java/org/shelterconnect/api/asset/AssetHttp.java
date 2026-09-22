package org.shelterconnect.api.asset;

import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;

final class AssetHttp {
    static HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    record Response(int status, byte[] body) {}
    static Response send(HttpClient client, HttpRequest request, int limit) throws Exception {
        var future=client.sendAsync(request,info->new LimitedBody(limit));
        try {
            var response=future.get(request.timeout().orElse(Duration.ofSeconds(45)).toMillis(),TimeUnit.MILLISECONDS);
            return new Response(response.statusCode(),response.body());
        } catch (Exception e) { future.cancel(true); throw e; }
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
        private final int limit;
        private Flow.Subscription subscription;
        private long size;
        LimitedBody(int limit) { this.limit=limit; }
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription s) { subscription=s; delegate.onSubscribe(s); }
        public void onNext(List<ByteBuffer> items) {
            for (var item:items) size+=item.remaining();
            if (size>limit) { subscription.cancel(); delegate.onError(new IllegalStateException("Response limit")); }
            else delegate.onNext(items);
        }
        public void onError(Throwable e) { delegate.onError(e); }
        public void onComplete() { delegate.onComplete(); }
    }
}
