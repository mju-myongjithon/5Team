package com.example.myeongranghoe.service;

import com.example.myeongranghoe.domain.Funding;
import com.example.myeongranghoe.domain.UserAccount;
import com.example.myeongranghoe.dto.AiPredictionResponse;
import com.example.myeongranghoe.repository.FundingRepository;
import com.example.myeongranghoe.repository.UserAccountRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class SuccessPredictionService {
    private final FundingRepository fundingRepository;
    private final UserAccountRepository userAccountRepository;
    private final GeminiAiClient geminiAiClient;
    private final ObjectMapper objectMapper;

    public SuccessPredictionService(
            FundingRepository fundingRepository,
            UserAccountRepository userAccountRepository,
            GeminiAiClient geminiAiClient
    ) {
        this.fundingRepository = fundingRepository;
        this.userAccountRepository = userAccountRepository;
        this.geminiAiClient = geminiAiClient;
        this.objectMapper = new ObjectMapper();
    }

    @Transactional(readOnly = true)
    public AiPredictionResponse predict(Long fundingId) {
        Funding funding = fundingRepository.findById(fundingId)
                .orElseThrow(() -> new IllegalArgumentException("펀딩을 찾을 수 없어요."));
        UserAccount host = userAccountRepository.findByEmail(funding.getHostEmail()).orElse(null);
        AiPredictionResponse fallback = predictByRules(funding, host);
        return predictWithGemini(funding, host, fallback).orElse(fallback);
    }

    private Optional<AiPredictionResponse> predictWithGemini(
            Funding funding,
            UserAccount host,
            AiPredictionResponse fallback
    ) {
        if (!geminiAiClient.isConfigured()) {
            return Optional.empty();
        }

        String prompt = """
                너는 대학생 크라우드펀딩형 모임 서비스 '명랑회'의 성사율 예측 AI야.
                펀딩 글의 설득력, 장소/시간 명확성, 목표 인원 대비 현재 인원, 개최자 신뢰도,
                노쇼 이력, 참여 이력을 종합해서 성사 가능성을 예측해.
                반드시 JSON 하나만 응답해.
                형식: {"score":0-100,"level":"높음|보통|낮음","reasons":["이유1","이유2"],"recommendations":["개선 제안1"]}

                참고용 백업 점수: %d
                제목: %s
                카테고리: %s
                설명: %s
                장소명: %s
                주소: %s
                모임 시간: %s %s
                모집 마감: %s %s
                목표 인원: %d
                현재 인원: %d
                남은 인원: %d
                개최자 햇살지수: %d
                개최자 노쇼 횟수: %d
                개최자 참여 횟수: %d
                """.formatted(
                fallback.score(),
                safe(funding.getTitle()),
                safe(funding.getCategory()),
                safe(funding.getDescription()),
                safe(funding.getLocationName()),
                safe(funding.getAddress()),
                safe(funding.getMeetAt()),
                safe(funding.getMeetTimeText()),
                safe(funding.getDeadlineAt()),
                safe(funding.getDeadlineText()),
                funding.getTargetCount(),
                funding.currentCount(),
                Math.max(0, funding.getTargetCount() - funding.currentCount()),
                host == null ? 50 : host.getSunlightScore(),
                host == null ? 0 : host.getNoShowCount(),
                host == null ? 0 : host.getParticipationCount()
        );

        return geminiAiClient.generate(prompt).flatMap(this::parsePrediction);
    }

    private Optional<AiPredictionResponse> parsePrediction(String raw) {
        try {
            JsonNode root = objectMapper.readTree(sliceJson(raw));
            int score = clamp(root.path("score").asInt(-1));
            if (score < 0) {
                return Optional.empty();
            }
            String level = safe(root.path("level").asText(deriveLevel(score)));
            List<String> reasons = readStringList(root.path("reasons"));
            List<String> recommendations = readStringList(root.path("recommendations"));
            if (reasons.isEmpty()) {
                reasons = List.of("AI가 펀딩 정보와 개최자 신뢰도를 종합해 판단했어요.");
            }
            if (recommendations.isEmpty()) {
                recommendations = List.of("장소, 시간, 비용, 모임 분위기를 더 구체적으로 적어보세요.");
            }
            return Optional.of(new AiPredictionResponse(score, level, reasons, recommendations, true));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private AiPredictionResponse predictByRules(Funding funding, UserAccount host) {
        int score = 0;
        int current = Math.max(0, funding.currentCount());
        int target = Math.max(1, funding.getTargetCount());
        int remain = Math.max(0, target - current);

        score += Math.min(40, Math.round((current * 40f) / target));
        if (remain == 0) score += 25;
        else if (remain == 1) score += 20;
        else if (remain <= 3) score += 10;

        int descriptionLength = safe(funding.getDescription()).length();
        if (descriptionLength >= 80) score += 10;
        else if (descriptionLength >= 30) score += 5;
        else score -= 5;

        if (!safe(funding.getLocationName()).isBlank()) score += 5;
        if (!safe(funding.getAddress()).isBlank()) score += 5;
        if (!safe(funding.getMeetAt()).isBlank() || !safe(funding.getMeetTimeText()).isBlank()) score += 5;
        if (!safe(funding.getDeadlineAt()).isBlank() || !safe(funding.getDeadlineText()).isBlank()) score += 5;

        if (host != null) {
            if (host.getSunlightScore() >= 85) score += 10;
            else if (host.getSunlightScore() >= 70) score += 7;
            else if (host.getSunlightScore() < 30) score -= 15;

            if (host.getNoShowCount() >= 2) score -= 20;
            else if (host.getNoShowCount() == 1) score -= 8;

            if (host.getParticipationCount() >= 5) score += 5;
        }

        score = clamp(score);
        List<String> reasons = new ArrayList<>();
        reasons.add(remain <= 1 ? "목표 인원까지 거의 도달했어요." : "목표 인원까지 " + remain + "명이 더 필요해요.");
        reasons.add(descriptionLength >= 30 ? "모임 설명이 충분히 작성되어 있어요." : "모임 설명이 짧아 판단 정보가 부족해요.");
        if (host != null) {
            reasons.add("개최자 햇살지수 " + host.getSunlightScore() + "점, 노쇼 " + host.getNoShowCount() + "회 이력이 반영됐어요.");
        }

        List<String> recommendations = score >= 75
                ? List.of("마지막 한 명에게 공유하기 좋은 짧은 넛지 문구를 노출해보세요.")
                : List.of("장소, 시간, 예상 분위기, 비용을 더 구체적으로 적으면 참여 결정을 돕습니다.");

        return new AiPredictionResponse(score, deriveLevel(score), reasons, recommendations, false);
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

    private int clamp(int value) {
        if (value < 0) return 0;
        return Math.min(100, value);
    }

    private String deriveLevel(int score) {
        if (score >= 75) return "높음";
        if (score >= 45) return "보통";
        return "낮음";
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
