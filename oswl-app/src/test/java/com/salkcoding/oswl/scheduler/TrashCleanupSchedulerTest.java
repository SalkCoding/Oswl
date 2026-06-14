package com.salkcoding.oswl.scheduler;

import org.junit.jupiter.api.Tag;
import com.salkcoding.oswl.testing.TestTags;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.repository.ProjectRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Tag(TestTags.FAST)
@ExtendWith(MockitoExtension.class)
@DisplayName("TrashCleanupScheduler 단위 테스트")
class TrashCleanupSchedulerTest {

    @Mock ProjectRepository projectRepository;
    @InjectMocks TrashCleanupScheduler scheduler;

    @Test
    @DisplayName("purgeExpiredTrash: 만료된 프로젝트가 있으면 모두 삭제한다")
    void purgeExpiredTrash_deletesExpiredProjects() {
        Project p1 = mock(Project.class);
        Project p2 = mock(Project.class);
        when(projectRepository.findAllByDeletedAtBefore(any(LocalDateTime.class)))
                .thenReturn(List.of(p1, p2));

        scheduler.purgeExpiredTrash();

        verify(projectRepository).deleteAll(List.of(p1, p2));
    }

    @Test
    @DisplayName("purgeExpiredTrash: 만료된 프로젝트가 없으면 deleteAll을 호출하지 않는다")
    void purgeExpiredTrash_nothingExpired_doesNotCallDelete() {
        when(projectRepository.findAllByDeletedAtBefore(any(LocalDateTime.class)))
                .thenReturn(List.of());

        scheduler.purgeExpiredTrash();

        verify(projectRepository, never()).deleteAll(anyList());
    }

    @Test
    @DisplayName("purgeExpiredTrash: cutoff는 현재 시각으로부터 약 30일 이전이다")
    void purgeExpiredTrash_cutoffIsThirtyDaysAgo() {
        when(projectRepository.findAllByDeletedAtBefore(any())).thenReturn(List.of());

        LocalDateTime before = LocalDateTime.now().minusDays(30).minusSeconds(2);
        scheduler.purgeExpiredTrash();
        LocalDateTime after  = LocalDateTime.now().minusDays(30).plusSeconds(2);

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(projectRepository).findAllByDeletedAtBefore(captor.capture());
        LocalDateTime cutoff = captor.getValue();

        assertThat(cutoff).isAfter(before).isBefore(after);
    }
}
