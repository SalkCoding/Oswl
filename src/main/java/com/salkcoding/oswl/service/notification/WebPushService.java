package com.salkcoding.oswl.service.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.auth.enums.Permission;
import com.salkcoding.oswl.auth.repository.UserRepository;
import com.salkcoding.oswl.auth.security.*;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.domain.entity.notification.*;
import com.salkcoding.oswl.dto.api.*;
import com.salkcoding.oswl.exception.InvalidRequestException;
import com.salkcoding.oswl.repository.notification.*;
import com.salkcoding.oswl.repository.project.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

@Service @RequiredArgsConstructor
public class WebPushService {
    private final WebPushSubscriptionRepository subscriptions;
    private final WebPushDeliveryRepository deliveries;
    private final UserRepository users;
    private final ProjectMemberRepository members;
    private final ProjectRepository projects;
    private final EncryptionService encryption;
    private final WebPushTransport transport;
    private final AuditLogService audit;
    private final MessageSource messages;
    private final ObjectMapper mapper=new ObjectMapper();

    @Transactional(readOnly=true)
    public WebPushStatus status() {
        var principal=current();
        List<WebPushStatus.Subscription> result=new ArrayList<>();
        for(var sub:subscriptions.findByUserId(principal.getUserId())) {
            try {var value=decode(sub); result.add(new WebPushStatus.Subscription(sub.getId(),sub.getEndpointHash(),value.newHighRisk(),value.gateFailure(),sub.getExpiresAt(),sub.getExpiresAt().isAfter(LocalDateTime.now())));}
            catch(Exception ignored) { /* An unreadable expired key is not exposed to the browser. */ }
        }
        boolean enabled=transport.enabled() && principal.hasPermission(Permission.SECURITY_CENTER_VIEW);
        return new WebPushStatus(enabled,enabled?transport.publicKey():"",result);
    }

    @Transactional
    public Long subscribe(WebPushSubscriptionRequest request) {
        var principal=current();
        if (!principal.hasPermission(Permission.SECURITY_CENTER_VIEW)) throw new AccessDeniedException("Security Center permission required");
        if (!transport.enabled()) throw new InvalidRequestException("Browser push is not configured or is disabled offline");
        transport.validate(request);
        cleanup();
        try {
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(request.endpoint().getBytes(StandardCharsets.UTF_8)));
            var existing=subscriptions.findByEndpointHash(hash);
            if (existing.isPresent() && !existing.get().getUserId().equals(principal.getUserId())) throw new AccessDeniedException("This browser subscription belongs to another account; unsubscribe in the browser first");
            if (existing.isEmpty() && (subscriptions.countByUserId(principal.getUserId())>=10 || subscriptions.count()>=1000)) throw new InvalidRequestException("Browser subscription limit reached");
            var sub=existing.orElseGet(()->WebPushSubscription.builder().userId(principal.getUserId()).endpointHash(hash).build());
            sub.setEncryptedSubscription(encryption.encrypt(mapper.writeValueAsString(request)));
            sub.setExpiresAt(LocalDateTime.now().plusDays(30));
            Long id=subscriptions.saveAndFlush(sub).getId();
            audit.log("WEB_PUSH.SUBSCRIBE","WEB_PUSH",id.toString(),null,"subscription preferences updated");
            return id;
        } catch (AccessDeniedException|InvalidRequestException e) {throw e;}
        catch(Exception e) {throw new InvalidRequestException("Unable to save browser subscription");}
    }

    @Transactional
    public void unsubscribe(Long id) {
        var sub=subscriptions.findById(id).orElse(null);
        if (sub==null) return;
        if (!sub.getUserId().equals(current().getUserId())) throw new AccessDeniedException("Subscription owner required");
        deliveries.deleteBySubscriptionId(id); subscriptions.delete(sub);
        audit.log("WEB_PUSH.UNSUBSCRIBE","WEB_PUSH",id.toString(),null,null);
    }

    @Transactional
    public void enqueue(Long projectId,String type,String eventKey) {
        if (!transport.enabled() || projectId==null || eventKey==null || !Set.of("NEW_HIGH_RISK","GATE_FAILURE").contains(type)) return;
        for(var sub:subscriptions.findAll(PageRequest.of(0,1000))) {
            if (sub.getExpiresAt().isBefore(LocalDateTime.now()) || !canRead(sub.getUserId(),projectId)) continue;
            WebPushSubscriptionRequest prefs;
            try { prefs=decode(sub); } catch (InvalidRequestException ignored) { continue; }
            if (!selected(prefs,type) || deliveries.existsBySubscriptionIdAndEventKey(sub.getId(),eventKey)) continue;
            deliveries.save(WebPushDelivery.builder().subscriptionId(sub.getId()).projectId(projectId).eventType(type).eventKey(eventKey)
                    .nextAttempt(LocalDateTime.now()).expiresAt(LocalDateTime.now().plusDays(1)).build());
        }
    }

    @Transactional(readOnly=true)
    public List<Long> due() {return deliveries.findByFinishedFalseAndNextAttemptBeforeOrderByIdAsc(LocalDateTime.now(),PageRequest.of(0,10)).stream().map(WebPushDelivery::getId).toList();}

    @Transactional
    public WebPushAttempt prepare(Long id) {
        var delivery=deliveries.findById(id).orElse(null);
        if (delivery==null || delivery.isFinished()) return null;
        if (delivery.getAttempts()>=3) {delivery.setFinished(true);return null;}
        var sub=subscriptions.findById(delivery.getSubscriptionId()).orElse(null);
        if (sub==null || sub.getExpiresAt().isBefore(LocalDateTime.now()) || delivery.getExpiresAt().isBefore(LocalDateTime.now()) || !canRead(sub.getUserId(),delivery.getProjectId())) {
            delivery.setFinished(true); return null;
        }
        try {
            var prefs=decode(sub);
            if (!selected(prefs,delivery.getEventType())) {delivery.setFinished(true);return null;}
            String message=messages.getMessage("webPush.event."+delivery.getEventType(),null,Locale.forLanguageTag(prefs.locale()));
            String payload=mapper.writeValueAsString(Map.of("title","OsWL","body",message,"url","/projects/"+delivery.getProjectId()+"/security-center","tag",delivery.getEventKey()));
            delivery.setAttempts(delivery.getAttempts()+1);
            delivery.setNextAttempt(LocalDateTime.now().plusMinutes(5));
            return new WebPushAttempt(id,prefs,payload);
        } catch(Exception e) {delivery.setFinished(true);return null;}
    }

    @Transactional
    public void complete(Long id,int status) {
        var delivery=deliveries.findById(id).orElse(null); if(delivery==null)return;
        delivery.setLastStatus(status);
        delivery.setFinished((status>=200 && status<300) || status==404 || status==410 || delivery.getAttempts()>=3);
        if(status==404 || status==410) {
            subscriptions.findById(delivery.getSubscriptionId()).ifPresent(s->s.setExpiresAt(LocalDateTime.now()));
        }
        audit.log("WEB_PUSH.DELIVERY","WEB_PUSH",id.toString(),null,"status="+status+", attempt="+delivery.getAttempts());
    }

    @Transactional public void cleanup() {
        var now=LocalDateTime.now();
        deliveries.deleteByExpiresAtBefore(now);
        for(var sub:subscriptions.findByExpiresAtBefore(now)) {
            deliveries.deleteBySubscriptionId(sub.getId());
            subscriptions.delete(sub);
        }
    }
    private WebPushSubscriptionRequest decode(WebPushSubscription sub) {
        try{return mapper.readValue(encryption.decrypt(sub.getEncryptedSubscription()),WebPushSubscriptionRequest.class);}
        catch(Exception e){throw new InvalidRequestException("Stored browser subscription is unreadable");}
    }
    private boolean selected(WebPushSubscriptionRequest prefs,String type) {return "NEW_HIGH_RISK".equals(type)?prefs.newHighRisk():prefs.gateFailure();}
    private boolean canRead(Long userId,Long projectId) {
        var project=projects.findById(projectId).orElse(null);
        if(project==null || project.getDeletedAt()!=null) return false;
        var user=users.findById(userId).orElse(null);
        if(user==null || !user.isEnabled() || user.isMustChangePassword())return false;
        return user.isSystemAdmin() || (members.existsByProjectIdAndUserId(projectId,userId) && user.getRoleTemplates().stream().anyMatch(r->r.getPermissions().contains(Permission.SECURITY_CENTER_VIEW)));
    }
    private OswlUserPrincipal current() {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof OswlUserPrincipal principal) || !principal.isEnabled())throw new AccessDeniedException("Signed-in account required");
        return principal;
    }
}
