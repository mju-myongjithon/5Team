package com.example.myeongranghoe.dto;

import java.util.List;

public record AiPredictionResponse(
        int score,
        String level,
        List<String> reasons,
        List<String> recommendations,
        boolean aiGenerated
) {}
