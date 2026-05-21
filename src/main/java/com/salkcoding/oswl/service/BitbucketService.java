package com.salkcoding.oswl.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Pull Request 생성 및 브랜치 조작을 위한 Bitbucket REST API 클라이언트.
 *
 * Bitbucket Cloud(api.bitbucket.org/2.0)와 Bitbucket Server / Data Center를 모두 지원한다.
 *
 * 인증 방식:
 *   - Cloud + username  → Basic 인증 (Base64(username:token))
 *   - Cloud, username 없음 → Bearer 토큰 (HTTP Access Token / ATATT…)
 *   - Server             → Bearer 토큰
 */
@Slf4j
@Service
public class BitbucketService {

    private static final String CLOUD_API_BASE = "https://api.bitbucket.org/2.0";
    private static final String USER_AGENT      = "OsWL-App/1.0";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient   httpClient   = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * 라이브러리 버전을 업그레이드하는 Bitbucket Pull Request를 생성한다.
     *
     * @param token       Bitbucket 액세스 토큰 또는 앱 비밀번호
     * @param username    Bitbucket 사용자명 (앱 비밀번호/Basic 인증 시 필수; HTTP Access Token인 경우 null)
     * @param serverUrl   null/빈칸 = Bitbucket Cloud; 이외의 경우 Bitbucket Server 브스 URL
     * @param repoPath    {@code Project.githubRepo}에 저장된 "workspace/repo-slug"
     * @param baseBranch  PR 대상 브랜치
     * @param libName     라이브러리 이름
     * @param oldVersion  현재 버전
     * @param newVersion  목표 버전
     * @param prTitle     PR 제목
     * @param prBody      PR 설명
     * @param reviewers   리뷰어 계정 ID 또는 사용자명 목록 (선택사항, 최선)
     * @return "prUrl"(String)과 "prNumber"(int)이 담긴 map
     */
    public Map<String, Object> createVersionBumpPr(String token,
                                                    String username,
                                                    String serverUrl,
                                                    String repoPath,
                                                    String baseBranch,
                                                    String libName,
                                                    String oldVersion,
                                                    String newVersion,
                                                    String prTitle,
                                                    String prBody,
                                                    List<String> reviewers) {
        boolean isCloud   = (serverUrl == null || serverUrl.isBlank());
        String  authHeader = buildAuthHeader(token, username);

        if (isCloud) {
            return createCloudPr(authHeader, repoPath, baseBranch,
                    libName, oldVersion, newVersion, prTitle, prBody, reviewers);
        } else {
            return createServerPr(authHeader, serverUrl, repoPath, baseBranch,
                    libName, oldVersion, newVersion, prTitle, prBody, reviewers);
        }
    }

    /**
     * Bitbucket 리포지토리의 브랜치 이름 목록을 반환한다.
     */
    public List<String> getBranches(String token, String username, String serverUrl, String repoPath) {
        String authHeader = buildAuthHeader(token, username);
        boolean isCloud   = (serverUrl == null || serverUrl.isBlank());
        try {
            if (isCloud) {
                String[] parts  = splitRepoPath(repoPath);
                String url = CLOUD_API_BASE + "/repositories/" + parts[0] + "/" + parts[1]
                        + "/refs/branches?pagelen=50&sort=-target.date";
                JsonNode result = getJson(authHeader, url);
                return extractCloudBranches(result);
            } else {
                return getServerBranches(authHeader, serverUrl, repoPath);
            }
        } catch (Exception e) {
            log.warn("[Bitbucket] {} 브랜치 목록 조회 실패: {}", repoPath, e.getMessage());
            return List.of("main");
        }
    }

    // ── Bitbucket Cloud ───────────────────────────────────────────────────────

    private Map<String, Object> createCloudPr(String authHeader, String repoPath,
                                               String baseBranch, String libName,
                                               String oldVersion, String newVersion,
                                               String prTitle, String prBody,
                                               List<String> reviewers) {
        String[] parts     = splitRepoPath(repoPath);
        String workspace   = parts[0];
        String repoSlug    = parts[1];
        String apiBase     = CLOUD_API_BASE + "/repositories/" + workspace + "/" + repoSlug;

        // 1. 베이스 브랜치 HEAD 코밋 해시 조회
        String branchUrl = apiBase + "/refs/branches/" + URLEncoder.encode(baseBranch, StandardCharsets.UTF_8);
        JsonNode branchInfo = getJson(authHeader, branchUrl);
        String baseSha = branchInfo.path("target").path("hash").asText();

        // 2. 피캘의 브랜치를 만들고 한 c88에 의존성 파일 코밋 업데이트
        String safeName  = libName.replaceAll("[^a-zA-Z0-9._\\-]", "_");
        String newBranch = "oswl/bump-" + safeName + "-" + newVersion;

        updateDependencyFileCloud(authHeader, apiBase, newBranch, baseSha, baseBranch,
                libName, oldVersion, newVersion);

        // 3. Pull Request 생성
        var prNode = objectMapper.createObjectNode();
        prNode.put("title", prTitle);
        prNode.put("description", prBody);
        prNode.putObject("source").putObject("branch").put("name", newBranch);
        prNode.putObject("destination").putObject("branch").put("name", baseBranch);
        prNode.put("close_source_branch", true);

        if (reviewers != null && !reviewers.isEmpty()) {
            var reviewersArray = prNode.putArray("reviewers");
            reviewers.forEach(r -> reviewersArray.addObject().put("account_id", r));
        }

        JsonNode pr    = postJson(authHeader, apiBase + "/pullrequests", prNode.toString());
        int    prId    = pr.path("id").asInt();
        String prUrl   = pr.path("links").path("html").path("href").asText();

        log.info("[Bitbucket Cloud] PR #{} created: {}", prId, prUrl);
        return Map.of("prUrl", prUrl, "prNumber", prId);
    }

    private void updateDependencyFileCloud(String authHeader, String apiBase,
                                            String newBranch, String baseSha, String baseBranch,
                                            String libName, String oldVersion, String newVersion) {
        List<String> candidates = List.of(
                "pom.xml", "build.gradle", "build.gradle.kts",
                "package.json", "requirements.txt", "pyproject.toml",
                "go.mod", "Cargo.toml"
        );

        for (String path : candidates) {
            try {
                // 베이스 브랜치에서 파일 콘텐츠 조회
                String fileUrl = apiBase + "/src/" + URLEncoder.encode(baseBranch, StandardCharsets.UTF_8)
                        + "/" + path;
                String content = getRawText(authHeader, fileUrl);
                if (!content.contains(oldVersion)) continue;

                String updated = content.replace(oldVersion, newVersion);
                String message = "chore: bump " + libName + " from " + oldVersion
                        + " to " + newVersion + " [OsWL]";

                // 새 브랜치에 코밋 (부모가 제공되면 브랜치가 없는 경우 생성됨)
                commitFileCloud(authHeader, apiBase + "/src", path, updated, newBranch, baseSha, message);
                log.info("[Bitbucket Cloud] Updated {} on branch {}", path, newBranch);
                return;
            } catch (Exception e) {
                log.debug("[Bitbucket Cloud] {}를 건너넷: {}", path, e.getMessage());
            }
        }
        log.warn("[Bitbucket Cloud] 의존성 파일 미업데이트 — 알려진 매니페스트에서 '{}' 구 버전을 찾지 못함", oldVersion);
    }

    /**
     * Bitbucket Cloud multipart src 엔드포인트로 단일 파일을 코밋한다.
     * 브랜치가 없으면 주어진 부모 코밋에서 생성한다.
     */
    private void commitFileCloud(String authHeader, String srcUrl,
                                  String filePath, String content,
                                  String branch, String parentSha, String message) throws Exception {
        // Build multipart/form-data 수동 조립
        String boundary = "----OsWLBoundary" + System.currentTimeMillis();
        var sb = new StringBuilder();

        appendFormField(sb, boundary, "branch", branch);
        appendFormField(sb, boundary, "parents", parentSha);
        appendFormField(sb, boundary, "message", message);
        appendFormField(sb, boundary, filePath, content);
        sb.append("--").append(boundary).append("--\r\n");

        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(srcUrl))
                .header("Authorization", authHeader)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401) throw new RuntimeException("Bitbucket 토큰이 유효하지 않거나 만료되었습니다");
        if (response.statusCode() >= 400) {
            log.warn("[Bitbucket Cloud] POST {} → HTTP {} {}", srcUrl, response.statusCode(), response.body());
            throw new RuntimeException("Bitbucket API 오류: " + response.statusCode());
        }
    }

    private List<String> extractCloudBranches(JsonNode result) {
        List<String> branches = new ArrayList<>();
        JsonNode values = result.path("values");
        if (values.isArray()) {
            values.forEach(b -> branches.add(b.path("name").asText()));
        }
        return branches.isEmpty() ? List.of("main") : branches;
    }

    // ── Bitbucket Server / Data Center ────────────────────────────────────────

    private Map<String, Object> createServerPr(String authHeader, String serverUrl,
                                                String repoPath, String baseBranch,
                                                String libName, String oldVersion, String newVersion,
                                                String prTitle, String prBody,
                                                List<String> reviewers) {
        // Server 경로: {serverUrl}/rest/api/1.0/projects/{PROJECT}/repos/{repo}
        // Project key와 repo slug는 githubRepo에 "PROJECT/repo-slug"로 저장됨
        String[] parts      = splitRepoPath(repoPath);
        String projectKey   = parts[0].toUpperCase();
        String repoSlug     = parts[1];
        String apiBase      = serverUrl.replaceAll("/+$", "")
                + "/rest/api/1.0/projects/" + projectKey + "/repos/" + repoSlug;

        // 1. 베이스 브랜치 HEAD 커밋 조회
        String branchUrl = apiBase + "/branches?filterText=" + URLEncoder.encode(baseBranch, StandardCharsets.UTF_8);
        JsonNode branchList = getJson(authHeader, branchUrl);
        String baseSha = "";
        JsonNode values = branchList.path("values");
        if (values.isArray()) {
            for (JsonNode b : values) {
                if (baseBranch.equals(b.path("displayId").asText())) {
                    baseSha = b.path("latestCommit").asText();
                    break;
                }
            }
        }

        // 2. 피처 브랜치 생성
        String safeName  = libName.replaceAll("[^a-zA-Z0-9._\\-]", "_");
        String newBranch = "oswl/bump-" + safeName + "-" + newVersion;
        createServerBranch(authHeader, apiBase, newBranch, baseSha);

        // 3. 의존성 파일 찾아 업데이트
        updateDependencyFileServer(authHeader, apiBase, newBranch, baseBranch,
                libName, oldVersion, newVersion);

        // 4. Pull Request 생성
        var prPayload = objectMapper.createObjectNode();
        prPayload.put("title", prTitle);
        prPayload.put("description", prBody);
        prPayload.putObject("fromRef").put("id", "refs/heads/" + newBranch);
        prPayload.putObject("toRef").put("id", "refs/heads/" + baseBranch);

        JsonNode pr   = postJson(authHeader, apiBase + "/pull-requests", prPayload.toString());
        int    prId   = pr.path("id").asInt();
        String prUrl  = pr.path("links").path("self").get(0).path("href").asText();

        log.info("[Bitbucket Server] PR #{} created: {}", prId, prUrl);
        return Map.of("prUrl", prUrl, "prNumber", prId);
    }

    private void createServerBranch(String authHeader, String apiBase, String branch, String startPoint) {
        String url = apiBase.replaceFirst("/projects/.*", "") + "/rest/api/1.0/projects/"
                + extractProjectKey(apiBase) + "/repos/"
                + extractRepoSlug(apiBase) + "/branches";
        String payload = objectMapper.createObjectNode()
                .put("name", branch)
                .put("startPoint", startPoint)
                .toString();
        postJson(authHeader, url, payload);
    }

    private void updateDependencyFileServer(String authHeader, String apiBase,
                                             String branch, String baseBranch,
                                             String libName, String oldVersion, String newVersion) {
        List<String> candidates = List.of(
                "pom.xml", "build.gradle", "build.gradle.kts",
                "package.json", "requirements.txt", "pyproject.toml",
                "go.mod", "Cargo.toml"
        );

        for (String path : candidates) {
            try {
                String fileUrl = apiBase + "/raw/" + path + "?at=refs/heads/" + baseBranch;
                String content = getRawText(authHeader, fileUrl);
                if (!content.contains(oldVersion)) continue;

                String updated = content.replace(oldVersion, newVersion);
                // Bitbucket Server 파일 업데이트: PUT + multipart
                commitFileServer(authHeader, apiBase, path, updated, branch,
                        "chore: bump " + libName + " from " + oldVersion + " to " + newVersion + " [OsWL]");
                log.info("[Bitbucket Server] Updated {} on branch {}", path, branch);
                return;
            } catch (Exception e) {
                log.debug("[Bitbucket Server] {}를 건너넷: {}", path, e.getMessage());
            }
        }
        log.warn("[Bitbucket Server] '{}' 구 버전에 대한 의존성 파일 미업데이트", oldVersion);
    }

    private void commitFileServer(String authHeader, String apiBase,
                                   String filePath, String content,
                                   String branch, String message) throws Exception {
        String url     = apiBase + "/browse/" + filePath;
        String boundary = "----OsWLBoundary" + System.currentTimeMillis();
        var sb = new StringBuilder();
        appendFormField(sb, boundary, "branch", branch);
        appendFormField(sb, boundary, "message", message);
        appendFormField(sb, boundary, "content", content);
        sb.append("--").append(boundary).append("--\r\n");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("User-Agent", USER_AGENT)
                .PUT(HttpRequest.BodyPublishers.ofString(sb.toString()))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401) throw new RuntimeException("Bitbucket 토큰이 유효하지 않거나 만료되었습니다");
        if (response.statusCode() >= 400) {
            throw new RuntimeException("Bitbucket Server API 오류: " + response.statusCode());
        }
    }

    private List<String> getServerBranches(String authHeader, String serverUrl, String repoPath) {
        String[] parts    = splitRepoPath(repoPath);
        String projectKey = parts[0].toUpperCase();
        String repoSlug   = parts[1];
        String url = serverUrl.replaceAll("/+$", "")
                + "/rest/api/1.0/projects/" + projectKey + "/repos/" + repoSlug
                + "/branches?orderBy=MODIFICATION&limit=50";
        JsonNode result = getJson(authHeader, url);
        List<String> branches = new ArrayList<>();
        JsonNode values = result.path("values");
        if (values.isArray()) {
            values.forEach(b -> branches.add(b.path("displayId").asText()));
        }
        return branches.isEmpty() ? List.of("main") : branches;
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    private String buildAuthHeader(String token, String username) {
        if (username != null && !username.isBlank()) {
            String credentials = username + ":" + token;
            return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        }
        return "Bearer " + token;
    }

    private String[] splitRepoPath(String repoPath) {
        int slash = repoPath.indexOf('/');
        if (slash < 0) throw new IllegalArgumentException("Bitbucket 리포지토리 경로가 유효하지 않음: " + repoPath);
        return new String[]{ repoPath.substring(0, slash), repoPath.substring(slash + 1) };
    }

    private String extractProjectKey(String apiBase) {
        // 패턴: .../projects/{KEY}/repos/...
        int pi = apiBase.indexOf("/projects/");
        int ri = apiBase.indexOf("/repos/", pi);
        return apiBase.substring(pi + 10, ri);
    }

    private String extractRepoSlug(String apiBase) {
        int ri = apiBase.indexOf("/repos/");
        return apiBase.substring(ri + 7);
    }

    private void appendFormField(StringBuilder sb, String boundary, String name, String value) {
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n");
        sb.append(value).append("\r\n");
    }

    private JsonNode getJson(String authHeader, String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", authHeader)
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401) throw new RuntimeException("Bitbucket 토큰이 유효하지 않거나 만료되었습니다");
            if (response.statusCode() >= 400) {
                log.warn("[Bitbucket] GET {} → HTTP {}", url, response.statusCode());
                throw new RuntimeException("Bitbucket API 오류: " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Bitbucket] GET 실패: {}", url, e);
            throw new RuntimeException("Bitbucket API 호출 실패: " + e.getMessage());
        }
    }

    private String getRawText(String authHeader, String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", authHeader)
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401) throw new RuntimeException("Bitbucket 토큰이 유효하지 않거나 만료되었습니다");
            if (response.statusCode() == 404) throw new RuntimeException("파일을 찾을 수 없음: " + url);
            if (response.statusCode() >= 400) {
                throw new RuntimeException("Bitbucket API 오류: " + response.statusCode());
            }
            return response.body();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Bitbucket에서 로우 파일 가져오기 실패: " + e.getMessage());
        }
    }

    private JsonNode postJson(String authHeader, String url, String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", authHeader)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(20))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401) throw new RuntimeException("Bitbucket 토큰이 유효하지 않거나 만료되었습니다");
            if (response.statusCode() >= 400) {
                log.warn("[Bitbucket] POST {} → HTTP {} {}", url, response.statusCode(), response.body());
                throw new RuntimeException("Bitbucket API 오류: " + response.statusCode() + " — " + response.body());
            }
            return objectMapper.readTree(response.body());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Bitbucket] POST 실패: {}", url, e);
            throw new RuntimeException("Bitbucket API 호출 실패: " + e.getMessage());
        }
    }
}
