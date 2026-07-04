package com.reconai.matching;

public record PassStatDto(int passNum, String matchType, int matchedCount, long elapsedMs) {}
