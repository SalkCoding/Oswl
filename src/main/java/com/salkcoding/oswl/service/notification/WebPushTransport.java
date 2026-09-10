package com.salkcoding.oswl.service.notification;

import com.salkcoding.oswl.dto.api.WebPushSubscriptionRequest;
import com.salkcoding.oswl.exception.InvalidRequestException;
import com.salkcoding.oswl.security.OutboundUrlValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Service @RequiredArgsConstructor
public class WebPushTransport {
    private final OutboundUrlValidator validator;
    @Value("${OSWL_WEB_PUSH_PUBLIC_KEY:}") private String publicKey;
    @Value("${OSWL_WEB_PUSH_PRIVATE_KEY:}") private String privateKey;
    @Value("${OSWL_WEB_PUSH_SUBJECT:}") private String subject;
    @Value("${oswl.airgapped.enabled:false}") private boolean airgapped;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    public boolean enabled() { return !airgapped && !publicKey.isBlank() && !privateKey.isBlank() && !subject.isBlank(); }
    public String publicKey() { return enabled() ? publicKey : ""; }

    public void validate(WebPushSubscriptionRequest request) {
        try {
            URI uri=URI.create(request.endpoint());
            String host=uri.getHost();
            if (request.endpoint().length()>2048 || !"https".equals(uri.getScheme()) || uri.getUserInfo()!=null || uri.getFragment()!=null || uri.getPort()!=-1
                    || host==null || !(Set.of("fcm.googleapis.com","updates.push.services.mozilla.com","web.push.apple.com").contains(host)
                    || host.endsWith(".push.apple.com"))) throw new IllegalArgumentException();
            byte[] key=Base64.getUrlDecoder().decode(request.p256dh());
            if (key.length!=65 || key[0]!=4 || Base64.getUrlDecoder().decode(request.auth()).length!=16
                    || !Set.of("en","ko","ja").contains(request.locale())) throw new IllegalArgumentException();
            validator.validateHttpUrl(request.endpoint());
        } catch (Exception e) { throw new InvalidRequestException("Invalid or unsupported browser push subscription"); }
    }

    public int send(WebPushSubscriptionRequest subscription,String payload) throws Exception {
        if (!enabled()) return 503;
        validate(subscription);
        if (java.security.Security.getProvider("BC")==null) java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        var notification=new nl.martijndwars.webpush.Notification(subscription.endpoint(),subscription.p256dh(),subscription.auth(),payload.getBytes(StandardCharsets.UTF_8),3600);
        var prepared=new PreparedPush(publicKey,privateKey,subject).prepare(notification);
        URI target=URI.create(prepared.getUrl());
        if(!target.getHost().equals(URI.create(subscription.endpoint()).getHost())) throw new IllegalStateException("Push destination changed");
        var request=HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(10));
        prepared.getHeaders().forEach(request::header);
        request.POST(HttpRequest.BodyPublishers.ofByteArray(prepared.getBody()));
        return http.send(request.build(),HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static final class PreparedPush extends nl.martijndwars.webpush.AbstractPushService<PreparedPush> {
        PreparedPush(String publicKey,String privateKey,String subject) throws java.security.GeneralSecurityException {super(publicKey,privateKey,subject);}
        nl.martijndwars.webpush.HttpRequest prepare(nl.martijndwars.webpush.Notification notification) throws Exception {
            return prepareRequest(notification,nl.martijndwars.webpush.Encoding.AES128GCM);
        }
    }
}
