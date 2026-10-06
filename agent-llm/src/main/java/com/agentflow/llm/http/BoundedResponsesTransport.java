package com.agentflow.llm.http;

import com.agentflow.core.context.HistoryStore;
import com.agentflow.llm.ModelClientException;
import tools.jackson.databind.*;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** One bounded, cancellable HTTP attempt. No automatic retries of model work. */
public final class BoundedResponsesTransport {
    public record Config(String baseUrl,String apiKey,String model,Duration timeout,int maxResponseBytes,int maxOutputTokens) {
        public Config {
            Objects.requireNonNull(baseUrl); Objects.requireNonNull(timeout);
            URI uri;
            try { uri=URI.create(baseUrl); } catch(IllegalArgumentException bad){throw new ModelClientException(ModelClientException.CONFIGURATION_ERROR,"Invalid Responses base URL");}
            if(!uri.isAbsolute() || uri.getHost()==null || !Set.of("https","http").contains(uri.getScheme()) || uri.getUserInfo()!=null || uri.getFragment()!=null || uri.getQuery()!=null)
                throw new ModelClientException(ModelClientException.CONFIGURATION_ERROR,"Invalid Responses base URL");
            if(apiKey==null||apiKey.isBlank()||model==null||model.isBlank())throw new ModelClientException(ModelClientException.CONFIGURATION_ERROR,"Responses key and model are required");
            if(timeout.isZero()||timeout.isNegative()||maxResponseBytes<1||maxResponseBytes>8*1024*1024||maxOutputTokens<1||maxOutputTokens>32768)
                throw new ModelClientException(ModelClientException.CONFIGURATION_ERROR,"Invalid Responses request limits");
        }
    }
    /** Carries bounded usage metadata only, never provider diagnostic text or reasoning payload. */
    public static final class ProviderFailure extends ModelClientException {
        private final JsonNode usageResponse;
        private ProviderFailure(int status, JsonNode usageResponse) {
            super(ModelClientException.PROVIDER_ERROR,"Responses HTTP request failed (status "+status+")");
            this.usageResponse=usageResponse;
        }
        public JsonNode usageResponse() { return usageResponse.deepCopy(); }
    }
    private final Config config;
    private final ObjectMapper mapper;
    private final HttpClient client;
    public BoundedResponsesTransport(Config config,ObjectMapper mapper) {
        this.config=Objects.requireNonNull(config);this.mapper=Objects.requireNonNull(mapper);
        client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public Config config(){return config;}
    public JsonNode execute(Map<String,Object> body,HistoryStore.Io io) {
        check(io);
        Instant deadline=min(io.deadline(),Instant.now().plus(config.timeout()));
        Duration timeout=Duration.between(Instant.now(),deadline);
        if(timeout.isNegative()||timeout.isZero())throw timedOut();
        final byte[] requestBytes;
        try {requestBytes=mapper.writeValueAsBytes(body);}catch(RuntimeException bad){throw new ModelClientException(ModelClientException.CONFIGURATION_ERROR,"Invalid Responses request body",bad);}
        String endpoint=config.baseUrl().replaceAll("/+$","")+"/responses";
        HttpRequest request=HttpRequest.newBuilder(URI.create(endpoint)).timeout(timeout)
                .header("Authorization","Bearer "+config.apiKey()).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(requestBytes)).build();
        CompletableFuture<HttpResponse<byte[]>> pending=client.sendAsync(request,info->new LimitedBody(config.maxResponseBytes(),info.headers().firstValueAsLong("Content-Length").orElse(-1)));
        try {
            HttpResponse<byte[]> response;
            for(;;){
                check(io);
                if(!Instant.now().isBefore(deadline))throw timedOut();
                try{response=pending.get(Math.max(1,Math.min(100,Duration.between(Instant.now(),deadline).toMillis())),TimeUnit.MILLISECONDS);break;}
                catch(TimeoutException waiting){/* bounded polling permits cancellation even while no bytes arrive */}
            }
            check(io); // A late response cannot generate a decision after cancellation.
            JsonNode result;
            try{result=mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(response.body());}
            catch(RuntimeException bad){throw new ModelClientException(ModelClientException.MALFORMED_MODEL_RESPONSE,"Invalid Responses JSON",bad);}
            if(response.statusCode()<200||response.statusCode()>=300) {
                var usage=mapper.createObjectNode();
                if(result!=null&&result.hasNonNull("usage"))usage.set("usage",result.get("usage").deepCopy());
                throw new ProviderFailure(response.statusCode(),usage);
            }
            return result;
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new ModelClientException("CANCELLED","Responses request cancelled");}
        catch(ExecutionException failure){
            Throwable cause=failure.getCause();
            while(cause!=null && cause.getCause()!=null && !(cause instanceof ModelClientException))cause=cause.getCause();
            if(cause instanceof ModelClientException known)throw known;
            if(cause instanceof HttpTimeoutException)throw timedOut();
            throw new ModelClientException(ModelClientException.PROVIDER_ERROR,"Responses transport failed",cause);
        }finally{if(!pending.isDone())pending.cancel(true);}
    }
    private static Instant min(Instant a,Instant b){return a.isBefore(b)?a:b;}
    public static void check(HistoryStore.Io io){
        if(io.cancellation().isCancelled()||Thread.currentThread().isInterrupted())throw new ModelClientException("CANCELLED","Responses request cancelled");
        if(!Instant.now().isBefore(io.deadline()))throw timedOut();
    }
    private static ModelClientException timedOut(){return new ModelClientException("TIMED_OUT","Responses request timed out");}
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream data=new ByteArrayOutputStream(8192);
        private final int limit; private final long declared; private Flow.Subscription subscription;
        LimitedBody(int limit,long declared){this.limit=limit;this.declared=declared;}
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription subscription){this.subscription=subscription;if(declared>limit)fail();else subscription.request(1);}
        public void onNext(List<ByteBuffer> buffers){
            for(ByteBuffer buffer:buffers){if(buffer.remaining()>limit-data.size()){fail();return;}byte[] piece=new byte[buffer.remaining()];buffer.get(piece);data.writeBytes(piece);}
            subscription.request(1);
        }
        public void onError(Throwable failure){result.completeExceptionally(failure);}
        public void onComplete(){if(!result.isDone())result.complete(data.toByteArray());}
        private void fail(){if(subscription!=null)subscription.cancel();result.completeExceptionally(new ModelClientException("RESPONSE_TOO_LARGE","Responses body exceeds byte limit"));}
    }
}
