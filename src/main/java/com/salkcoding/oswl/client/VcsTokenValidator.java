package com.salkcoding.oswl.client;

import com.salkcoding.oswl.auth.enums.VcsProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Base64;

/**
 * 저장 전에 프로바이더의 사용자 엔드포인트를 호출하여 VCS 액세스 토큰을 검증한다.
 * 토큰이 유효하지 않거나 서버에 도달할 수 없으면 IllegalStateException을 던진다.
 */
@Slf4j
@Component
public class VcsTokenValidator {

    public void validate(VcsProvider provider, String serverUrl, String accessToken, String vcsUsername) {
        switch (provider) {
            case GITHUB    -> validateGitHub(serverUrl, accessToken);
            case GITLAB    -> validateGitLab(serverUrl, accessToken);
            case BITBUCKET -> validateBitbucket(serverUrl, accessToken, vcsUsername);
        }
    }

    private void validateGitHub(String serverUrl, String token) {
        String base = (serverUrl != null && !serverUrl.isBlank())
                ? serverUrl.replaceAll("/+$", "") + "/api/v3"
                : "https://api.github.com";
        RestClient client = RestClient.builder()
                .baseUrl(base)
                .defaultHeader("Accept", "application/vnd.github+json")
                .build();
        try {
            client.get().uri("/user")
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403) {
                throw new IllegalStateException("GitHub 토큰이 유효하지 않거나 필요한 권한이 없습니다.");
            }
            throw new IllegalStateException("GitHub 오류 응답: " + status);
        } catch (RestClientException e) {
            log.warn("[VcsTokenValidator] GitHub에 도달할 수 없음 ({}): {}", base, e.getMessage());
            throw new IllegalStateException("GitHub에 연결하여 토큰을 확인할 수 없습니다. 서버 URL을 확인하고 다시 시도하세요.");
        }
    }

    private void validateGitLab(String serverUrl, String token) {
        String base = (serverUrl != null && !serverUrl.isBlank())
                ? serverUrl.replaceAll("/+$", "")
                : "https://gitlab.com";
        RestClient client = RestClient.builder().baseUrl(base).build();
        try {
            // 일반 PAT: /personal_access_tokens/self는 스코프 없이 동작.
            // 세분화된 프로젝트 스코프 토큰은 403을 반환할 수 있음 — 이 경우 프로젝트 목록으로 폴백.
            client.get().uri("/api/v4/personal_access_tokens/self")
                    .header("PRIVATE-TOKEN", token)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            if (status == 401) {
                throw new IllegalStateException("GitLab 토큰이 유효하지 않거나 필요한 권한이 없습니다.");
            }
            if (status == 403) {
                // 프로젝트 스코프 세분화 토큰은 사용자 엔드포인트에 접근할 수 없음.
                // 프로젝트 목록으로 확인.
                validateGitLabViaProjects(client, token);
                return;
            }
            throw new IllegalStateException("GitLab 오류 응답: " + status);
        } catch (RestClientException e) {
            log.warn("[VcsTokenValidator] GitLab에 도달할 수 없음 ({}): {}", base, e.getMessage());
            throw new IllegalStateException("GitLab에 연결하여 토큰을 확인할 수 없습니다. 서버 URL을 확인하고 다시 시도하세요.");
        }
    }

    private void validateGitLabViaProjects(RestClient client, String token) {
        try {
            client.get().uri("/api/v4/projects?membership=true&per_page=1")
                    .header("PRIVATE-TOKEN", token)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            if (status == 401) {
                throw new IllegalStateException("GitLab 토큰이 유효하지 않거나 만료되었습니다.");
            }
            // 403 전체 = 토큰은 구조적으로 유효하지만 read_api 스코프 부족.
            // API 스코프 없는 세분화 토큰이 이 경로를 탈.
            // 연결은 허용 — 스코프 부족은 임포트 시에 오류로 표면됨.
            log.warn("[VcsTokenValidator] GitLab 세분화 토큰에 read_api 스코프가 없을 수 있음 (HTTP {}). 연결은 저장됩니다.", status);
        } catch (RestClientException e) {
            log.warn("[VcsTokenValidator] GitLab 프로젝트 목록 폴백 실패: {}", e.getMessage());
            throw new IllegalStateException("GitLab에 연결하여 토큰을 확인할 수 없습니다. 서버 URL을 확인하고 다시 시도하세요.");
        }
    }

    private void validateBitbucket(String serverUrl, String token, String username) {
        boolean isCloud = (serverUrl == null || serverUrl.isBlank());
        if (isCloud) {
            validateBitbucketCloud(token, username);
        } else {
            validateBitbucketServer(serverUrl, token);
        }
    }

    private void validateBitbucketCloud(String tokenOrAppPassword, String username) {
        boolean hasUsername = username != null && !username.isBlank();
        // 앱 비밀번호 → Basic 인증 (username 필수).
        // HTTP 액세스 토큰 (ATATT, 엔티티 스코프) → Bearer 인증 (username 없음).
        // NOTE: /2.0/user는 사용자 수준 인증 필요, 엔티티 스코프 토큰에는 403 반환.
        // 토큰 전용 모드에서는 /2.0/workspaces로 검증.
        String authHeader = hasUsername
                ? "Basic " + Base64.getEncoder().encodeToString((username + ":" + tokenOrAppPassword).getBytes())
                : "Bearer " + tokenOrAppPassword;
        RestClient client = RestClient.builder()
                .baseUrl("https://api.bitbucket.org")
                .build();
        try {
            if (hasUsername) {
                // Basic 인증 앱 비밀번호 — 사용자 엔드포인트로 검증.
                client.get().uri("/2.0/user")
                        .header("Authorization", authHeader)
                        .retrieve()
                        .toBodilessEntity();
            } else {
                // Bearer 토큰 (ATATT) — 엔티티 스코프 토큰은 /2.0/user 호출 불가.
                // /2.0/workspaces로 검증; 403은 토큰은 유효하지만
                // 워크스페이스 수준보다 제한된 스코프 (프로젝트/리포 토큰) 의맸 — 경고와 함께 허용.
                try {
                    client.get().uri("/2.0/workspaces?pagelen=1")
                            .header("Authorization", authHeader)
                            .retrieve()
                            .toBodilessEntity();
                } catch (HttpClientErrorException e) {
                    if (e.getStatusCode().value() == 403) {
                        log.warn("[VcsTokenValidator] Bitbucket HTTP 액세스 토큰은 유효하지만 워크스페이스 스코프 부족 (HTTP 403). 연결을 저장합니다.");
                        return;
                    }
                    if (e.getStatusCode().value() == 401) {
                        // /2.0/workspaces에 Bearer 인증 실패(401) = 사용자 수준 API 토큰 (App Passwords 대체)일 수 있음.
                        // username을 입력하도록 안내.
                        throw new IllegalStateException(
                                "토큰이 유효하지 않거나 Basic 인증이 필요합니다. " +
                                "Bitbucket API 토큰(App Passwords 대체)인 경우 Bitbucket 사용자 이름도 입력하세요.");
                    }
                    throw e;
                }
            }
        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403) {
                throw new IllegalStateException("Bitbucket 자격증명이 유효하지 않거나 필요한 권한이 없습니다.");
            }
            throw new IllegalStateException("Bitbucket 오류 응답: " + status);
        } catch (RestClientException e) {
            log.warn("[VcsTokenValidator] Bitbucket Cloud에 도달할 수 없음: {}", e.getMessage());
            throw new IllegalStateException("Bitbucket에 연결하여 자격증명을 확인할 수 없습니다. 다시 시도하세요.");
        }
    }

    /** Bitbucket Data Center / Server 인스턴스에 Personal Access Token을 검증한다. */
    private void validateBitbucketServer(String serverUrl, String personalAccessToken) {
        String base = serverUrl.replaceAll("/+$", "");
        RestClient client = RestClient.builder().baseUrl(base).build();
        try {
            client.get().uri("/rest/api/1.0/repos?limit=1")
                    .header("Authorization", "Bearer " + personalAccessToken)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403) {
                throw new IllegalStateException("Bitbucket Server 토큰이 유효하지 않거나 필요한 권한이 없습니다.");
            }
            throw new IllegalStateException("Bitbucket Server 오류 응답: " + status);
        } catch (RestClientException e) {
            log.warn("[VcsTokenValidator] Bitbucket Server에 도달할 수 없음 ({}): {}", base, e.getMessage());
            throw new IllegalStateException("Bitbucket Server에 연결하여 토큰을 확인할 수 없습니다. 서버 URL을 확인하고 다시 시도하세요.");
        }
    }
}
