package com.learnthink.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 视频信息代理接口
 * 后端代理请求 B站/YouTube API，避免前端 CORS 问题
 */
@Slf4j
@RestController
@RequestMapping("/video-info")
public class VideoInfoController {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 获取视频信息（标题、封面）
     * GET /video-info?platform=bilibili&bvid=BV1yC4y127uj
     */
    @GetMapping
    public Result<Map<String, String>> getVideoInfo(
            @RequestParam String platform,
            @RequestParam String videoId) {
        try {
            if ("bilibili".equals(platform)) {
                return getBilibiliInfo(videoId);
            } else if ("youtube".equals(platform)) {
                return getYoutubeInfo(videoId);
            } else if ("icourse163".equals(platform)) {
                return getIcourse163Info(videoId);
            } else if ("xuetangx".equals(platform)) {
                return getXuetangxInfo(videoId);
            }
            return Result.error("不支持的平台: " + platform);
        } catch (Exception e) {
            log.warn("获取视频信息失败: platform={}, videoId={}, error={}", platform, videoId, e.getMessage());
            return Result.error("获取视频信息失败");
        }
    }

    /**
     * B站：通过公开 API 获取视频标题和封面
     */
    private Result<Map<String, String>> getBilibiliInfo(String bvid) throws Exception {
        String url = "https://api.bilibili.com/x/web-interface/view?bvid=" + bvid;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("User-Agent", "Mozilla/5.0")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = objectMapper.readTree(response.body());
        int code = root.path("code").asInt(-1);
        if (code == 0) {
            JsonNode data = root.path("data");
            String title = data.path("title").asText("");
            String pic = data.path("pic").asText("");
            return Result.success(Map.of("title", title, "thumbnail", pic));
        }
        return Result.error("B站API返回错误: code=" + code);
    }

    /**
     * YouTube：通过 oEmbed API 获取视频标题
     */
    private Result<Map<String, String>> getYoutubeInfo(String videoId) throws Exception {
        String url = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v="
                + videoId + "&format=json";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("User-Agent", "Mozilla/5.0")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = objectMapper.readTree(response.body());
        String title = root.path("title").asText("");
        String thumbnail = "https://img.youtube.com/vi/" + videoId + "/hqdefault.jpg";
        return Result.success(Map.of("title", title, "thumbnail", thumbnail));
    }

    /**
     * 中国大学MOOC：爬取课程页面获取标题和封面
     * videoId 为完整的课程URL，如 https://www.icourse163.org/course/ZJU-1464119172?tid=1487018441
     */
    private Result<Map<String, String>> getIcourse163Info(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        String html = response.body();

        // 提取标题：<span class="course-title f-ib f-vam">标题</span>
        String title = "";
        Matcher titleMatcher = Pattern
                .compile("<span class=\"course-title[^\"]*\">([^<]+)</span>")
                .matcher(html);
        if (titleMatcher.find()) {
            title = titleMatcher.group(1).trim();
        }

        // 提取封面：<img class="img" ... src="..." ...>
        String thumbnail = "";
        Matcher imgMatcher = Pattern
                .compile("<img class=\"img\"[^>]*src=\"([^\"]+)\"")
                .matcher(html);
        if (imgMatcher.find()) {
            thumbnail = imgMatcher.group(1).trim();
            // 处理协议相对URL (//nos.netease.com/...)
            if (thumbnail.startsWith("//")) {
                thumbnail = "https:" + thumbnail;
            }
        }

        if (title.isEmpty() && thumbnail.isEmpty()) {
            return Result.error("无法解析课程信息");
        }

        return Result.success(Map.of("title", title, "thumbnail", thumbnail));
    }

    /**
     * 学堂在线：从课程页面 HTML 的 meta 标签提取标题
     * 学堂在线是 SPA，封面图由 JS 动态加载，无法通过 HTTP 请求获取
     * videoId 为完整的课程URL，如 https://www.xuetangx.com/course/zjsru0809bt0909/16912227
     */
    private Result<Map<String, String>> getXuetangxInfo(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        String html = response.body();

        // 学堂在线是 SPA，但 <head> 中有 <title>课程名 - 学校 - 学堂在线</title>
        // 优先从 <meta itemprop="name"> 提取，兜底用 <title> 标签
        String title = "";
        Matcher metaMatcher = Pattern
                .compile("<meta[^>]+itemprop=\"name\"[^>]+content=\"([^\"]+)\"")
                .matcher(html);
        if (metaMatcher.find()) {
            title = metaMatcher.group(1).trim();
        } else {
            Matcher titleMatcher = Pattern
                    .compile("<title>([^<]+)</title>")
                    .matcher(html);
            if (titleMatcher.find()) {
                title = titleMatcher.group(1).trim();
            }
        }
        // 标题格式 "课程名 - 学校 - 学堂在线"，取第一段
        if (title.contains(" - ")) {
            title = title.split(" - ")[0].trim();
        }

        if (title.isEmpty()) {
            return Result.error("无法解析课程信息");
        }

        // 封面无法通过 HTTP 获取（SPA 动态加载），返回空字符串
        return Result.success(Map.of("title", title, "thumbnail", ""));
    }
}
