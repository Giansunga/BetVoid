package com.betvoid.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiClassifierContractTest {
    private val contract = AiClassifierContract(
        schemaVersion = 5,
        contractVersion = "domain-v5",
        safeDomains = setOf("google.com", "better.com"),
        tokens = setOf("casino", "bet365", "1xbet", "bc.game"),
        gamblingTlds = setOf("casino", "bet"),
        candidateTlds = setOf("bar", "top", "site"),
        mirrorBrands = setOf("1x", "1xbet", "1xlite", "xbet", "melbet"),
        thresholds = mapOf(AiSensitivity.STRICT to 50, AiSensitivity.BALANCED to 70, AiSensitivity.RELAXED to 85),
        modelConfidence = mapOf(AiSensitivity.STRICT to .80, AiSensitivity.BALANCED to .85, AiSensitivity.RELAXED to .92),
    )

    @Test fun androidAndServerUseTheSameVersionedContract() {
        val android = File("src/main/assets/ai/classifier-contract.json").readText().filterNot(Char::isWhitespace)
        val server = File("../supabase/functions/_shared/classifier-contract.json").readText().filterNot(Char::isWhitespace)
        assertEquals(android, server)
        assertTrue(android.contains("\"schemaVersion\":5"))
        assertTrue(android.contains("\"strict\":50"))
        assertTrue(android.contains("\"balanced\":70"))
        assertTrue(android.contains("\"relaxed\":85"))
    }

    @Test fun rotatingMirrorsBlockOnTheirFirstRequest() {
        for (host in listOf("1xlite-68638.bar", "img.1xbet99.top", "1xbet.site", "xbet-2026.top", "melbet.org", "melbet.com", "melbet.bar", "melbetofficial.com")) {
            val decision = contract.evaluate(host, AiSensitivity.BALANCED)
            assertTrue("Expected heuristic block for $host but got $decision", decision is DomainRiskDecision.HeuristicBlock)
            assertTrue((decision as DomainRiskDecision.HeuristicBlock).evaluation.score >= 70)
            assertEquals(GamblingCategory.SPORTS_BETTING, decision.evaluation.category)
        }
    }

    @Test fun benignLookalikesAndSafeSubdomainsRemainSafe() {
        for (host in listOf("better.com", "mail.google.com", "alphabet.org", "1xample.com", "xbetter.com")) {
            assertTrue("Expected safe for $host", contract.evaluate(host, AiSensitivity.STRICT) is DomainRiskDecision.Safe)
        }
    }

    @Test fun thresholdsChangeTheSameHostnameDecision() {
        assertTrue(contract.evaluate("casino.example", AiSensitivity.STRICT) is DomainRiskDecision.RemoteCandidate)
        assertTrue(contract.evaluate("casino.bet", AiSensitivity.BALANCED) is DomainRiskDecision.HeuristicBlock)
        assertTrue(contract.evaluate("casino.bet", AiSensitivity.RELAXED) is DomainRiskDecision.RemoteCandidate)
        assertEquals(50, contract.threshold(AiSensitivity.STRICT))
        assertEquals(70, contract.threshold(AiSensitivity.BALANCED))
        assertEquals(85, contract.threshold(AiSensitivity.RELAXED))
    }

    @Test fun knownRulesAndSafeListHaveExplicitPrecedence() {
        val known = RuleMatch(RuleAction.BLOCK, GamblingCategory.CASINO, RuleSource.CUSTOM)
        assertTrue(contract.evaluate("mail.google.com", AiSensitivity.BALANCED, known) is DomainRiskDecision.KnownBlock)
        assertTrue(contract.evaluate("mail.google.com", AiSensitivity.BALANCED) is DomainRiskDecision.Safe)
    }

    @Test fun belowThresholdHostsDoNotProduceHeuristicBlocks() {
        // Hosts that score above zero but below the chosen threshold (e.g. STRICT=50) must
        // NOT be reported as HeuristicBlocks. The contract still flags them as candidates
        // (RemoteCandidate) for backwards compatibility, but the new local-only classifier
        // (added later) treats them as non-blocking.
        val belowThreshold = listOf("casino-news.example", "review-bet.example.org")
        for (host in belowThreshold) {
            val decision = contract.evaluate(host, AiSensitivity.STRICT)
            assertTrue("Expected non-block for $host at STRICT, got $decision", decision !is DomainRiskDecision.HeuristicBlock)
        }
    }

    @Test fun sameHostnameCanBlockAtStrictButStaySafeAtRelaxed() {
        // "casino.bet" is a gambling TLD + domain token, exceeding STRICT (50) and BALANCED (70)
        // but under RELAXED (85) when there is no mirror structure; the new local-only
        // classifier must treat it as a HeuristicBlock at STRICT and a non-block at RELAXED.
        val strictDecision = contract.evaluate("casino.bet", AiSensitivity.STRICT)
        val relaxedDecision = contract.evaluate("casino.bet", AiSensitivity.RELAXED)
        assertTrue("Expected HeuristicBlock at STRICT for casino.bet, got $strictDecision", strictDecision is DomainRiskDecision.HeuristicBlock)
        assertTrue("Expected non-block at RELAXED for casino.bet, got $relaxedDecision", relaxedDecision !is DomainRiskDecision.HeuristicBlock)
    }
}
