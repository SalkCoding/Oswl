package com.salkcoding.oswl.service.notification;

import com.salkcoding.oswl.auth.entity.User;
import com.salkcoding.oswl.auth.repository.UserRepository;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.dto.api.WebPushSubscriptionRequest;
import com.salkcoding.oswl.repository.notification.*;
import com.salkcoding.oswl.repository.project.ProjectRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:web-push-check;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS TEXT","oswl.web-push.poll-ms=3600000"})
class WebPushServiceTest {
    @Autowired WebPushService service;
    @Autowired WebPushSubscriptionRepository subscriptions;
    @Autowired WebPushDeliveryRepository deliveries;
    @Autowired UserRepository users;
    @Autowired ProjectRepository projects;
    @MockitoBean WebPushTransport transport;
    private User owner;
    private Project project;
    @BeforeEach void setup() {
        deliveries.deleteAllInBatch(); subscriptions.deleteAllInBatch();
        owner=users.save(User.builder().email(UUID.randomUUID()+"@example.test").displayName("Push owner").passwordHash("test").enabled(true).isSystemAdmin(true).build());
        project=projects.save(Project.builder().name("Confidential project name").build());
        login(owner);
        when(transport.enabled()).thenReturn(true); when(transport.publicKey()).thenReturn("public-key");
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    private void login(User user) {
        var principal=new OswlUserPrincipal(user.getId(),user.getEmail(),"test",user.getDisplayName(),true,true,List.of(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")),Set.of(),Set.of(),false);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,"",principal.getAuthorities()));
    }
    private Long subscribe(){return service.subscribe(new WebPushSubscriptionRequest("https://fcm.googleapis.com/fcm/send/owned-fixture","public-browser-key","private-browser-auth",true,true,"ko"));}

    @Test void encryptsSubscriptionDeduplicatesEventsAndDeletesPendingDeliveriesOnUnsubscribe() {
        Long id=subscribe();
        assertThat(subscriptions.findById(id).orElseThrow().getEncryptedSubscription()).doesNotContain("fcm.googleapis.com","private-browser-auth");
        service.enqueue(project.getId(),"GATE_FAILURE","GATE:fixture"); service.enqueue(project.getId(),"GATE_FAILURE","GATE:fixture");
        assertThat(deliveries.count()).isEqualTo(1);
        var attempt=service.prepare(deliveries.findAll().getFirst().getId());
        assertThat(attempt.payload()).contains("OsWL").doesNotContain(project.getName());
        service.unsubscribe(id); assertThat(subscriptions.count()).isZero(); assertThat(deliveries.count()).isZero();
    }
    @Test void permissionRevocationStopsQueuedDelivery() throws Exception {
        subscribe();service.enqueue(project.getId(),"NEW_HIGH_RISK","CVE:fixture");
        owner.setEnabled(false); users.saveAndFlush(owner);
        Long id=deliveries.findAll().getFirst().getId();
        assertThat(service.prepare(id)).isNull(); assertThat(deliveries.findById(id).orElseThrow().isFinished()).isTrue();
        verify(transport,never()).send(any(),anyString());
    }
    @Test void retriesStopAfterThreeAttemptsAndGoneEndpointsExpire() {
        Long subscription=subscribe();service.enqueue(project.getId(),"GATE_FAILURE","GATE:retry");
        Long id=deliveries.findAll().getFirst().getId();
        for(int i=0;i<3;i++){makeDue(id);assertThat(service.prepare(id)).isNotNull();service.complete(id,503);}
        assertThat(service.prepare(id)).isNull();
        service.enqueue(project.getId(),"GATE_FAILURE","GATE:gone");
        Long gone=deliveries.findAll().stream().filter(d->d.getEventKey().equals("GATE:gone")).findFirst().orElseThrow().getId();
        service.prepare(gone);service.complete(gone,410);
        assertThat(subscriptions.findById(subscription).orElseThrow().getExpiresAt()).isBeforeOrEqualTo(java.time.LocalDateTime.now());
    }
    @Test void anotherAccountCannotRemoveOwnedSubscription() {
        Long id=subscribe();
        var other=users.save(User.builder().email(UUID.randomUUID()+"@example.test").passwordHash("test").displayName("Other").enabled(true).isSystemAdmin(true).build());
        login(other);
        assertThatThrownBy(()->service.unsubscribe(id)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(subscriptions.existsById(id)).isTrue();
    }

    @Test void abandonedAttemptsAreBoundedAndExpiredSubscriptionsAreReclaimed() {
        Long sub=subscribe();service.enqueue(project.getId(),"GATE_FAILURE","GATE:abandoned");
        Long id=deliveries.findAll().getFirst().getId();
        for(int i=0;i<3;i++) {makeDue(id); assertThat(service.prepare(id)).isNotNull();}
        assertThat(service.prepare(id)).isNull();
        var expired=subscriptions.findById(sub).orElseThrow();
        expired.setExpiresAt(java.time.LocalDateTime.now().minusSeconds(1));subscriptions.saveAndFlush(expired);
        assertThat(service.status().subscriptions().getFirst().active()).isFalse();
        service.cleanup();assertThat(subscriptions.count()).isZero();assertThat(deliveries.count()).isZero();
    }
    @Test void unreadableSubscriptionDoesNotBlockOtherSubscribers() {
        var broken=subscriptions.findById(subscribe()).orElseThrow();
        broken.setEncryptedSubscription("unreadable");subscriptions.saveAndFlush(broken);
        service.subscribe(new WebPushSubscriptionRequest("https://fcm.googleapis.com/fcm/send/second-fixture","public-key","private-auth",true,true,"en"));
        service.enqueue(project.getId(),"GATE_FAILURE","GATE:valid-after-broken");
        assertThat(deliveries.count()).isEqualTo(1);
    }
    private void makeDue(Long id) {
        var delivery=deliveries.findById(id).orElseThrow();
        delivery.setNextAttempt(java.time.LocalDateTime.now().minusSeconds(1)); deliveries.saveAndFlush(delivery);
    }
    @Test void concurrentEnqueueIsIdempotentAndOnlyOneWorkerClaimsADelivery() throws Exception {
        subscribe();
        try(var workers=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var barrier=new java.util.concurrent.CyclicBarrier(2);
            java.util.concurrent.Callable<Void> enqueue=()->{barrier.await();service.enqueue(project.getId(),"GATE_FAILURE","GATE:race");return null;};
            var a=workers.submit(enqueue); var b=workers.submit(enqueue);
            a.get(10,java.util.concurrent.TimeUnit.SECONDS);b.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(deliveries.count()).isEqualTo(1);
            Long id=deliveries.findAll().getFirst().getId();
            java.util.concurrent.Callable<Boolean> claim=()->{barrier.await();return service.prepare(id)!=null;};
            var c=workers.submit(claim);var d=workers.submit(claim);
            assertThat(List.of(c.get(10,java.util.concurrent.TimeUnit.SECONDS),d.get(10,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            assertThat(deliveries.findById(id).orElseThrow().getAttempts()).isEqualTo(1);
        }
    }
    @Test void concurrentSubscriptionsCannotExceedTheAccountLimit() throws Exception {
        for(int i=0;i<9;i++) service.subscribe(new WebPushSubscriptionRequest("https://fcm.googleapis.com/fcm/send/limit-"+i,"key","auth",true,true,"en"));
        try(var workers=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var barrier=new java.util.concurrent.CyclicBarrier(2);
            java.util.concurrent.Callable<Boolean> add=()->{
                login(owner);
                try {barrier.await();service.subscribe(new WebPushSubscriptionRequest("https://fcm.googleapis.com/fcm/send/"+UUID.randomUUID(),"key","auth",true,true,"en"));return true;}
                catch(com.salkcoding.oswl.exception.InvalidRequestException e){return false;}
                finally{SecurityContextHolder.clearContext();}
            };
            var a=workers.submit(add);var b=workers.submit(add);
            assertThat(List.of(a.get(10,java.util.concurrent.TimeUnit.SECONDS),b.get(10,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            assertThat(subscriptions.countByUserId(owner.getId())).isEqualTo(10);
        }
    }

}
