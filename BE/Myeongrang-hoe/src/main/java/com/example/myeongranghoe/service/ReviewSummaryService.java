package com.example.myeongranghoe.service;

import com.example.myeongranghoe.domain.Review;
import com.example.myeongranghoe.domain.UserAccount;
import com.example.myeongranghoe.dto.ReviewSummaryResponse;
import com.example.myeongranghoe.repository.ReviewRepository;
import com.example.myeongranghoe.repository.UserAccountRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ReviewSummaryService {
    private final ReviewRepository reviewRepository;
    private final UserAccountRepository userAccountRepository;
    private final GeminiAiClient geminiAiClient;
    private final ObjectMapper objectMapper;

    public ReviewSummaryService(
            ReviewRepository reviewRepository,
            UserAccountRepository userAccountRepository,
            GeminiAiClient geminiAiClient
    ) {
        this.reviewRepository = reviewRepository;
        this.userAccountRepository = userAccountRepository;
        this.geminiAiClient = geminiAiClient;
        this.objectMapper = new ObjectMapper();
    }

    @Transactional(readOnly = true)
    public ReviewSummaryResponse summarize(String email) {
        UserAccount user = userAccountRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없어요."));
        List<Review> reviews = reviewRepository.findByTargetEmailOrderByCreatedAtDesc(email);
        ReviewSummaryResponse fallback = summarizeByRules(user, reviews);
        return summarizeWithGemini(user, reviews).orElse(fallback);
    }

    private Optional<ReviewSummaryResponse> summarizeWithGemini(UserAccount user, List<Review> reviews) {
        if (!geminiAiClient.isConfigured() || reviews.isEmpty()) {
            return Optional.empty();
        }

        StringBuilder reviewLines = new StringBuilder();
        for (Review review : reviews.stream().limit(8).toList()) {
            reviewLines.append("- noShow=")
                    .append(review.isNoShow())
                    .append(", checklist=")
                    .append(review.getChecklist())
                    .append(", content=")
                    .append(safe(review.getContent()))
                    .append('\n');
        }

        String prompt = """
                너는 대학생 모임 서비스 '명랑회'의 후기 요약 AI야.
                아래 개최자의 받은 후기를 요약해서 신뢰 포인트와 주의 포인트를 분리해.
                반드시 JSON 하나만 응답해.
                형식: {"summary":"한 문장 요약","highlights":["장점1","장점2"],"riskNotes":["주의1"]}

                개최자 이름: %s
                햇살지수: %d
                노쇼 횟수: %d
                참여 횟수: %d
                받은 후기:
                %s
                """.formatted(
                safe(user.getName()),
                user.getSunlightScore(),
                user.getNoShowCount(),
                user.getParticipationCount(),
                reviewLines
        );

        return geminiAiClient.generate(prompt).flatMap(this::parseSummary);
    }

    private Optional<ReviewSummaryResponse> parseSummary(String raw) {
        try {
            JsonNode root = objectMapper.readTree(sliceJson(raw));
            String summary = safe(root.path("summary").asText(""));
            List<String> highlights = readStringList(root.path("highlights"));
            List<String> riskNotes = readStringList(root.path("riskNotes"));
            if (summary.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new ReviewSummaryResponse(summary, highlights, riskNotes, true));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private ReviewSummaryResponse summarizeByRules(UserAccount user, List<Review> reviews) {
        if (reviews.isEmpty()) {
            return new ReviewSummaryResponse(
                    "아직 누적된 후기가 없어 AI 요약을 만들기 어렵습니다.",
                    List.of("모임 후 후기가 쌓이면 신뢰 포인트를 요약할 수 있어요."),
                    user.getNoShowCount() > 0 ? List.of("노쇼 이력이 있어 참여 전 일정 확인이 필요해요.") : List.of(),
                    false
            );
        }

        Map<String, Integer> checklistCounts = new LinkedHashMap<>();
        int noShowReviews = 0;
        for (Review review : reviews) {
            if (review.isNoShow()) {
                noShowReviews++;
            }
            for (String item : review.getChecklist()) {
                checklistCounts.merge(item, 1, Integer::sum);
            }
        }

        List<String> highlights = checklistCounts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(3)
                .map(e -> e.getKey() + " 평가가 " + e.getValue() + "번 있었어요.")
                .toList();

        List<String> riskNotes = new ArrayList<>();
        if (user.getNoShowCount() > 0 || noShowReviews > 0) {
            riskNotes.add("노쇼 관련 기록이 있어 약속 시간 확인이 필요해요.");
        }
        if (user.getSunlightScore() < 40) {
            riskNotes.add("햇살지수가 낮아 후기와 댓글을 함께 확인하는 편이 좋아요.");
        }

        String summary = safe(user.getName()) + "님은 받은 후기 " + reviews.size()
                + "개 기준으로 햇살지수 " + user.getSunlightScore()
                + "점의 개최자입니다.";
        return new ReviewSummaryResponse(summary, highlights, riskNotes, false);
    }

    private List<String> readStringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (!node.isArray()) {
            return values;
        }
        for (JsonNode item : node) {
            String value = safe(item.asText(""));
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    private String sliceJson(String raw) {
        String text = safe(raw)
                .replaceAll("(?i)```json", "")
                .replace("```", "")
                .trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("AI 응답에서 JSON을 찾을 수 없어요.");
        }
        return text.substring(start, end + 1);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
