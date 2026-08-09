package com.aegis.policy.application;

import java.time.LocalDate;

/** @param effectiveFrom the first day the policy is off risk. May be backdated. */
public record CancelPolicyCommand(LocalDate effectiveFrom, String reason) {}
