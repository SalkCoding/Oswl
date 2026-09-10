package com.salkcoding.oswl.service.notification;

import com.salkcoding.oswl.dto.api.WebPushSubscriptionRequest;
import com.salkcoding.oswl.security.OutboundUrlValidator;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.Flow;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WebPushTransportTest {
    @Test void createsEncryptedVapidRequestWithoutSendingToAProvider() throws Exception {
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        var generator=KeyPairGenerator.getInstance("ECDH","BC");generator.initialize(org.bouncycastle.jce.ECNamedCurveTable.getParameterSpec("prime256v1"));
        var server=generator.generateKeyPair();var browser=generator.generateKeyPair();
        var transport=new WebPushTransport(mock(OutboundUrlValidator.class));
        ReflectionTestUtils.setField(transport,"publicKey",publicKey(server));
        ReflectionTestUtils.setField(transport,"privateKey",Base64.getUrlEncoder().withoutPadding().encodeToString(org.bouncycastle.util.BigIntegers.asUnsignedByteArray(32,((org.bouncycastle.jce.interfaces.ECPrivateKey)server.getPrivate()).getD())));
        ReflectionTestUtils.setField(transport,"subject","mailto:operator@example.test");
        HttpClient client=mock(HttpClient.class);ReflectionTestUtils.setField(transport,"http",client);
        @SuppressWarnings("unchecked") HttpResponse<Void> response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(201);
        when(client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var subscription=new WebPushSubscriptionRequest("https://fcm.googleapis.com/fcm/send/fixture",publicKey(browser),Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]),true,true,"en");
        assertThat(transport.send(subscription,"confidential plaintext fixture")).isEqualTo(201);
        var captor=org.mockito.ArgumentCaptor.forClass(HttpRequest.class);verify(client).send(captor.capture(),any(HttpResponse.BodyHandler.class));
        var request=captor.getValue();
        assertThat(request.headers().firstValue("Authorization").orElseThrow()).startsWith("vapid ");
        assertThat(request.headers().firstValue("Content-Encoding")).contains("aes128gcm");
        assertThat(request.timeout()).contains(java.time.Duration.ofSeconds(10));
        var bytes=new java.io.ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription s){s.request(Long.MAX_VALUE);}
            public void onNext(ByteBuffer b){byte[] data=new byte[b.remaining()];b.get(data);bytes.writeBytes(data);}
            public void onError(Throwable e){throw new AssertionError(e);}
            public void onComplete(){}
        });
        assertThat(bytes.size()).isGreaterThan(30);
        assertThat(bytes.toString(StandardCharsets.UTF_8)).doesNotContain("confidential plaintext fixture");
    }
    @Test void rejectsArbitraryEndpointBeforeNetworkValidation() {
        var validator=mock(OutboundUrlValidator.class);var transport=new WebPushTransport(validator);
        for(String endpoint:List.of("http://localhost/push","https://example.org/push","https://fcm.googleapis.com.evil.test/push","https://user:secret@fcm.googleapis.com/push"))
            assertThatThrownBy(()->transport.validate(new WebPushSubscriptionRequest(endpoint,"key","auth",true,true,"en"))).isInstanceOf(com.salkcoding.oswl.exception.InvalidRequestException.class);
        verifyNoInteractions(validator);
    }
    private String publicKey(KeyPair pair){return Base64.getUrlEncoder().withoutPadding().encodeToString(((org.bouncycastle.jce.interfaces.ECPublicKey)pair.getPublic()).getQ().getEncoded(false));}
}
