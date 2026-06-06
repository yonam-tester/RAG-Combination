package com.yeonam.tester.service;

import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GithubService {

    private final HttpClient httpClient;
    private static final Pattern GITHUB_PATTERN = Pattern.compile(
            "^(https?://)?(www\\.)?github\\.com/([a-zA-Z0-9-_.]+)/([a-zA-Z0-9-_.]+)(/.*)?$"
    );

    public GithubService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Parse and validate GitHub URL.
     * Returns an array where [0] is Owner and [1] is Repository Name (without .git).
     */
    public String[] parseGithubUrl(String githubUrl) {
        if (githubUrl == null || githubUrl.isBlank()) {
            throw new IllegalArgumentException("GitHub URL cannot be empty");
        }
        Matcher matcher = GITHUB_PATTERN.matcher(githubUrl.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid GitHub URL format. Must be like https://github.com/owner/repo");
        }
        String owner = matcher.group(3);
        String repo = matcher.group(4);
        if (repo.endsWith(".git")) {
            repo = repo.substring(0, repo.length() - 4);
        }
        return new String[]{owner, repo};
    }

    /**
     * Checks if the repository is public and exists.
     * Returns the default branch name (e.g. "main" or "master").
     */
    public String verifyRepositoryAndGetDefaultBranch(String githubUrl) {
        String[] parsed = parseGithubUrl(githubUrl);
        String owner = parsed[0];
        String repo = parsed[1];

        // 1. Try to query GitHub Repository API
        String apiUrl = String.format("https://api.github.com/repos/%s/%s", owner, repo);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("User-Agent", "yeonam-tester-app")
                    .header("Accept", "application/vnd.github.v3+json")
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                // Parse default branch from JSON manually to avoid extra library dependency
                String body = response.body();
                String defaultBranch = extractJsonKeyValue(body, "default_branch");
                return defaultBranch != null ? defaultBranch : "main";
            } else if (response.statusCode() == 404) {
                throw new IllegalArgumentException("GitHub repository not found or is private.");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            System.err.println("GitHub API check failed: " + e.getMessage() + ". Falling back to direct URL head check.");
        }

        // 2. Fallback: Check if the repository page returns 200 OK
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(String.format("https://github.com/%s/%s", owner, repo)))
                    .header("User-Agent", "yeonam-tester-app")
                    .timeout(Duration.ofSeconds(5))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() == 200) {
                return "main"; // Fallback default
            } else {
                throw new IllegalArgumentException("GitHub repository is not accessible (Status: " + response.statusCode() + ").");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to connect to GitHub to verify repository: " + e.getMessage());
        }
    }

    /**
     * Downloads README.md from the repository's specified branch.
     * Returns the text content of README.md, or an empty string if not found.
     */
    public String downloadReadme(String githubUrl, String branch) {
        try {
            String[] parsed = parseGithubUrl(githubUrl);
            String owner = parsed[0];
            String repo = parsed[1];
            String defaultBranch = (branch == null || branch.isBlank()) ? "main" : branch;

            // Try README.md
            String rawUrl = String.format("https://raw.githubusercontent.com/%s/%s/%s/README.md", owner, repo, defaultBranch);
            String content = fetchText(rawUrl);
            if (content != null) return content;

            // Try readme.md
            rawUrl = String.format("https://raw.githubusercontent.com/%s/%s/%s/readme.md", owner, repo, defaultBranch);
            content = fetchText(rawUrl);
            if (content != null) return content;

            // Try README.txt
            rawUrl = String.format("https://raw.githubusercontent.com/%s/%s/%s/README.txt", owner, repo, defaultBranch);
            content = fetchText(rawUrl);
            if (content != null) return content;

        } catch (Exception e) {
            System.err.println("Failed to download README: " + e.getMessage());
        }
        return "";
    }

    private String fetchText(String urlStr) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(urlStr))
                    .header("User-Agent", "yeonam-tester-app")
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return response.body();
            }
        } catch (Exception e) {
            System.err.println("Error fetching URL: " + urlStr + ". " + e.getMessage());
        }
        return null;
    }

    /**
     * Fetches repository description from GitHub API.
     * Returns description text, or null if private/errors.
     */
    public String fetchRepositoryDescription(String githubUrl) {
        try {
            String[] parsed = parseGithubUrl(githubUrl);
            String owner = parsed[0];
            String repo = parsed[1];
            String apiUrl = String.format("https://api.github.com/repos/%s/%s", owner, repo);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("User-Agent", "yeonam-tester-app")
                    .header("Accept", "application/vnd.github.v3+json")
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return extractJsonKeyValue(response.body(), "description");
            }
        } catch (Exception e) {
            System.err.println("Failed to fetch GitHub repo description: " + e.getMessage());
        }
        return null;
    }

    private String extractJsonKeyValue(String json, String key) {
        String searchPattern = "\"" + key + "\"\\s*:\\s*\"([^\"]+)\"";
        Pattern pattern = Pattern.compile(searchPattern);
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}
