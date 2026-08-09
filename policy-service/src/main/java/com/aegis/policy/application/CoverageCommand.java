package com.aegis.policy.application;

import com.aegis.contracts.money.Money;
import java.util.Set;

public record CoverageCommand(String coverageCode, Money limit, Money deductible, Set<String> exclusions) {}
