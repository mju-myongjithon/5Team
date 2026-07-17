package com.example.myeongranghoe.dto;

import java.util.List;

public record ReviewSummaryResponse(
        String summary,
        List<String> highlights,
        List<String> riskNotes,
        boolean aiGenerated
) {}
