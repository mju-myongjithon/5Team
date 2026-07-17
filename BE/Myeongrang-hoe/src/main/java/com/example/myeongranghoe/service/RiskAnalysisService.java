package com.example.myeongranghoe.service;

import com.example.myeongranghoe.domain.Funding;
import com.example.myeongranghoe.domain.UserAccount;
import com.example.myeongranghoe.repository.UserAccountRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * PRD 노쇼 리스크와 성사 임박 넛지 구현.
 * Gemini API를 우선 사용하고, API 키가 없거나 호출에 실패하면 규칙 기반 분석으로 폴백한다.
 */
@Service
public class RiskAnalysisService {
    private final UserAccountRepository userAccountRepository;
    private final GeminiAiClient geminiAiClient;

    public RiskAnalysisService(UserAccountRepository userAccountRepository, GeminiAiClient geminiAiClient) {
        this.userAccountRepository = userAccountRepository;
        this.geminiAiClient = geminiAiClient;
    }

    public String analyze(Funding funding) {
        UserAccount host = userAccountRepository.findByEmail(funding.getHostEmail()).orElse(null);
        return analyzeWithGemini(funding, host).orElseGet(() -> analyzeByRules(funding, host));
    }

    private Optional<String> analyzeWithGemini(Funding funding, UserAccount host) {
        if (!geminiAiClient.isConfigured()) {
            return Optional.empty();
        }

        String prompt = """
                너는 대학생 크라우드펀딩형 모임 서비스 '명랑회'의 노쇼 리스크 분석 AI야.
                글의 구체성, 장소/시간 명확성, 모집 마감 정보, 개최자 햇살지수, 노쇼 이력, 참여 이력을 종합해서 판단해.
                반드시 JSON 하나만 응답해. risk 값은 "낮음", "중간", "높음" 중 하나여야 해.
                예: {"risk":"낮음","reason":"장소와 시간이 명확하고 개최자 신뢰도가 높음"}

                제목: %s
                카테고리: %s
                설명: %s
                장소명: %s
                주소: %s
                모임 시간: %s %s
                모집 마감: %s %s
                목표 인원: %d
                현재 인원: %d
                개최자 햇살지수: %d
                개최자 노쇼 횟수: %d
                개최자 참여 횟수: %d
                """.formatted(
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
                host == null ? 50 : host.getSunlightScore(),
                host == null ? 0 : host.getNoShowCount(),
                host == null ? 0 : host.getParticipationCount()
        );

        return geminiAiClient.generate(prompt).flatMap(this::extractRisk);
    }

    private String analyzeByRules(Funding funding, UserAccount host) {
        int score = 0;

        int descriptionLength = safe(funding.getDescription()).length();
        if (descriptionLength >= 80) {
            score += 2;
        } else if (descriptionLength >= 30) {
            score += 1;
        } else {
            score -= 1;
        }

        if (!safe(funding.getLocationName()).isBlank()) {
            score += 1;
        }
        if (!safe(funding.getAddress()).isBlank()) {
            score += 1;
        }
        if (!safe(funding.getMeetAt()).isBlank() || !safe(funding.getMeetTimeText()).isBlank()) {
            score += 1;
        }
        if (!safe(funding.getDeadlineAt()).isBlank() || !safe(funding.getDeadlineText()).isBlank()) {
            score += 1;
        }

        if (host != null) {
            if (host.getNoShowCount() >= 3) {
                return "높음";
            }
            if (host.getNoShowCount() == 2) {
                score -= 3;
            } else if (host.getNoShowCount() == 1) {
                score -= 1;
            }

            if (host.getSunlightScore() >= 85) {
                score += 3;
            } else if (host.getSunlightScore() >= 70) {
                score += 2;
            } else if (host.getSunlightScore() >= 50) {
                score += 1;
            } else if (host.getSunlightScore() < 30) {
                score -= 2;
            } else {
                score -= 1;
            }

            if (host.getParticipationCount() >= 5) {
                score += 1;
            }
        }

        if (score >= 5) {
            return "낮음";
        }
        if (score >= 2) {
            return "중간";
        }
        return "높음";
    }

    public String buildNudgeMessage(Funding funding) {
        return buildNudgeWithGemini(funding).orElseGet(() -> buildNudgeByRules(funding));
    }

    private Optional<String> buildNudgeWithGemini(Funding funding) {
        if (!geminiAiClient.isConfigured()) {
            return Optional.empty();
        }

        int remain = Math.max(0, funding.getTargetCount() - funding.currentCount());
        String prompt = """
                너는 대학생 모임 크라우드펀딩 플랫폼 '명랑회'의 AI 넛지 작성자야.
                아래 상황을 보고 참여를 독려하는 짧은 한국어 문장 1개만 작성해.
                과장하지 말고, 현재 인원/목표 인원/남은 인원을 자연스럽게 반영해.
                설명 없이 문장만 응답해.

                제목: %s
                카테고리: %s
                장소: %s
                시간: %s
                목표 인원: %d
                현재 인원: %d
                남은 인원: %d
                모집 완료 여부: %s
                조기 마감 여부: %s
                """.formatted(
                safe(funding.getTitle()),
                safe(funding.getCategory()),
                safe(funding.getLocationName()),
                safe(funding.getMeetTimeText()),
                funding.getTargetCount(),
                funding.currentCount(),
                remain,
                funding.isMatched() ? "완료" : "진행 중",
                funding.isClosed() ? "마감" : "진행 중"
        );

        return geminiAiClient.generate(prompt).map(this::singleLine).filter(s -> !s.isBlank());
    }

    private String buildNudgeByRules(Funding funding) {
        int remain = Math.max(0, funding.getTargetCount() - funding.currentCount());
        int current = Math.max(0, funding.currentCount());
        int target = Math.max(current, funding.getTargetCount());
        String title = safe(funding.getTitle()).isBlank() ? "이 모임" : funding.getTitle().trim();
        String category = safe(funding.getCategory()).isBlank() ? "모임" : funding.getCategory().trim();

        if (funding.isClosed()) {
            return "호스트가 모집을 마감한 펀딩이에요.";
        }
        if (funding.isMatched()) {
            return "목표 인원이 모두 모였어요. 채팅방에서 시간과 장소를 확정해보세요!";
        }
        if (remain == 0) {
            return "목표 인원이 모두 모였어요. 채팅방에서 시간과 장소를 확정해보세요!";
        }
        if (remain == 1) {
            return "딱 한 명만 더 모이면 \"" + title + "\" " + category + " 모임이 바로 성사돼요. 지금 참여하면 함께 출발할 수 있어요!";
        }
        if (remain <= 3) {
            return "현재 " + current + "/" + target + "명 참여 중이에요. 성사까지 " + remain + "명 남았으니 관심 있는 친구에게 공유해보세요!";
        }
        return "\"" + title + "\" 모집이 진행 중이에요. 비슷한 관심사의 친구들과 함께 참여해보세요.";
    }

    private Optional<String> extractRisk(String raw) {
        String text = safe(raw);
        int riskIndex = text.indexOf("\"risk\"");
        if (riskIndex >= 0) {
            String afterRisk = text.substring(riskIndex);
            if (afterRisk.contains("높음")) return Optional.of("높음");
            if (afterRisk.contains("중간")) return Optional.of("중간");
            if (afterRisk.contains("낮음")) return Optional.of("낮음");
        }
        if (text.contains("높음")) return Optional.of("높음");
        if (text.contains("중간")) return Optional.of("중간");
        if (text.contains("낮음")) return Optional.of("낮음");
        return Optional.empty();
    }

    private String singleLine(String value) {
        String text = safe(value).replace("\r", " ").replace("\n", " ");
        if ((text.startsWith("\"") && text.endsWith("\"")) || (text.startsWith("'") && text.endsWith("'"))) {
            text = text.substring(1, text.length() - 1);
        }
        return text.length() > 160 ? text.substring(0, 160) : text;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
